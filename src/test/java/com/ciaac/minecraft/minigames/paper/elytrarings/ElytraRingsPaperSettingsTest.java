package com.ciaac.minecraft.minigames.paper.elytrarings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.ciaac.minecraft.minigames.elytrarings.ElytraCourseRevision;
import com.ciaac.minecraft.minigames.elytrarings.ElytraRingsConfig;
import com.ciaac.minecraft.minigames.elytrarings.RingCheckpoint;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ElytraRingsPaperSettingsTest {
    @Test void dedicatedCourseDefaultsClosed() {
        var course = new ElytraCourseRevision("v1", "00000000-0000-0000-0000-000000000001", List.of(new RingCheckpoint(1, 0, 100, 0), new RingCheckpoint(2, 20, 100, 0)));
        var settings = ElytraRingsPaperSettings.disabled(ElytraRingsConfig.dedicatedWorld(Duration.ofMinutes(2), course), "elytra-course");
        assertFalse(settings.enabled());
    }

    @Test void paperBoundsRejectOversizedRocketsAndPreload() {
        var course = new ElytraCourseRevision("v1", "00000000-0000-0000-0000-000000000001",
                List.of(new RingCheckpoint(1, 0, 100, 0), new RingCheckpoint(2, 16, 100, 0)));
        var config = ElytraRingsConfig.dedicatedWorld(Duration.ofMinutes(2), course);
        assertThrows(IllegalArgumentException.class, () -> new ElytraRingsPaperSettings(false, config, null,
                "elytra-course", null, 3.0, 65, 0));
        assertThrows(IllegalArgumentException.class, () -> new ElytraRingsPaperSettings(false, config, null,
                "elytra-course", null, 3.0, 0, 9));
    }
}
