package com.ciaac.minecraft.minigames.runtime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Idempotency-bound evidence for one session transition. */
public record SessionTransition(
        UUID operationId,
        SessionPhase from,
        SessionPhase to,
        Instant occurredAt,
        String reasonCode) {

    public SessionTransition {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(occurredAt, "occurredAt");
        reasonCode = MachineCode.normalize(reasonCode, "reasonCode");
    }
}
