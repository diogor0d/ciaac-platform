package com.ciaac.minecraft.platform.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.configuration.RuntimeConfiguration;
import com.ciaac.minecraft.minigames.configuration.RuntimeConfigurationLoader;
import com.ciaac.minecraft.minigames.module.MinigameModuleRegistry;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FacilitySetupCommandsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void onlyAuthorizedLocalConsoleCanRunFacilityCommands() {
        Harness harness = new Harness();
        for (Class<? extends CommandSender> senderType : List.of(
                Player.class, BlockCommandSender.class, RemoteConsoleCommandSender.class)) {
            CommandSender sender = harness.sender(senderType, true);

            assertTrue(harness.commands.execute(sender, captureArgs()));

            assertTrue(harness.messages(sender).stream().anyMatch(message -> message.contains("consola local")));
        }
        assertEquals(0, harness.configurationReads.get());
        assertEquals(0, harness.moduleReads.get());
        assertEquals(0, harness.blockReads.get());
        assertEquals(0, harness.worldLookups.get());
        assertFalse(harness.templates.toFile().exists());
    }

    @Test
    void worldNameLookupRejectsCaseAliasInsteadOfTreatingItAsExactWorldIdentity() {
        Harness harness = new Harness();
        harness.worldName = "Arena";
        CommandSender console = harness.sender(ConsoleCommandSender.class, true);

        assertTrue(harness.commands.execute(console, new String[] {"instalacoes", "mundo", "arena"}));

        assertTrue(harness.messages(console).stream().anyMatch(message -> message.contains("nome exato")));
        assertEquals(1, harness.worldLookups.get());
        assertEquals(0, harness.configurationReads.get());
        assertEquals(0, harness.blockReads.get());
    }

    @Test
    void remoteConsoleIsRejectedEvenWhenItHasAdminPermission() {
        Harness harness = new Harness();
        CommandSender remote = harness.sender(RemoteConsoleCommandSender.class, true);

        assertFalse(remote instanceof ConsoleCommandSender);
        assertTrue(harness.commands.execute(remote, captureArgs()));

        assertTrue(harness.messages(remote).stream().anyMatch(message -> message.contains("consola local")));
        assertEquals(0, harness.configurationReads.get());
        assertEquals(0, harness.blockingChecks.get());
        assertEquals(0, harness.blockReads.get());
    }

    @Test
    void blockingSessionGateRunsBeforeConfigurationAndAnyWorldOrArtifactAccess() {
        Harness harness = new Harness();
        harness.hasBlockingSessions = true;
        // The local server console is authority in its own right; player permission defaults do not gate it.
        CommandSender console = harness.sender(ConsoleCommandSender.class, false);

        assertTrue(harness.commands.execute(console, captureArgs()));

        assertTrue(harness.messages(console).stream().anyMatch(message -> message.contains("todas as sessões")));
        assertEquals(1, harness.blockingChecks.get());
        assertEquals(0, harness.configurationReads.get());
        assertEquals(0, harness.moduleReads.get());
        assertEquals(0, harness.worldLookups.get());
        assertEquals(0, harness.blockReads.get());
        assertFalse(harness.templates.toFile().exists());
    }

    @Test
    void missingConfigurationAndInvalidFloorAreRefusedBeforeBlockCapture() {
        for (RuntimeConfiguration configuration : List.of(
                new RuntimeConfigurationLoader().load(new YamlConfiguration()),
                new RuntimeConfigurationLoader().load(invalidFloorConfiguration()))) {
            Harness harness = new Harness();
            harness.configuration = () -> {
                harness.configurationReads.incrementAndGet();
                return configuration;
            };
            CommandSender console = harness.sender(ConsoleCommandSender.class, true);

            assertTrue(harness.commands.execute(console, captureArgs()));

            assertTrue(harness.messages(console).stream().anyMatch(message -> message.contains("Configura primeiro")));
            assertEquals(1, harness.configurationReads.get());
            assertEquals(0, harness.blockReads.get());
            assertFalse(harness.templates.toFile().exists());
        }
    }

    private static String[] captureArgs() {
        return new String[] {"instalacoes", "capturar-cores"};
    }

    private static YamlConfiguration invalidFloorConfiguration() {
        YamlConfiguration configuration = new YamlConfiguration();
        configuration.set("schema-version", 2);
        configuration.set("locale", "pt-PT");
        configuration.set("admission.enabled", true);
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
            configuration.set(invariant, true);
        }
        String root = "modules.color-floor.";
        UUID worldId = UUID.fromString("281f9356-e534-48c4-a3d4-93de28a0df12");
        configuration.set(root + "enabled", true);
        configuration.set(root + "world.name", "world");
        configuration.set(root + "world.uuid", worldId.toString());
        configuration.set(root + "regions.floor.min", List.of(0, 64, 0));
        configuration.set(root + "regions.floor.max", List.of(1, 65, 1));
        configuration.set(root + "regions.boundary.min", List.of(-5, 60, -5));
        configuration.set(root + "regions.boundary.max", List.of(5, 70, 5));
        configuration.set(root + "locations.start.x", 0.5);
        configuration.set(root + "locations.start.y", 64.0);
        configuration.set(root + "locations.start.z", 0.5);
        configuration.set(root + "minimum-players", 2);
        configuration.set(root + "maximum-players", 8);
        configuration.set(root + "rounds", 5);
        configuration.set(root + "announce-ticks", 20);
        configuration.set(root + "unsafe-ticks", 20);
        configuration.set(root + "palette", List.of("RED", "BLUE"));
        configuration.set(root + "restore-strategy", "immutable-template");
        return configuration;
    }

    private final class Harness {
        private final AtomicInteger configurationReads = new AtomicInteger();
        private final AtomicInteger moduleReads = new AtomicInteger();
        private final AtomicInteger blockingChecks = new AtomicInteger();
        private final AtomicInteger worldLookups = new AtomicInteger();
        private final AtomicInteger blockReads = new AtomicInteger();
        private final Path templates = temporaryDirectory.resolve("templates-" + UUID.randomUUID());
        private final Map<CommandSender, List<String>> senderMessages = new IdentityHashMap<>();
        private String worldName = "world";
        private Supplier<RuntimeConfiguration> configuration = () -> {
            configurationReads.incrementAndGet();
            return new RuntimeConfigurationLoader().load(new YamlConfiguration());
        };
        private boolean hasBlockingSessions;
        private final BooleanSupplier blockingSessions = () -> {
            blockingChecks.incrementAndGet();
            return hasBlockingSessions;
        };
        private final Server server = server();
        private final FacilitySetupCommands commands = new FacilitySetupCommands(
                server, templates, () -> configuration.get(), () -> {
                    moduleReads.incrementAndGet();
                    return MinigameModuleRegistry.allUnavailable("test");
                }, () -> blockingSessions.getAsBoolean());

        private Harness() {}

        private Server server() {
            World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getName" -> worldName;
                        case "getUID" -> UUID.fromString("281f9356-e534-48c4-a3d4-93de28a0df12");
                        case "getMinHeight" -> 0;
                        case "getMaxHeight" -> 256;
                        case "isChunkLoaded" -> true;
                        case "getBlockAt" -> { blockReads.incrementAndGet(); yield null; }
                        case "toString" -> "Facility setup world";
                        default -> null;
                    });
            return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "isPrimaryThread" -> true;
                        case "getWorld" -> {
                            worldLookups.incrementAndGet();
                            if (args[0] instanceof UUID || worldName.equalsIgnoreCase(String.valueOf(args[0]))) yield world;
                            yield null;
                        }
                        case "getWorlds" -> List.of(world);
                        case "toString" -> "Facility setup server";
                        default -> null;
                    });
        }

        private CommandSender sender(Class<? extends CommandSender> type, boolean permission) {
            List<String> messages = new ArrayList<>();
            ClassLoader loader = type.getClassLoader();
            CommandSender value = (CommandSender) Proxy.newProxyInstance(loader, new Class<?>[] {type},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "hasPermission" -> permission;
                        case "sendMessage" -> {
                            if (args != null && args.length > 0 && args[0] instanceof String message) messages.add(message);
                            yield null;
                        }
                        case "getName" -> "test-sender";
                        case "toString" -> "Facility setup sender";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
            senderMessages.put(value, messages);
            return value;
        }

        private List<String> messages(CommandSender sender) {
            return senderMessages.getOrDefault(sender, List.of());
        }
    }

}
