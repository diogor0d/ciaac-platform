package com.ciaac.minecraft.minigames.paper.configuration;

import com.ciaac.minecraft.minigames.runtime.MachineCode;
import java.util.Objects;

/** A bounded, player/operator-safe configuration resolution diagnostic. */
public record ResolutionDiagnostic(String code, String path, String message) {
    public ResolutionDiagnostic {
        code = MachineCode.normalize(code, "code");
        if (path == null || path.isBlank() || path.length() > 256) {
            throw new IllegalArgumentException("Diagnostic path is invalid");
        }
        if (message == null || message.isBlank() || message.length() > 500) {
            throw new IllegalArgumentException("Diagnostic message is invalid");
        }
        Objects.requireNonNull(path, "path");
    }
}
