package com.ciaac.minecraft.minigames.paper.configuration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.configuration.RuntimeConfigurationLoader;
import com.ciaac.minecraft.minigames.core.GameKey;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

final class ColosseumConfigurationTest {
    @Test
    void preparedColosseumOverlayResolvesAndSeparatesPublicBenchesFromFullHeightCombat() throws Exception {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        YamlConfiguration overlay = new YamlConfiguration();
        overlay.load(Path.of("docs/examples/colosseum-arena.yml").toFile());
        config.set("modules.arena", overlay.getConfigurationSection("modules.arena"));
        config.set("admission.enabled", true);
        UUID id = UUID.fromString(config.getString("modules.arena.world.uuid"));
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> "world";
                    case "getUID" -> id;
                    case "getMinHeight" -> -64;
                    case "getMaxHeight" -> 320;
                    default -> primitiveDefault(method.getReturnType());
                });
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getWorld" -> id.equals(args[0]) ? world : null;
                    case "getWorlds" -> List.of(world);
                    default -> primitiveDefault(method.getReturnType());
                });
        var result = new PaperConfigurationResolver().resolve(new RuntimeConfigurationLoader().load(config), server);
        var arena = result.module(GameKey.ARENA).orElseThrow();
        assertTrue(arena.admissionAllowed(), () -> arena.diagnostics().toString());
        var combat = arena.regions().get("combat-floor");
        var benches = arena.regions().get("spectator-benches");
        assertTrue(combat.contains(-418, -64, 6425));
        assertTrue(combat.contains(-321, 319, 6470));
        assertFalse(combat.contains(-320, 87, 6447));
        assertFalse(combat.contains(-370, 98, 6471));
        assertTrue(benches.contains(arena.locations().get("recovery")));
        assertFalse(combat.intersects(benches));
    }

    private static Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }
}
