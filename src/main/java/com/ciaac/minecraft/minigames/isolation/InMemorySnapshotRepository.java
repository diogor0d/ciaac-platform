package com.ciaac.minecraft.minigames.isolation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Deterministic development implementation; never a production recovery store. */
public final class InMemorySnapshotRepository implements SnapshotRepository {
    private final Map<UUID, SnapshotRecord> records = new LinkedHashMap<>();

    @Override
    public synchronized boolean create(SnapshotRecord record) {
        Objects.requireNonNull(record, "record");
        UUID id = record.snapshot().snapshotId();
        SnapshotRecord previous = records.get(id);
        if (previous != null) {
            if (!previous.checksumSha256().equals(record.checksumSha256())) {
                throw new IllegalStateException("Conflicting snapshot replay " + id);
            }
            return false;
        }
        records.put(id, record);
        return true;
    }

    @Override
    public synchronized boolean transition(
            UUID snapshotId,
            UUID operationId,
            SnapshotState expected,
            SnapshotState target,
            Instant occurredAt,
            String reasonCode) {
        SnapshotRecord record = records.get(Objects.requireNonNull(snapshotId, "snapshotId"));
        if (record == null) {
            throw new IllegalArgumentException("Unknown snapshot " + snapshotId);
        }
        return record.transition(operationId, expected, target, occurredAt, reasonCode);
    }

    @Override
    public synchronized Optional<SnapshotRecord> find(UUID snapshotId) {
        return Optional.ofNullable(records.get(Objects.requireNonNull(snapshotId, "snapshotId")));
    }

    @Override
    public synchronized List<SnapshotRecord> nonTerminal() {
        return records.values().stream().filter(record -> !record.state().terminal()).toList();
    }
}
