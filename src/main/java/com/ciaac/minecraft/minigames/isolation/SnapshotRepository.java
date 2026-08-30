package com.ciaac.minecraft.minigames.isolation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable repository contract; implementations must reject conflicting replays. */
public interface SnapshotRepository {
    boolean create(SnapshotRecord snapshot);

    boolean transition(
            UUID snapshotId,
            UUID operationId,
            SnapshotState expected,
            SnapshotState target,
            Instant occurredAt,
            String reasonCode);

    Optional<SnapshotRecord> find(UUID snapshotId);

    List<SnapshotRecord> nonTerminal();
}
