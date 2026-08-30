package com.ciaac.minecraft.minigames.minecart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MinecartSpeedOverridesTest {
    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final MinecartSpeedConfiguration base = new MinecartSpeedConfiguration(1, true, 16, Map.of());

    @Test void overlaysWorldAndDefaultWithoutMutatingPreviousValue() {
        MinecartSpeedOverrides original = MinecartSpeedOverrides.empty();
        MinecartSpeedOverrides changed = original.withDefault(24).withWorld("spawn", ID, 32);
        assertFalse(original.active());
        assertEquals(24, changed.resolve(base, "survival", ID).blocksPerSecond().orElseThrow());
        assertEquals(32, changed.resolve(base, "spawn", ID).blocksPerSecond().orElseThrow());
        assertTrue(changed.resolve(base, "spawn", UUID.randomUUID()).identityMismatch());
    }

    @Test void rejectsInvalidBoundsAndInvalidWorldNames() {
        assertThrows(IllegalArgumentException.class, () -> MinecartSpeedOverrides.empty().withDefault(0.09));
        assertThrows(IllegalArgumentException.class, () -> MinecartSpeedOverrides.empty().withDefault(64.01));
        assertThrows(IllegalArgumentException.class, () -> MinecartSpeedOverrides.empty().withWorld("\n", ID, 1));
    }

    @Test void clearingWorldIsImmutableAndFallsBackToDefaultThenBase() {
        MinecartSpeedOverrides changed = MinecartSpeedOverrides.empty().withDefault(24).withWorld("spawn", ID, 32);
        MinecartSpeedOverrides cleared = changed.withoutWorld("spawn");
        assertEquals(24, cleared.resolve(base, "spawn", ID).blocksPerSecond().orElseThrow());
        assertEquals(32, changed.resolve(base, "spawn", ID).blocksPerSecond().orElseThrow());
        assertEquals(16, MinecartSpeedOverrides.empty().resolve(base, "survival", ID).blocksPerSecond().orElseThrow());
    }

    @Test void disabledBaseOnlyActivatesWorldsCoveredByTemporaryOverlay() {
        MinecartSpeedConfiguration disabled = MinecartSpeedConfiguration.disabled();
        MinecartSpeedOverrides overrides = MinecartSpeedOverrides.empty().withWorld("spawn", ID, 20);

        assertEquals(20, overrides.resolve(disabled, "spawn", ID).blocksPerSecond().orElseThrow());
        assertTrue(overrides.resolve(disabled, "survival", UUID.randomUUID()).blocksPerSecond().isEmpty());
    }
}
