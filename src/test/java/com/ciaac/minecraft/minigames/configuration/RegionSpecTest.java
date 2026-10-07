package com.ciaac.minecraft.minigames.configuration;

import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegionSpecTest {
    private static final WorldReference WORLD = new WorldReference(
            "world", UUID.fromString("11111111-1111-1111-1111-111111111111"));

    @Test
    void fullHeightRegionRequiresWorldBoundsForResolution() {
        RegionSpec region = new RegionSpec(WORLD, -418, 0, 6425, -321, 100, 6470, true);

        assertThrows(IllegalStateException.class, region::toRegion);
        assertEquals(-64, region.toRegion(-64, 320).minY());
        assertEquals(319, region.toRegion(-64, 320).maxY());
    }

    @Test
    void finiteRegionKeepsConfiguredBoundsAndLegacyConstructor() {
        RegionSpec region = new RegionSpec(WORLD, -5, -10, -7, 5, 20, 7);

        assertEquals(-10, region.toRegion().minY());
        assertEquals(20, region.toRegion().maxY());
        assertFalse(region.fullHeight());
    }
}
