package com.ciaac.minecraft.minigames.persistence;

import com.ciaac.minecraft.minigames.runtime.MachineCode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Bounded local audit evidence without player-controlled prose or secrets. */
public record AuditEvent(
        UUID eventId,
        UUID operationId,
        Instant occurredAt,
        String eventType,
        String subjectType,
        String subjectId,
        String outcomeCode,
        String detailCode) {

    public AuditEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        eventType = MachineCode.normalize(eventType, "eventType");
        subjectType = MachineCode.normalize(subjectType, "subjectType");
        outcomeCode = MachineCode.normalize(outcomeCode, "outcomeCode");
        detailCode = MachineCode.normalize(detailCode, "detailCode");
        subjectId = Objects.requireNonNull(subjectId, "subjectId").trim();
        if (!subjectId.matches("[A-Za-z0-9_.:-]{1,128}")) {
            throw new IllegalArgumentException("subjectId must be a bounded opaque identifier");
        }
    }
}
