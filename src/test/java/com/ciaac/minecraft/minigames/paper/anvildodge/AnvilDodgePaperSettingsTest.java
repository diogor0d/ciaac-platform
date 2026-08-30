package com.ciaac.minecraft.minigames.paper.anvildodge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.ciaac.minecraft.minigames.anvildodge.AnvilDodgeConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class AnvilDodgePaperSettingsTest {
    @Test void disabledSettingsRemainClosedWithoutAWorld() {
        var settings = AnvilDodgePaperSettings.disabled(new AnvilDodgeConfig(1, 4, 3, Duration.ofSeconds(1), Duration.ofSeconds(3), 7, "v1"), "anvil-floor");
        assertFalse(settings.enabled());
    }
    @Test void enabledSettingsRejectMissingWorldCoordinates() {
        var config = new AnvilDodgeConfig(1, 4, 3, Duration.ofSeconds(1), Duration.ofSeconds(3), 7, "v1");
        assertThrows(NullPointerException.class, () -> new AnvilDodgePaperSettings(true, config, null, "anvil-floor", null, null, 4, 4));
    }
}
