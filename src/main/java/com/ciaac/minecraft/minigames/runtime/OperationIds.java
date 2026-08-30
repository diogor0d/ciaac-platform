package com.ciaac.minecraft.minigames.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Stable child operation identifiers make crash retries deterministic. */
public final class OperationIds {
    private OperationIds() {}

    public static UUID derive(UUID rootOperationId, String step) {
        Objects.requireNonNull(rootOperationId, "rootOperationId");
        String normalizedStep = MachineCode.normalize(step, "step");
        return UUID.nameUUIDFromBytes(
                (rootOperationId + ":" + normalizedStep).getBytes(StandardCharsets.UTF_8));
    }
}
