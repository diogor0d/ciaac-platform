package com.ciaac.minecraft.minigames.core;

import java.util.Objects;

public record ModuleDefinition(
        GameKey key,
        ImplementationStage stage,
        boolean admissionOpen,
        String statusMessagePtPt) {

    public ModuleDefinition {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(stage, "stage");
        if (statusMessagePtPt == null || statusMessagePtPt.isBlank()) {
            throw new IllegalArgumentException("statusMessagePtPt must not be blank");
        }
        if (admissionOpen && stage != ImplementationStage.RUNTIME_VALIDATED) {
            throw new IllegalArgumentException(
                    "Admission requires a runtime-validated module stage");
        }
    }
}
