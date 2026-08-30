package com.ciaac.minecraft.minigames.paper.configuration;

import com.ciaac.minecraft.minigames.configuration.RuntimeConfiguration;
import com.ciaac.minecraft.minigames.core.GameKey;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable output of {@link PaperConfigurationResolver}. */
public record ConfigurationResolutionResult(
        RuntimeConfiguration source,
        boolean globalAdmissionAllowed,
        Map<GameKey, ResolvedModuleConfiguration> modules,
        List<ResolutionDiagnostic> diagnostics) {

    public ConfigurationResolutionResult {
        source = Objects.requireNonNull(source, "source");
        EnumMap<GameKey, ResolvedModuleConfiguration> copy = new EnumMap<>(GameKey.class);
        copy.putAll(Objects.requireNonNull(modules, "modules"));
        if (copy.size() != GameKey.values().length) {
            throw new IllegalArgumentException("Every game needs a resolved module entry");
        }
        modules = Map.copyOf(copy);
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    public Optional<ResolvedModuleConfiguration> module(GameKey game) {
        return Optional.ofNullable(modules.get(Objects.requireNonNull(game, "game")));
    }

    public boolean canAttemptAdmission() {
        return globalAdmissionAllowed;
    }

    public boolean hasDiagnostics() {
        return !diagnostics.isEmpty();
    }
}
