package com.ciaac.minecraft.minigames.paper.configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.configuration.RuntimeConfiguration;
import com.ciaac.minecraft.minigames.configuration.RuntimeConfigurationLoader;
import com.ciaac.minecraft.minigames.core.GameKey;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class ColorFloorConfigurationRegressionTest {
    private static final UUID WORLD_ID = UUID.fromString("281f9356-e534-48c4-a3d4-93de28a0df12");

    @Test
    void admitsPlayerStartOneBlockAboveAnOwnedFloorCell() {
        ResolvedModuleConfiguration module = resolve(0.5, 81.0, 0.5, 80, 1, 1);

        assertTrue(module.admissionAllowed(), () -> module.diagnostics().toString());
    }

    @Test
    void rejectsEmbeddedOrBelowFloorStart() {
        assertRejectedWithCode(resolve(0.5, 80.9, 0.5, 80, 1, 1), "LOCATION_OUTSIDE_REGION");
        assertRejectedWithCode(resolve(0.5, 79.9, 0.5, 80, 1, 1), "LOCATION_OUTSIDE_REGION");
    }

    @Test
    void rejectsStartBesideFloorEvenWhenInsideBoundary() {
        assertRejectedWithCode(resolve(2.5, 81.0, 0.5, 80, 1, 1), "LOCATION_OUTSIDE_REGION");
    }

    @Test
    void rejectsStartOutsideBoundary() {
        assertRejectedWithCode(resolve(4.5, 81.0, 0.5, 80, 1, 1), "LOCATION_OUTSIDE_REGION");
    }

    @Test
    void rejectsMultiHeightAndMoreThan4096Cells() {
        assertRejectedWithCode(resolve(0.5, 82.0, 0.5, 81, 1, 1), "GEOMETRY_UNSAFE");
        assertRejectedWithCode(resolve(0.5, 81.0, 0.5, 80, 64, 63), "GEOMETRY_UNSAFE");
    }

    private static void assertRejectedWithCode(ResolvedModuleConfiguration module, String code) {
        assertFalse(module.admissionAllowed(), () -> module.diagnostics().toString());
        assertTrue(module.diagnostics().stream().anyMatch(diagnostic -> diagnostic.code().equals(code)),
                () -> module.diagnostics().toString());
    }

    private static ResolvedModuleConfiguration resolve(
            double startX, double startY, double startZ, int floorMaxY, int floorMaxX, int floorMaxZ) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema-version", 2);
        yaml.set("locale", "pt-PT");
        yaml.set("admission.enabled", true);
        for (String invariant : List.of(
                "admission.require-authenticated-adapter", "admission.block-while-recovery-pending",
                "progress-isolation.inventory", "progress-isolation.item-metadata-and-durability",
                "progress-isolation.armor", "progress-isolation.off-hand", "progress-isolation.cursor",
                "progress-isolation.ender-chest", "progress-isolation.experience",
                "progress-isolation.health-food-effects", "progress-isolation.location-flight-gamemode",
                "progress-isolation.vanilla-statistics", "progress-isolation.advancements",
                "progress-isolation.economy-permissions", "progress-isolation.claims-homes",
                "progress-isolation.scoreboards-cooldowns",
                "progress-isolation.temporary-world-blocks-and-entities")) {
            yaml.set(invariant, true);
        }
        yaml.set("persistence.sqlite-file", "minigames.sqlite");
        yaml.set("persistence.snapshot-journal-directory", "recovery");

        String root = "modules.color-floor.";
        yaml.set(root + "enabled", true);
        yaml.set(root + "world.name", "world");
        yaml.set(root + "world.uuid", WORLD_ID.toString());
        yaml.set(root + "regions.floor.min", List.of(0, 80, 0));
        yaml.set(root + "regions.floor.max", List.of(floorMaxX, floorMaxY, floorMaxZ));
        yaml.set(root + "regions.boundary.min", List.of(-2, 70, -2));
        yaml.set(root + "regions.boundary.max", List.of(3, 90, 3));
        yaml.set(root + "locations.start.x", startX);
        yaml.set(root + "locations.start.y", startY);
        yaml.set(root + "locations.start.z", startZ);
        yaml.set(root + "locations.exit.x", -1.5);
        yaml.set(root + "locations.exit.y", 81.0);
        yaml.set(root + "locations.exit.z", -1.5);
        yaml.set(root + "minimum-players", 2);
        yaml.set(root + "maximum-players", 8);
        yaml.set(root + "rounds", 5);
        yaml.set(root + "announce-ticks", 20);
        yaml.set(root + "unsafe-ticks", 20);
        yaml.set(root + "palette", List.of("RED", "BLUE"));
        yaml.set(root + "restore-strategy", "immutable-template");

        RuntimeConfiguration configuration = new RuntimeConfigurationLoader().load(yaml);
        ConfigurationResolutionResult resolved = new PaperConfigurationResolver().resolve(configuration,
                server(world("world", WORLD_ID)));
        return resolved.module(GameKey.COLOR_FLOOR).orElseThrow();
    }

    private static World world(String name, UUID id) {
        return proxy(World.class, (proxy, method, args) -> switch (method.getName()) {
            case "getName" -> name;
            case "getUID" -> id;
            case "getMinHeight" -> 0;
            case "getMaxHeight" -> 256;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Server server(World world) {
        return proxy(Server.class, (proxy, method, args) -> switch (method.getName()) {
            case "getWorld" -> args != null && args.length == 1 && WORLD_ID.equals(args[0]) ? world : null;
            case "getWorlds" -> List.of(world);
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }
}
