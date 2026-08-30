package com.ciaac.minecraft.minigames.runtime;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One authenticated player's durable, replay-safe participation state.
 *
 * <p>The object is deliberately independent from Bukkit so persisted recovery
 * can be reasoned about without a live player entity.</p>
 */
public final class PlayerSession {
    private final UUID sessionId;
    private final UUID matchId;
    private final UUID playerId;
    private final GameKey game;
    private final Instant createdAt;
    private final Map<UUID, SessionTransition> transitionsByOperation = new LinkedHashMap<>();
    private SessionPhase phase;
    private Instant updatedAt;
    private UUID snapshotId;

    public PlayerSession(
            UUID sessionId,
            UUID matchId,
            UUID playerId,
            GameKey game,
            Instant createdAt) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.matchId = Objects.requireNonNull(matchId, "matchId");
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.game = Objects.requireNonNull(game, "game");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = createdAt;
        this.phase = SessionPhase.REQUESTED;
    }

    public synchronized boolean transition(
            UUID operationId,
            SessionPhase expected,
            SessionPhase target,
            Instant occurredAt,
            String reasonCode) {
        SessionTransition candidate = new SessionTransition(
                operationId, expected, target, occurredAt, reasonCode);
        SessionTransition previous = transitionsByOperation.get(operationId);
        if (previous != null) {
            if (!previous.equals(candidate)) {
                throw new IllegalStateException("Conflicting replay for session operation " + operationId);
            }
            return false;
        }
        if (phase != expected) {
            throw new IllegalStateException("Expected session phase " + expected + " but was " + phase);
        }
        if (!allowed(expected, target)) {
            throw new IllegalStateException("Illegal session transition " + expected + " -> " + target);
        }
        if (occurredAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Session transition time cannot move backwards");
        }
        transitionsByOperation.put(operationId, candidate);
        phase = target;
        updatedAt = occurredAt;
        return true;
    }

    public synchronized void bindSnapshot(UUID value) {
        Objects.requireNonNull(value, "snapshotId");
        if (phase != SessionPhase.SNAPSHOTTING && phase != SessionPhase.SNAPSHOT_COMMITTED) {
            throw new IllegalStateException("Snapshot can only be bound during snapshot preparation");
        }
        if (snapshotId != null && !snapshotId.equals(value)) {
            throw new IllegalStateException("A session cannot replace its bound snapshot");
        }
        snapshotId = value;
    }

    private static boolean allowed(SessionPhase from, SessionPhase to) {
        if (to == SessionPhase.RECOVERING) {
            return !from.terminal() && from != SessionPhase.RESTORING;
        }
        if (to == SessionPhase.QUARANTINED) {
            return from == SessionPhase.RECOVERING || from == SessionPhase.RESTORING;
        }
        return switch (from) {
            case REQUESTED -> to == SessionPhase.SNAPSHOTTING || to == SessionPhase.CLOSED;
            case SNAPSHOTTING -> to == SessionPhase.SNAPSHOT_COMMITTED || to == SessionPhase.CLOSED;
            case SNAPSHOT_COMMITTED -> to == SessionPhase.PREPARING;
            case PREPARING -> to == SessionPhase.ACTIVE;
            case ACTIVE -> to == SessionPhase.FINISHING;
            case FINISHING -> to == SessionPhase.RESTORING;
            case RECOVERING -> to == SessionPhase.RESTORING;
            case RESTORING -> to == SessionPhase.CLOSED;
            case QUARANTINED, CLOSED -> false;
        };
    }

    public UUID sessionId() { return sessionId; }
    public UUID matchId() { return matchId; }
    public UUID playerId() { return playerId; }
    public GameKey game() { return game; }
    public Instant createdAt() { return createdAt; }
    public synchronized Instant updatedAt() { return updatedAt; }
    public synchronized SessionPhase phase() { return phase; }
    public synchronized Optional<UUID> snapshotId() { return Optional.ofNullable(snapshotId); }
    public synchronized List<SessionTransition> transitions() {
        return List.copyOf(new ArrayList<>(transitionsByOperation.values()));
    }
}
