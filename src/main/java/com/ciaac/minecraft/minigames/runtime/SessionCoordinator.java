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

    public SessionCoordinator(
            AuthenticationRegistry authentication,
            SessionRegistry sessions,
            SessionRepository sessionRepository,
            SnapshotRepository snapshots,
            PlayerStateGateway stateGateway,
            IsolationPolicy isolationPolicy,
            SnapshotEnvelopeCodec snapshotCodec,
            Clock clock) {
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.stateGateway = Objects.requireNonNull(stateGateway, "stateGateway");
        this.isolationPolicy = Objects.requireNonNull(isolationPolicy, "isolationPolicy");
        this.snapshotCodec = Objects.requireNonNull(snapshotCodec, "snapshotCodec");
        this.clock = Objects.requireNonNull(clock, "clock");
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
        if (!stateGateway.supports(isolationPolicy)) {
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
                    request.game(),
                    now);
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
            return failClosedAfterPreparationFailure(request, session, durableSnapshotExists, failure);
        }
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
            if (session.phase() != SessionPhase.FINISHING) {
                throw new IllegalStateException("Only an active/finishing session can complete normally");
            }
            restore(session, rootOperationId, false, reasonCode);
            return new AdmissionResult(
                    AdmissionStatus.RECOVERED,
                    "RESTORED",
                    "O teu estado survival foi restaurado.",
                    Optional.of(session));
        } catch (RuntimeException failure) {
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
        } catch (RuntimeException failure) {
            quarantine(session, rootOperationId, "RECOVERY_FAILED");
            return new AdmissionResult(
                    AdmissionStatus.QUARANTINED,
                    "RECOVERY_QUARANTINED",
                    "A recuperação precisa de revisão. A tua sessão ficou protegida e fechada.",
                    Optional.of(session));
        }
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
        if (recovery && session.phase() != SessionPhase.RECOVERING) {
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
        if (!recovery) {
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
        SnapshotState state = snapshotRecord.state();
        if (state == SnapshotState.CAPTURED || state == SnapshotState.TEMPORARY_APPLIED) {
            snapshots.transition(
                    snapshotId,
                    OperationIds.derive(rootOperationId, "SNAPSHOT_RESTORE_BEGIN"),
                    state,
                    SnapshotState.RESTORING,
                    clock.instant(),
                    reasonCode);
        } else if (state != SnapshotState.RESTORING) {
            throw new IllegalStateException("Snapshot is not recoverable from state " + state);
        }

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
