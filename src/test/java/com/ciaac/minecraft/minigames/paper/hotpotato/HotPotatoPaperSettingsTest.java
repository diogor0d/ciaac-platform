package com.ciaac.minecraft.minigames.paper.hotpotato;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ciaac.minecraft.minigames.hotpotato.HotPotatoConfig;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

class HotPotatoPaperSettingsTest {
    private static final HotPotatoConfig CONFIG = new HotPotatoConfig(
            2, 2, Duration.ofSeconds(12), Duration.ofSeconds(4), Duration.ZERO,
            4, Duration.ofMinutes(1), "r1");

    @Test
    void invalidRegionIdIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> HotPotatoPaperSettings.disabled(CONFIG, "Bad"));
    }

    @Test
    void enabledSettingsRejectSpawnFromAnotherWorld() {
        World arena = world(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        World other = world(UUID.fromString("22222222-2222-2222-2222-222222222222"));

        assertThrows(IllegalArgumentException.class, () -> new HotPotatoPaperSettings(
                true, CONFIG, arena, "hot-potato-arena",
                List.of(new Location(arena, 0, 65, 0), new Location(other, 2, 65, 0)),
                Duration.ofSeconds(20), Duration.ofMinutes(10)));
    }

    private static World world(UUID id) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUID" -> id;
                    case "hashCode" -> id.hashCode();
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }
}
