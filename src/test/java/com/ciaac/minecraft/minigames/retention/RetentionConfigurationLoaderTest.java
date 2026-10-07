package com.ciaac.minecraft.minigames.retention;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class RetentionConfigurationLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void defaultDisabledPassportConfigurationLoadsWithApprovedStartDate() throws IOException {
        RetentionConfiguration configuration = load(defaultConfiguration());

        assertFalse(configuration.enabled());
        assertFalse(configuration.placeholdersEnabled());
        assertEquals(LisbonSeasonCalendar.ANCHOR, configuration.seasonAnchor());
    }

    @Test
    void generatedUnquotedYamlDateRemainsCompatible() throws IOException {
        String legacyDefault = defaultConfiguration().replace(
                "anchor: '2026-09-15'", "anchor: 2026-09-15");

        RetentionConfiguration configuration = load(legacyDefault);

        assertFalse(configuration.enabled());
        assertEquals(LisbonSeasonCalendar.ANCHOR, configuration.seasonAnchor());
    }

    private RetentionConfiguration load(String yaml) throws IOException {
        Path file = temporaryDirectory.resolve("retention.yml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return new RetentionConfigurationLoader().load(file.toFile());
    }

    private static String defaultConfiguration() throws IOException {
        InputStream stream = RetentionConfigurationLoaderTest.class.getClassLoader()
                .getResourceAsStream("retention.yml");
        if (stream == null) throw new IllegalStateException("retention.yml test resource is missing");
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
