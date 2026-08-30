package com.ciaac.minecraft.minigames.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ModuleCatalogTest {
    @Test
    void foundationCatalogClassifiesEveryKnownGameAndKeepsAdmissionClosed() {
        ModuleCatalog catalog = ModuleCatalog.foundationCatalog();

        assertEquals(GameKey.values().length, catalog.all().size());
        assertFalse(catalog.all().stream().anyMatch(ModuleDefinition::admissionOpen));
        assertEquals(ImplementationStage.SOURCE_IMPLEMENTED, catalog.get(GameKey.ARENA).stage());
        assertEquals(ImplementationStage.SOURCE_IMPLEMENTED, catalog.get(GameKey.ELYTRA_RINGS).stage());
    }

    @Test
    void locationPoliciesMatchTheRequestedWorldBoundaries() {
        assertEquals(GameLocationPolicy.SPAWN_SAFEZONE, GameKey.KNOCKBACK_SUMO.locationPolicy());
        assertEquals(GameLocationPolicy.SPAWN_SAFEZONE, GameKey.COLOR_FLOOR.locationPolicy());
        assertEquals(GameLocationPolicy.DEDICATED_WORLD, GameKey.BUILD_BATTLE.locationPolicy());
        assertEquals(GameLocationPolicy.DEDICATED_WORLD, GameKey.ELYTRA_RINGS.locationPolicy());
    }

    @Test
    void domainFoundationCannotBeConstructedWithOpenAdmission() {
        assertThrows(IllegalArgumentException.class, () -> new ModuleDefinition(
                GameKey.ARENA,
                ImplementationStage.DOMAIN_FOUNDATION,
                true,
                "Aberto"));
    }
}
