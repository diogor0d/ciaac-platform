package com.ciaac.minecraft.minigames.paper.elytrarings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.elytrarings.ElytraCourseRevision;
import com.ciaac.minecraft.minigames.elytrarings.ElytraRingsConfig;
import com.ciaac.minecraft.minigames.elytrarings.RingCheckpoint;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

class ElytraChunkPreparationTest {
    @Test
    void footprintCoversIntermediateRouteAndConfiguredRadius() {
        Set<ElytraChunkPreparation.ChunkCoordinate> chunks = ElytraChunkPreparation.requiredChunks(
                new Location(null, 0, 100, 0),
                List.of(new RingCheckpoint(1, 48, 100, 0),
                        new RingCheckpoint(2, 48, 100, 32)),
                1);

        assertTrue(chunks.contains(new ElytraChunkPreparation.ChunkCoordinate(0, 0)));
        assertTrue(chunks.contains(new ElytraChunkPreparation.ChunkCoordinate(1, 0)));
        assertTrue(chunks.contains(new ElytraChunkPreparation.ChunkCoordinate(2, 0)));
        assertTrue(chunks.contains(new ElytraChunkPreparation.ChunkCoordinate(3, 0)));
        assertTrue(chunks.contains(new ElytraChunkPreparation.ChunkCoordinate(3, 2)));
        assertTrue(chunks.contains(new ElytraChunkPreparation.ChunkCoordinate(2, 1)));
    }

    @Test
    void oversizedRouteFailsBeforeAnyPaperOperation() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ElytraChunkPreparation.requiredChunks(
                        new Location(null, 0, 100, 0),
                        List.of(new RingCheckpoint(1,
                                (double) (16L * (ElytraChunkPreparation.MAX_REQUIRED_CHUNKS + 1L)),
                                100, 0)),
                        0));

        assertEquals("PRELOAD_FOOTPRINT_TOO_LARGE", failure.getMessage());
    }

    @Test
    void disabledPreparationNeverAdmits() {
        var course = new ElytraCourseRevision(
                "v1", "00000000-0000-0000-0000-000000000001",
                List.of(new RingCheckpoint(1, 0, 100, 0),
                        new RingCheckpoint(2, 16, 100, 0)));
        var settings = ElytraRingsPaperSettings.disabled(
                ElytraRingsConfig.dedicatedWorld(Duration.ofMinutes(2), course),
                "elytra-course");
        var preparation = ElytraChunkPreparation.unavailable(settings, "CHUNK_PREPARER_UNAVAILABLE");

        assertEquals(ElytraChunkPreparation.Phase.DISABLED, preparation.status().phase());
        assertFalse(preparation.admissionReady());
    }
}
