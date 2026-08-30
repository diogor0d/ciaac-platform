package com.ciaac.minecraft.minigames.isolation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

final class IsolationPolicyTest {
    @Test
    void strictPolicyCoversEveryDeclaredFacet() {
        assertEquals(
                EnumSet.allOf(PlayerStateFacet.class),
                IsolationPolicy.strictNoProgress().protectedFacets());
    }

    @Test
    void incompletePolicyFailsClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new IsolationPolicy(EnumSet.of(PlayerStateFacet.INVENTORY)));
    }
}
