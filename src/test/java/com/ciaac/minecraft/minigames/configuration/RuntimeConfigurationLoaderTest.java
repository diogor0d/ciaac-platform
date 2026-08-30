package com.ciaac.minecraft.minigames.configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class RuntimeConfigurationLoaderTest {
    @Test
    void defaultTemplateKeepsEveryMandatorySafetyInvariantEnabled() {
        RuntimeConfiguration configuration = new RuntimeConfigurationLoader().load(defaultConfiguration());

        assertTrue(configuration.globalProblems().stream()
                .noneMatch(problem -> problem.code().equals("SAFETY_INVARIANT_DISABLED")));
    }

    @Test
    void anOperatorCannotSilentlyDisableARequiredIsolationFacet() {
        YamlConfiguration source = defaultConfiguration();
        source.set("progress-isolation.inventory", false);

        RuntimeConfiguration configuration = new RuntimeConfigurationLoader().load(source);

        assertFalse(configuration.canAttemptAdmission());
        assertTrue(configuration.globalProblems().stream().anyMatch(problem ->
                problem.code().equals("SAFETY_INVARIANT_DISABLED")
                        && problem.path().equals("progress-isolation.inventory")));
    }

    private static YamlConfiguration defaultConfiguration() {
        InputStream stream = RuntimeConfigurationLoaderTest.class.getClassLoader()
                .getResourceAsStream("config.yml");
        if (stream == null) throw new IllegalStateException("config.yml test resource is missing");
        try (stream; InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Could not read config.yml test resource", failure);
        }
    }
}
