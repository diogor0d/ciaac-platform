package com.ciaac.minecraft.minigames.configuration;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record RuntimeConfiguration(
        int schemaVersion,
        boolean globalAdmissionEnabled,
        String locale,
        Path sqlitePath,
        Path recoveryDirectory,
        AnnouncementConfiguration announcements,
        Map<GameKey, ModuleConfiguration> modules,
        List<ConfigurationProblem> globalProblems) {

    public RuntimeConfiguration {
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
        if (!"pt-PT".equals(locale)) throw new IllegalArgumentException("Only pt-PT is supported");
        Objects.requireNonNull(sqlitePath, "sqlitePath");
        Objects.requireNonNull(recoveryDirectory, "recoveryDirectory");
        Objects.requireNonNull(announcements, "announcements");
        EnumMap<GameKey, ModuleConfiguration> copy = new EnumMap<>(GameKey.class);
        copy.putAll(Objects.requireNonNull(modules, "modules"));
        if (copy.size() != GameKey.values().length) {
            throw new IllegalArgumentException("Every minigame must have a configuration entry");
        }
        modules = Map.copyOf(copy);
        globalProblems = List.copyOf(Objects.requireNonNull(globalProblems, "globalProblems"));
    }

    public boolean canAttemptAdmission() {
        return globalAdmissionEnabled && globalProblems.isEmpty();
    }
}
