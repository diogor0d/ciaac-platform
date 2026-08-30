package com.ciaac.minecraft.minigames.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.ciaac.minecraft.minigames.core.GameKey;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NativeDisplayConfigLoaderTest {
    @Test void loaderDerivesCanonicalPortugueseJoinCommand() {
        DisplayConfigLoadResult result = new NativeDisplayConfigLoader().load(Map.of(
                "enabled", true,
                "entries", List.of(Map.of(
                        "id", "arena-main", "game", "arena",
                        "world-id", "00000000-0000-0000-0000-000000000001", "world-name", "spawn",
                        "x", 1, "y", 80, "z", 2))));
        assertTrue(result.config().enabled());
        assertEquals("/coliseu entrar", result.config().entries().getFirst().joinCommand());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test void malformedEntryIsClosedAndReported() {
        DisplayConfigLoadResult result = new NativeDisplayConfigLoader().load(Map.of(
                "enabled", true,
                "entries", List.of(Map.of("id", "broken", "game", "not-a-game"))));
        assertTrue(result.config().entries().isEmpty());
        assertFalse(result.diagnostics().isEmpty());
    }
}
