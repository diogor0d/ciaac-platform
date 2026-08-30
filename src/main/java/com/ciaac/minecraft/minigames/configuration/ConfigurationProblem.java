package com.ciaac.minecraft.minigames.configuration;

import com.ciaac.minecraft.minigames.runtime.MachineCode;
import java.util.Objects;

public record ConfigurationProblem(String code, String path, String message) {
    public ConfigurationProblem {
        code = MachineCode.normalize(code, "code");
        if (path == null || path.isBlank() || path.length() > 256) {
            throw new IllegalArgumentException("Configuration problem path is invalid");
        }
        if (message == null || message.isBlank() || message.length() > 500) {
            throw new IllegalArgumentException("Configuration problem message is invalid");
        }
        Objects.requireNonNull(path, "path");
    }
}
