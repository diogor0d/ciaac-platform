package com.ciaac.minecraft.minigames.minecart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MinecartSpeedConfigurationLoaderTest {
    private final MinecartSpeedConfigurationLoader loader = new MinecartSpeedConfigurationLoader();

    @TempDir Path temporaryDirectory;

    @Test
    void loadsGlobalDefaultAndExactWorldOverride() throws Exception {
        UUID worldId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        YamlConfiguration yaml = yaml("""
                schema-version: 1
                enabled: true
                default-terminal-speed-blocks-per-second: 16.0
                worlds:
                  spawn:
                    uuid: 11111111-1111-1111-1111-111111111111
                    terminal-speed-blocks-per-second: 20.0
                """);

        var result = loader.load(yaml);

        assertTrue(result.accepted());
        assertEquals(16.0, result.configuration().speedFor("survival", UUID.randomUUID()).orElseThrow());
        assertEquals(20.0, result.configuration().speedFor("spawn", worldId).orElseThrow());
        assertTrue(result.configuration().speedFor("spawn", UUID.randomUUID()).isEmpty());
    }

    @Test
    void rejectsUnsupportedSchemaMalformedUuidAndOutOfRangeValues() throws Exception {
        assertFalse(loader.load(yaml("schema-version: 2\nenabled: false\n")).accepted());
        assertFalse(loader.load(yaml("""
                schema-version: 1
                enabled: true
                worlds:
                  spawn:
                    uuid: not-a-uuid
                    terminal-speed-blocks-per-second: 16.0
                """)).accepted());
        assertFalse(loader.load(yaml("""
                schema-version: 1
                enabled: true
                default-terminal-speed-blocks-per-second: 64.1
                worlds: {}
                """)).accepted());
        assertFalse(loader.load(yaml("""
                schema-version: 1
                enabled: true
                default-terminal-speed-blocks-per-second: NaN
                worlds: {}
                """)).accepted());
    }

    @Test
    void defaultsToDisabledAndSixteenBlocksPerSecond() throws Exception {
        var result = loader.load(yaml("schema-version: 1\nworlds: {}\n"));
        assertTrue(result.accepted());
        assertFalse(result.configuration().enabled());
        assertEquals(16.0, result.configuration().defaultBlocksPerSecond());
    }

    @Test
    void rejectsDuplicateWorldEntriesBeforeYamlCanCollapseThem() throws Exception {
        Path file = temporaryDirectory.resolve("minecarts.yml");
        Files.writeString(file, """
                schema-version: 1
                enabled: true
                default-terminal-speed-blocks-per-second: 16.0
                worlds:
                  spawn:
                    uuid: 11111111-1111-1111-1111-111111111111
                    terminal-speed-blocks-per-second: 20.0
                  spawn:
                    uuid: 22222222-2222-2222-2222-222222222222
                    terminal-speed-blocks-per-second: 24.0
                """);

        var result = loader.load(file.toFile());

        assertFalse(result.accepted());
        assertEquals("minecarts.yml contém YAML inválido ou chaves repetidas.", result.diagnosticPtPt());
    }

    private static YamlConfiguration yaml(String source) throws Exception {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.loadFromString(source);
        return configuration;
    }
}
