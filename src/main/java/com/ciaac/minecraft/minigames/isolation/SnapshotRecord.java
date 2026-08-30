package com.ciaac.minecraft.minigames.isolation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Mutable state machine around an immutable player snapshot. */
public final class SnapshotRecord {
    private final PlayerStateSnapshot snapshot;
    private final String checksumSha256;
    private final Map<UUID, SnapshotTransition> transitionsByOperation = new LinkedHashMap<>();
    private SnapshotState state;
    private Instant updatedAt;

    public SnapshotRecord(PlayerStateSnapshot snapshot, String checksumSha256) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.checksumSha256 = normalizeChecksum(checksumSha256);
        this.state = SnapshotState.CAPTURED;
        this.updatedAt = snapshot.capturedAt();
    }

    public synchronized boolean transition(
            UUID operationId,
            SnapshotState expected,
            SnapshotState target,
            Instant occurredAt,
            String reasonCode) {
        SnapshotTransition candidate = new SnapshotTransition(
                operationId, expected, target, occurredAt, reasonCode);
        SnapshotTransition previous = transitionsByOperation.get(operationId);
        if (previous != null) {
            if (!previous.equals(candidate)) {
                throw new IllegalStateException("Conflicting snapshot operation replay " + operationId);
            }
            return false;
        }
        if (state != expected) {
            throw new IllegalStateException("Expected snapshot state " + expected + " but was " + state);
        }
        if (!allowed(expected, target)) {
            throw new IllegalStateException("Illegal snapshot transition " + expected + " -> " + target);
        }
        if (occurredAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Snapshot transition time cannot move backwards");
        }
        transitionsByOperation.put(operationId, candidate);
        state = target;
        updatedAt = occurredAt;
        return true;
    }

    private static boolean allowed(SnapshotState from, SnapshotState to) {
        if (to == SnapshotState.QUARANTINED) {
            return !from.terminal();
        }
        return switch (from) {
            case CAPTURED -> to == SnapshotState.TEMPORARY_APPLIED || to == SnapshotState.RESTORING;
            case TEMPORARY_APPLIED -> to == SnapshotState.RESTORING;
            case RESTORING -> to == SnapshotState.RESTORED;
            case RESTORED, QUARANTINED -> false;
        };
    }

    private static String normalizeChecksum(String value) {
        String normalized = Objects.requireNonNull(value, "checksumSha256").toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("checksumSha256 must be a lowercase SHA-256 value");
        }
        return normalized;
    }

    public PlayerStateSnapshot snapshot() { return snapshot; }
    public String checksumSha256() { return checksumSha256; }
    public synchronized SnapshotState state() { return state; }
    public synchronized Instant updatedAt() { return updatedAt; }
    public synchronized List<SnapshotTransition> transitions() {
        return List.copyOf(new ArrayList<>(transitionsByOperation.values()));
    }
}
