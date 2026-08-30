package com.ciaac.minecraft.minigames.paper.arena;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ciaac.minecraft.minigames.arena.ArenaFormatPolicy;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ColiseumSettingsTest {
    @Test
    void disabledSettingsRequireNoLiveGeometry() {
        ColiseumSettings settings = ColiseumSettings.disabled();
        assertDoesNotThrow(() -> settings.formatPolicy().supports(
                com.ciaac.minecraft.minigames.arena.ArenaFormat.standard(1)));
        assertFalse(settings.enabled());
    }

    @Test
    void enabledSettingsRejectMissingOrMismatchedSpawnWorld() {
        UUID worldId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new ColiseumSettings(
                true, Optional.of(worldId), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                ArenaFormatPolicy.defaultPolicy(), Duration.ofSeconds(30), Duration.ofSeconds(30), false, Set.of()));
    }
}
