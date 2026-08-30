package com.ciaac.minecraft.minigames.display;

import java.util.Objects;

public record DisplayDiagnostic(String entryId, String code, String messagePtPt) {
    public DisplayDiagnostic {
        entryId = Objects.requireNonNull(entryId, "entryId"); code = Objects.requireNonNull(code, "code"); messagePtPt = Objects.requireNonNull(messagePtPt, "messagePtPt");
        if (!code.matches("[A-Z0-9_]{2,64}") || messagePtPt.isBlank() || messagePtPt.length() > 240) throw new IllegalArgumentException("diagnostic is invalid");
    }
}
