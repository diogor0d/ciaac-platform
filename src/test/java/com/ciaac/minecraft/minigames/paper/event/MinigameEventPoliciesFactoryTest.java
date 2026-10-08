package com.ciaac.minecraft.minigames.paper.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MinigameEventPoliciesFactoryTest {
    @Test
    void archeryTargetWithUnknownExtraBandIsRejected() {
        assertTrue(MinigameEventPoliciesFactory.resolveArcheryTargetBand(
                Set.of("ciaac-archery-target:target-1:inner", "ciaac-archery-target:target-1:unknown"),
                "target-1", Map.of("inner", 7, "outer", 2)).isEmpty());
    }

    @Test
    void archeryTargetWithOneConfiguredBandResolvesThatBand() {
        var resolved = MinigameEventPoliciesFactory.resolveArcheryTargetBand(
                Set.of("ciaac-archery-target:target-1:inner", "unrelated"),
                "target-1", Map.of("inner", 7, "outer", 2));

        assertEquals("inner", resolved.orElseThrow());
    }

    @Test
    void archeryTargetWithMultipleConfiguredBandsIsRejectedAsAmbiguous() {
        var resolved = MinigameEventPoliciesFactory.resolveArcheryTargetBand(
                Set.of("ciaac-archery-target:target-1:inner",
                        "ciaac-archery-target:target-1:outer"),
                "target-1", Map.of("inner", 7, "outer", 2));

        assertTrue(resolved.isEmpty());
    }
}
