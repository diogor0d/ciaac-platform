package com.ciaac.minecraft.minigames.isolation;

import com.ciaac.minecraft.minigames.runtime.MachineCode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SnapshotTransition(
        UUID operationId,
        SnapshotState from,
        SnapshotState to,
        Instant occurredAt,
        String reasonCode) {

    public SnapshotTransition {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(occurredAt, "occurredAt");
        reasonCode = MachineCode.normalize(reasonCode, "reasonCode");
    }
}
