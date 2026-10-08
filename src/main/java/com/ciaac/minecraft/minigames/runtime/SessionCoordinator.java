package com.ciaac.minecraft.minigames.runtime;

import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.isolation.PlayerStateGateway;
import com.ciaac.minecraft.minigames.isolation.PlayerStateSnapshot;
import com.ciaac.minecraft.minigames.isolation.SnapshotEnvelopeCodec;
import com.ciaac.minecraft.minigames.isolation.SnapshotRecord;
import com.ciaac.minecraft.minigames.isolation.SnapshotRepository;
import com.ciaac.minecraft.minigames.isolation.SnapshotState;
import com.ciaac.minecraft.minigames.persistence.SessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Transactional orchestration boundary between authentication, durable state,
 * and game-specific preparation.
 *
 * <p>Bukkit adapters must call this on the main server thread for operations
 * touching a live player. Database failures close admission and trigger
 * restoration when any temporary state may have been applied.</p>
 */
public final class SessionCoordinator {
    private final AuthenticationRegistry authentication;
    private final SessionRegistry sessions;
    private final SessionRepository sessionRepository;
    private final SnapshotRepository snapshots;
    private final PlayerStateGateway stateGateway;
    private final IsolationPolicy isolationPolicy;
    private final SnapshotEnvelopeCodec snapshotCodec;
    private final Clock clock;
    private final Consumer<String> failureReporter;

    public SessionCoordinator(
            AuthenticationRegistry authentication,
            SessionRegistry sessions,
            SessionRepository sessionRepository,
            SnapshotRepository snapshots,
            PlayerStateGateway stateGateway,
            IsolationPolicy isolationPolicy,
            SnapshotEnvelopeCodec snapshotCodec,
            Clock clock) {
        this(authentication, sessions, sessionRepository, snapshots, stateGateway,
                isolationPolicy, snapshotCodec, clock, ignored -> {});
    }

    public SessionCoordinator(
            AuthenticationRegistry authentication,
            SessionRegistry sessions,
            SessionRepository sessionRepository,
            SnapshotRepository snapshots,
            PlayerStateGateway stateGateway,
            IsolationPolicy isolationPolicy,
            SnapshotEnvelopeCodec snapshotCodec,
            Clock clock,
            Consumer<String> failureReporter) {
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.stateGateway = Objects.requireNonNull(stateGateway, "stateGateway");
        this.isolationPolicy = Objects.requireNonNull(isolationPolicy, "isolationPolicy");
        this.snapshotCodec = Objects.requireNonNull(snapshotCodec, "snapshotCodec");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
    }

    public synchronized AdmissionResult prepare(AdmissionRequest request) {
        Objects.requireNonNull(request, "request");
        Instant now = clock.instant();
        Optional<AuthenticatedSession> authenticationEvidence =
                authentication.current(request.playerId(), now);
        if (authenticationEvidence.isEmpty()
                || !authenticationEvidence.orElseThrow().connectionId().equals(request.connectionId())) {
            return AdmissionResult.rejected(
                    "AUTHENTICATION_REQUIRED",
                    "Conclui primeiro a autenticação da tua sessão antes de entrares num minijogo.");
        }
        if (!stateGateway.supports(request.game(), isolationPolicy)) {
            return AdmissionResult.rejected(
                    "ISOLATION_UNAVAILABLE",
                    "Este minijogo está fechado porque o isolamento da progressão ainda não está completo.");
        }
        Optional<PlayerSession> existing = sessions.findByPlayer(request.playerId());
        if (existing.isPresent() && existing.orElseThrow().phase() != SessionPhase.CLOSED) {
            return AdmissionResult.rejected(
                    "SESSION_ALREADY_ACTIVE",
                    "Já tens uma sessão de minijogo ativa ou em recuperação.");
        }

        PlayerSession session = new PlayerSession(
                request.sessionId(), request.matchId(), request.playerId(), request.game(), request.requestedAt());
        boolean durableSnapshotExists = false;
        try {
            sessions.register(session);
            sessionRepository.save(session);
            transitionAndSave(
                    session,
                    OperationIds.derive(request.requestId(), "SNAPSHOT_BEGIN"),
                    SessionPhase.REQUESTED,
                    SessionPhase.SNAPSHOTTING,
                    now,
                    "ADMISSION_REQUESTED");

            UUID captureOperation = OperationIds.derive(request.requestId(), "SNAPSHOT_CAPTURE");
            PlayerStateSnapshot snapshot = stateGateway.capture(
                    request.snapshotId(),
                    captureOperation,
                    request.sessionId(),
                    request.matchId(),
                    request.playerId(),
                    request.connectionId(),
                    request.game(),
                    now);
            validateSnapshotIdentity(session, snapshot);
            if (snapshot.schemaVersion() != 2 || !request.snapshotId().equals(snapshot.snapshotId())
                    || !captureOperation.equals(snapshot.operationId())
                    || !request.connectionId().equals(snapshot.capturedConnectionId())
                    || !now.equals(snapshot.capturedAt())) {
                throw new IllegalStateException("Captured snapshot does not match the admission request");
            }
            byte[] envelope = snapshotCodec.encode(snapshot);
            SnapshotRecord snapshotRecord = new SnapshotRecord(snapshot, snapshotCodec.checksum(envelope));
            snapshots.create(snapshotRecord);
            durableSnapshotExists = true;
            session.bindSnapshot(snapshot.snapshotId());
            sessionRepository.save(session);
            transitionAndSave(
                    session,
                    OperationIds.derive(request.requestId(), "SNAPSHOT_COMMIT"),
                    SessionPhase.SNAPSHOTTING,
                    SessionPhase.SNAPSHOT_COMMITTED,
                    clock.instant(),
                    "SNAPSHOT_DURABLE");
            transitionAndSave(
                    session,
                    OperationIds.derive(request.requestId(), "PREPARATION_BEGIN"),
                    SessionPhase.SNAPSHOT_COMMITTED,
                    SessionPhase.PREPARING,
                    clock.instant(),
                    "TEMPORARY_STATE_BEGIN");

            UUID temporaryOperation = OperationIds.derive(request.requestId(), "TEMPORARY_STATE_APPLY");
            stateGateway.enterTemporaryState(temporaryOperation, snapshot);
            snapshots.transition(
                    snapshot.snapshotId(),
                    temporaryOperation,
                    SnapshotState.CAPTURED,
                    SnapshotState.TEMPORARY_APPLIED,
                    clock.instant(),
                    "TEMPORARY_STATE_APPLIED");
            return new AdmissionResult(
                    AdmissionStatus.PREPARED,
                    "PREPARED",
                    "Estado protegido. A preparar o minijogo…",
                    Optional.of(session));
        } catch (RuntimeException failure) {
            reportFailure(session, "PREPARATION_FAILED", failure);
            return failClosedAfterPreparationFailure(request, session, durableSnapshotExists, failure);
        }
    }

    /** Current live session state; closed sessions have already been released. */
    public synchronized Optional<PlayerSession> findSession(UUID sessionId) {
        return sessions.findById(Objects.requireNonNull(sessionId, "sessionId"));
    }

    public synchronized boolean activate(UUID sessionId, UUID operationId, Instant occurredAt) {
        PlayerSession session = requireSession(sessionId);
        boolean changed = session.transition(
                operationId,
                SessionPhase.PREPARING,
                SessionPhase.ACTIVE,
                occurredAt,
                "GAME_ACTIVE");
        sessionRepository.save(session);
        return changed;
    }

    public synchronized AdmissionResult finishAndRestore(
            UUID sessionId,
            UUID rootOperationId,
            String reasonCode) {
        PlayerSession session = requireSession(sessionId);
        rootOperationId = scopeOperationIdToSession(session, rootOperationId);
        try {
            if (session.phase() == SessionPhase.ACTIVE) {
                transitionAndSave(
                        session,
                        OperationIds.derive(rootOperationId, "FINISH"),
                        SessionPhase.ACTIVE,
                        SessionPhase.FINISHING,
                        clock.instant(),
                        reasonCode);
            }
            if (session.phase() != SessionPhase.FINISHING && session.phase() != SessionPhase.RESTORING) {
                throw new IllegalStateException("Only an active/finishing session can complete normally");
            }
            restore(session, rootOperationId, false, reasonCode);
            return new AdmissionResult(
                    AdmissionStatus.RECOVERED,
                    "RESTORED",
                    "O teu estado survival foi restaurado.",
                    Optional.of(session));
        } catch (com.ciaac.minecraft.minigames.isolation.WorldRecoveryPendingException pending) {
            return pendingWorldRecovery(session);
        } catch (RuntimeException failure) {
            reportFailure(session, "RESTORE_FAILED", failure);
            quarantine(session, rootOperationId, "RESTORE_FAILED");
            return new AdmissionResult(
                    AdmissionStatus.QUARANTINED,
                    "RESTORE_QUARANTINED",
                    "A recuperação precisa de revisão. A tua sessão ficou protegida e fechada.",
                    Optional.of(session));
        }
    }

    public synchronized AdmissionResult recover(UUID sessionId, UUID rootOperationId, String reasonCode) {
        PlayerSession session = requireSession(sessionId);
        rootOperationId = scopeOperationIdToSession(session, rootOperationId);
        try {
            if (session.snapshotId().isEmpty()
                    && (session.phase() == SessionPhase.REQUESTED
                            || session.phase() == SessionPhase.SNAPSHOTTING)) {
                transitionAndSave(
                        session,
                        OperationIds.derive(rootOperationId, "INCOMPLETE_PREPARATION_CLOSE"),
                        session.phase(),
                        SessionPhase.CLOSED,
                        clock.instant(),
                        "SNAPSHOT_NOT_COMMITTED");
                sessions.releaseClosed(session.sessionId());
                return new AdmissionResult(
                        AdmissionStatus.RECOVERED,
                        "INCOMPLETE_PREPARATION_CLOSED",
                        "A preparação interrompida foi fechada sem alterar o teu estado.",
                        Optional.of(session));
            }
            restore(session, rootOperationId, true, reasonCode);
            return new AdmissionResult(
                    AdmissionStatus.RECOVERED,
                    "RECOVERED",
                    "A recuperação da tua sessão terminou com segurança.",
                    Optional.of(session));
        } catch (com.ciaac.minecraft.minigames.isolation.WorldRecoveryPendingException pending) {
            return pendingWorldRecovery(session);
        } catch (RuntimeException failure) {
            reportFailure(session, "RECOVERY_FAILED", failure);
            quarantine(session, rootOperationId, "RECOVERY_FAILED");
            return new AdmissionResult(
                    AdmissionStatus.QUARANTINED,
                    "RECOVERY_QUARANTINED",
                    "A recuperação precisa de revisão. A tua sessão ficou protegida e fechada.",
                    Optional.of(session));
        }
    }

    private static AdmissionResult pendingWorldRecovery(PlayerSession session) {
        return new AdmissionResult(AdmissionStatus.REJECTED, "RECOVERY_PENDING",
                "O restauro do mundo está a terminar; o teu estado continua protegido.", Optional.of(session));
    }

    private AdmissionResult failClosedAfterPreparationFailure(
            AdmissionRequest request,
            PlayerSession session,
            boolean durableSnapshotExists,
            RuntimeException failure) {
        if (!durableSnapshotExists) {
            try {
                SessionPhase phase = session.phase();
                if (phase == SessionPhase.REQUESTED || phase == SessionPhase.SNAPSHOTTING) {
                    session.transition(
                            OperationIds.derive(request.requestId(), "SAFE_PREPARATION_CLOSE"),
                            phase,
                            SessionPhase.CLOSED,
                            clock.instant(),
                            "SNAPSHOT_NOT_COMMITTED");
                    sessionRepository.save(session);
                    sessions.releaseClosed(session.sessionId());
                }
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            return new AdmissionResult(
                    AdmissionStatus.FAILED_CLOSED,
                    "PREPARATION_FAILED",
                    "Não foi possível preparar o minijogo; não alterámos o teu estado.",
                    Optional.of(session));
        }
        AdmissionResult recovered = recover(
                session.sessionId(),
                OperationIds.derive(request.requestId(), "PREPARATION_RECOVERY"),
                "PREPARATION_FAILED");
        return recovered.status() == AdmissionStatus.RECOVERED
                ? new AdmissionResult(
                        AdmissionStatus.FAILED_CLOSED,
                        "PREPARATION_RECOVERED",
                        "A preparação falhou, mas o teu estado foi recuperado com segurança.",
                        Optional.of(session))
                : recovered;
    }

    private void restore(
            PlayerSession session,
            UUID rootOperationId,
            boolean recovery,
            String reasonCode) {
        SessionPhase initialPhase = session.phase();
        if (recovery && session.phase() != SessionPhase.RECOVERING
                && session.phase() != SessionPhase.RESTORING) {
            SessionPhase current = session.phase();
            if (current.terminal()) {
                throw new IllegalStateException("A terminal session cannot enter recovery");
            }
            transitionAndSave(
                    session,
                    OperationIds.derive(rootOperationId, "RECOVERY_BEGIN"),
                    current,
                    SessionPhase.RECOVERING,
                    clock.instant(),
                    reasonCode);
        }
        if (!recovery && session.phase() == SessionPhase.FINISHING) {
            transitionAndSave(
                    session,
                    OperationIds.derive(rootOperationId, "RESTORE_BEGIN"),
                    SessionPhase.FINISHING,
                    SessionPhase.RESTORING,
                    clock.instant(),
                    reasonCode);
        }
        UUID snapshotId = session.snapshotId()
                .orElseThrow(() -> new IllegalStateException("Session has no durable snapshot"));
        SnapshotRecord snapshotRecord = snapshots.find(snapshotId)
                .orElseThrow(() -> new IllegalStateException("Durable snapshot is missing"));
        validateSnapshotIdentity(session, snapshotRecord.snapshot());
        SnapshotState state = snapshotRecord.state();
        if (state == SnapshotState.RESTORED && initialPhase != SessionPhase.RESTORING
                && initialPhase != SessionPhase.RECOVERING) {
            throw new IllegalStateException("A restored snapshot requires an already-restoring session");
        }
        if (state == SnapshotState.CAPTURED || state == SnapshotState.TEMPORARY_APPLIED) {
            snapshots.transition(
                    snapshotId,
                    OperationIds.derive(rootOperationId, "SNAPSHOT_RESTORE_BEGIN"),
                    state,
                    SnapshotState.RESTORING,
                    clock.instant(),
                    reasonCode);
        } else if (state != SnapshotState.RESTORING && state != SnapshotState.RESTORED) {
            throw new IllegalStateException("Snapshot is not recoverable from state " + state);
        }

        if (state != SnapshotState.RESTORED) {
            stateGateway.purgeTemporaryState(
                    OperationIds.derive(rootOperationId, "TEMPORARY_PURGE"), snapshotRecord.snapshot());
            stateGateway.restore(
                    OperationIds.derive(rootOperationId, "SNAPSHOT_RESTORE_APPLY"), snapshotRecord.snapshot());
            snapshots.transition(
                    snapshotId,
                    OperationIds.derive(rootOperationId, "SNAPSHOT_RESTORE_COMMIT"),
                    SnapshotState.RESTORING,
                    SnapshotState.RESTORED,
                    clock.instant(),
                    "RESTORE_COMMITTED");
        }

        if (session.phase() == SessionPhase.RECOVERING) {
            transitionAndSave(
                    session,
                    OperationIds.derive(rootOperationId, "SESSION_RESTORING"),
                    SessionPhase.RECOVERING,
                    SessionPhase.RESTORING,
                    clock.instant(),
                    reasonCode);
        }
        transitionAndSave(
                session,
                OperationIds.derive(rootOperationId, "SESSION_CLOSED"),
                SessionPhase.RESTORING,
                SessionPhase.CLOSED,
                clock.instant(),
                "RESTORE_COMMITTED");
        sessions.releaseClosed(session.sessionId());
    }

    private static void validateSnapshotIdentity(PlayerSession session, PlayerStateSnapshot snapshot) {
        if (!snapshot.sessionId().equals(session.sessionId())
                || !snapshot.matchId().equals(session.matchId())
                || !snapshot.playerId().equals(session.playerId())
                || snapshot.game() != session.game()) {
            throw new IllegalStateException("Snapshot does not belong to its session");
        }
    }

    /**
     * Caller roots may identify a match-wide event shared by several players.
     * Scope them before deriving any child operation so provider journals see
     * distinct operations for distinct durable sessions, while retries for the
     * same session remain stable.
     */
    private static UUID scopeOperationIdToSession(PlayerSession session, UUID rootOperationId) {
        return OperationIds.derive(
                Objects.requireNonNull(rootOperationId, "rootOperationId"),
                "SESSION_" + session.sessionId());
    }

    private void reportFailure(PlayerSession session, String code, RuntimeException failure) {
        // Exception messages may contain provider state or credentials. Report only
        // correlation, exception types and bounded source locations.
        StringBuilder diagnostic = new StringBuilder("Falha de isolamento [")
                .append(code).append("] sessão=").append(session.sessionId());
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 4; depth++, cause = cause.getCause()) {
            diagnostic.append("; tipo=").append(cause.getClass().getName());
            StackTraceElement[] frames = cause.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 4); i++) {
                diagnostic.append("; origem=").append(frames[i].getClassName())
                        .append('.').append(frames[i].getMethodName())
                        .append(':').append(frames[i].getLineNumber());
            }
        }
        try { failureReporter.accept(diagnostic.toString()); }
        catch (RuntimeException ignored) { /* Diagnostics cannot interrupt safe closure. */ }
    }

    private void quarantine(PlayerSession session, UUID rootOperationId, String reasonCode) {
        try {
            session.snapshotId().flatMap(snapshots::find).ifPresent(record -> {
                if (!record.state().terminal()) {
                    snapshots.transition(
                            record.snapshot().snapshotId(),
                            OperationIds.derive(rootOperationId, "SNAPSHOT_QUARANTINE"),
                            record.state(),
                            SnapshotState.QUARANTINED,
                            clock.instant(),
                            reasonCode);
                }
            });
        } catch (RuntimeException ignored) {
            // The session transition below still attempts to preserve a closed marker.
        }
        try {
            SessionPhase current = session.phase();
            if (current != SessionPhase.QUARANTINED && current != SessionPhase.CLOSED) {
                if (current != SessionPhase.RECOVERING && current != SessionPhase.RESTORING) {
                    session.transition(
                            OperationIds.derive(rootOperationId, "SESSION_RECOVERY_FOR_QUARANTINE"),
                            current,
                            SessionPhase.RECOVERING,
                            clock.instant(),
                            reasonCode);
                    current = SessionPhase.RECOVERING;
                }
                session.transition(
                        OperationIds.derive(rootOperationId, "SESSION_QUARANTINE"),
                        current,
                        SessionPhase.QUARANTINED,
                        clock.instant(),
                        reasonCode);
                sessionRepository.save(session);
            }
        } catch (RuntimeException ignored) {
            // Caller reports quarantine even if persistence is unavailable; admission remains closed.
        }
    }

    private void transitionAndSave(
            PlayerSession session,
            UUID operationId,
            SessionPhase expected,
            SessionPhase target,
            Instant occurredAt,
            String reasonCode) {
        session.transition(operationId, expected, target, occurredAt, reasonCode);
        sessionRepository.save(session);
    }

    private PlayerSession requireSession(UUID sessionId) {
        return sessions.findById(Objects.requireNonNull(sessionId, "sessionId"))
                .orElseThrow(() -> new IllegalArgumentException("Unknown active session " + sessionId));
    }
}
