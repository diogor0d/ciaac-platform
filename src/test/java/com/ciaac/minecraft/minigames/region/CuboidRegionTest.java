package com.ciaac.minecraft.minigames.region;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class CuboidRegionTest {
    private static final UUID WORLD_ID = UUID.randomUUID();

    @Test
    void rejectsRangesWhoseIntSubtractionWouldWrap() {
        assertThrows(IllegalArgumentException.class, () -> new CuboidRegion(
                WORLD_ID,
                Integer.MIN_VALUE, Integer.MIN_VALUE, 0,
                Integer.MAX_VALUE - 1, Integer.MAX_VALUE - 1, 0));
    }

    @Test
    void rejectsRangesAboveTheVolumeLimitWithoutMultiplicationOverflow() {
        assertThrows(IllegalArgumentException.class, () -> new CuboidRegion(
                WORLD_ID, 0, 0, 0, 65_535, 65_535, 65_535));
        assertThrows(IllegalArgumentException.class, () -> new CuboidRegion(
                WORLD_ID, 0, 0, 0, 0, 0, 100_000_000));
    }

    @Test
    void acceptsExactVolumeLimitWithInclusiveBounds() {
        CuboidRegion region = new CuboidRegion(WORLD_ID, 0, 0, 0, 0, 0, 99_999_999);

        assertTrue(region.contains(0, 0, 0));
        assertTrue(region.contains(0, 0, 99_999_999));
        assertFalse(region.contains(0, 0, 100_000_000));
    }

    @Test
    void acceptsRepresentativeFullHeightColosseumBounds() {
        CuboidRegion region = new CuboidRegion(
                WORLD_ID, -418, -64, 6_425, -321, 319, 6_470);

        assertTrue(region.contains(-418, -64, 6_425));
        assertTrue(region.contains(-321, 319, 6_470));
        assertFalse(region.contains(-320, 319, 6_470));
    }
}
