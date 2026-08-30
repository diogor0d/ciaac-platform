package com.ciaac.minecraft.minigames.paper.configuration;

import com.ciaac.minecraft.minigames.configuration.ConfigValues;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResolvedValuesTest {
    @Test
    void boundedTypedGettersKeepInvalidValuesClosed() {
        ResolvedValues values = new ResolvedValues(new ConfigValues(Map.of(
                "flag", true,
                "count", 4,
                "seconds", 2,
                "materials", List.of("STICK"),
                "names", List.of("lane-a", "lane-b"))), "modules.test");

        assertTrue(values.bool("flag").orElseThrow());
        assertEquals(4, values.requiredInteger("count", 1, 8));
        assertEquals(Duration.ofSeconds(2), values.requiredDurationSeconds("seconds", 1, 10));
        assertEquals(List.of(Material.STICK), values.materials("materials", 4));
        assertEquals(List.of("lane-a", "lane-b"), values.requiredStringList("names", 4, 32));
        assertTrue(values.diagnostics().isEmpty());

        assertTrue(values.integer("count", 5, 8).isEmpty());
        assertTrue(values.materials("materials", 0).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> values.requiredString("missing", 32));
    }

    @Test
    void placeholdersAndUnknownMaterialsProduceBoundedPortugueseDiagnostics() {
        ResolvedValues values = new ResolvedValues(new ConfigValues(Map.of(
                "marker", "__SET_ME__",
                "materials", List.of("NOT_A_MATERIAL"))), "modules.test");

        assertTrue(values.string("marker", 64).isEmpty());
        assertTrue(values.materials("materials", 4).isEmpty());
        assertTrue(values.diagnostics().stream().anyMatch(d -> d.code().equals("PLACEHOLDER_UNRESOLVED")));
        assertTrue(values.diagnostics().stream().anyMatch(d -> d.code().equals("MATERIAL_UNKNOWN")));
        assertTrue(values.diagnostics().stream().allMatch(d -> d.message().matches(".*[A-Za-zÀ-ÿ].*")));
    }
}
