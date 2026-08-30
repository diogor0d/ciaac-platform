package com.ciaac.minecraft.minigames.paper.recovery;

import com.ciaac.minecraft.minigames.persistence.AuditEvent;
import com.ciaac.minecraft.minigames.persistence.AuditRepository;
import com.ciaac.minecraft.minigames.persistence.SessionRepository;
import com.ciaac.minecraft.minigames.runtime.AdmissionResult;
import com.ciaac.minecraft.minigames.runtime.AdmissionStatus;
import com.ciaac.minecraft.minigames.runtime.OperationIds;
import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import com.ciaac.minecraft.minigames.runtime.SessionCoordinator;
import com.ciaac.minecraft.minigames.runtime.SessionPhase;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import com.ciaac.minecraft.minigames.runtime.SessionViolation;
import com.ciaac.minecraft.minigames.runtime.SessionViolationHandler;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.entity.Player;

/** Loads blocking sessions and restores them only when the player is safely online. */
public final class SessionRecoveryService implements SessionViolationHandler {
    private final SessionRepository repository;
    private final SessionRegistry registry;
    private final SessionCoordinator coordinator;
    private final AuditRepository audit;
    private final Clock clock;
    private final Consumer<PlayerSession> stopGame;

    public SessionRecoveryService(
            SessionRepository repository,
            SessionRegistry registry,
            SessionCoordinator coordinator,
            AuditRepository audit,
            Clock clock,
            Consumer<PlayerSession> stopGame) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.stopGame = Objects.requireNonNull(stopGame, "stopGame");
    }

    /** Must run during bootstrap before command admission is exposed. */
    public synchronized List<PlayerSession> loadBlockingSessions() {
        List<PlayerSession> loaded = repository.nonTerminal();
        for (PlayerSession session : loaded) registry.register(session);
        return loaded;
    }

    /** Called only after the connection-bound nLogin authentication event. */
    public synchronized AdmissionResult onAuthenticated(Player player) {
        Objects.requireNonNull(player, "player");
        PlayerSession session = registry.findByPlayer(player.getUniqueId()).orElse(null);
        if (session == null) {
            return AdmissionResult.rejected("NO_RECOVERY_PENDING", "Não tens nenhuma recuperação pendente.");
        }
        if (session.phase() == SessionPhase.QUARANTINED) {
            player.sendMessage("§cA tua sessão de minijogo está em quarentena e precisa de revisão por um administrador.");
            return new AdmissionResult(
                    AdmissionStatus.QUARANTINED,
                    "RECOVERY_QUARANTINED",
                    "A tua sessão continua protegida até ser revista.",
                    java.util.Optional.of(session));
        }
        stopGame.accept(session);
        UUID root = operation(session, "AUTHENTICATED_RECOVERY");
        AdmissionResult result = coordinator.recover(session.sessionId(), root, "RESTART_OR_RECONNECT");
        appendAudit(session, root, "AUTHENTICATED_RECOVERY", result.code(), session.phase().name());
        player.sendMessage(result.messagePtPt());
        return result;
    }

    @Override
    public synchronized void onViolation(Player player, PlayerSession session, SessionViolation violation) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(violation, "violation");
        UUID root = operation(session, "VIOLATION_" + violation.name());
        appendAudit(session, root, "SESSION_VIOLATION", "OBSERVED", violation.name());
        if (violation == SessionViolation.DISCONNECT || violation == SessionViolation.CONTROLLED_DEATH
                || session.phase() == SessionPhase.QUARANTINED || session.phase() == SessionPhase.CLOSED) {
            return;
        }
        stopGame.accept(session);
        AdmissionResult result = coordinator.recover(session.sessionId(), root, violation.name());
        appendAudit(session, root, "VIOLATION_RECOVERY", result.code(), violation.name());
        if (player.isOnline()) player.sendMessage(result.messagePtPt());
    }

    private void appendAudit(
            PlayerSession session, UUID operationId, String type, String outcome, String detail) {
        try {
            audit.append(new AuditEvent(
                    OperationIds.derive(operationId, "AUDIT_" + type),
                    operationId,
                    clock.instant(),
                    type,
                    "SESSION",
                    session.sessionId().toString(),
                    outcome,
                    detail));
        } catch (RuntimeException ignored) {
            // Recovery remains fail-closed even if secondary audit persistence fails.
        }
    }

    private static UUID operation(PlayerSession session, String purpose) {
        String seed = session.sessionId() + ":" + session.phase() + ":" + session.updatedAt() + ":" + purpose;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
