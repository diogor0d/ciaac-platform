package com.ciaac.minecraft.minigames.display;

import java.util.List;

public record DisplayConfigLoadResult(NativeDisplayConfig config, List<DisplayDiagnostic> diagnostics) {
    public DisplayConfigLoadResult { config = java.util.Objects.requireNonNull(config); diagnostics = List.copyOf(java.util.Objects.requireNonNull(diagnostics)); }
}
