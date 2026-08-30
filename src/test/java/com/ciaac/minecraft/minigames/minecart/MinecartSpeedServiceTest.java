package com.ciaac.minecraft.minigames.minecart;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.entity.minecart.StorageMinecart;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MinecartSpeedServiceTest {
    @TempDir Path temporaryDirectory;

    @Test
    void successfulReloadClearsOverridesWhileRejectedReloadPreservesThem() throws Exception {
        Path configuration = temporaryDirectory.resolve("minecarts.yml");
        Files.writeString(configuration, validConfiguration());
        MinecartSpeedService service = new MinecartSpeedService(plugin(server()), configuration.toFile());

        assertTrue(service.reload().accepted());
        assertTrue(service.setDefaultOverride(24.0).accepted());
        assertTrue(service.temporaryOverrides().active());

        Files.writeString(configuration, "schema-version: 2\nenabled: true\n");
        assertFalse(service.reload().accepted());
        assertTrue(service.temporaryOverrides().active());

        Files.writeString(configuration, validConfiguration());
        MinecartSpeedControl.ReloadResult accepted = service.reload();
        assertTrue(accepted.accepted());
        assertTrue(accepted.clearedOverrides().isPresent());
        assertFalse(service.temporaryOverrides().active());
    }

    @Test
    void ignoresNonRideableMinecartTypes() {
        MinecartSpeedService service = new MinecartSpeedService(
                plugin(server()), temporaryDirectory.resolve("minecarts.yml").toFile());
        AtomicInteger mutations = new AtomicInteger();
        StorageMinecart storage = (StorageMinecart) Proxy.newProxyInstance(
                StorageMinecart.class.getClassLoader(), new Class<?>[] { StorageMinecart.class },
                (proxy, method, arguments) -> {
                    if (method.getName().equals("setMaxSpeed") || method.getName().equals("getPersistentDataContainer")) {
                        mutations.incrementAndGet();
                    }
                    return defaultValue(method.getReturnType());
                });

        service.onVehicleCreated(new VehicleCreateEvent(storage));

        assertTrue(mutations.get() == 0);
    }

    @Test
    void disabledPolicyLeavesNativeCartSpeedUntouched() throws Exception {
        CartFixture fixture = cartFixture();
        Path configuration = temporaryDirectory.resolve("minecarts-disabled.yml");
        Files.writeString(configuration, validConfiguration());
        MinecartSpeedService service = new MinecartSpeedService(plugin(fixture.server()), configuration.toFile());

        assertTrue(service.reload().accepted());
        assertFalse(service.configuration().enabled());
        assertEquals(0.7, fixture.maxSpeed.get());
        assertEquals(0, fixture.setCalls.get());
    }

    @Test
    void failedApplyRestoresPolicyOverridesAndAlreadyTouchedCarts() throws Exception {
        CartFixture fixture = cartFixture();
        Path configuration = temporaryDirectory.resolve("minecarts-atomic.yml");
        Files.writeString(configuration, enabledConfiguration(16.0));
        MinecartSpeedService service = new MinecartSpeedService(plugin(fixture.server()), configuration.toFile());

        assertTrue(service.reload().accepted());
        assertTrue(service.setDefaultOverride(24.0).accepted());
        fixture.throwOnCall.set(3);
        Files.writeString(configuration, enabledConfiguration(20.0));

        assertFalse(service.reload().accepted());
        assertEquals(16.0, service.configuration().defaultBlocksPerSecond());
        assertTrue(service.temporaryOverrides().active());
        assertEquals(1.2, fixture.maxSpeed.get());
        assertEquals(0.7, fixture.originalSpeed.get());
    }

    @Test
    void failedWorldOverrideRestoresOverlayAndCart() throws Exception {
        CartFixture fixture = cartFixture();
        Path configuration = temporaryDirectory.resolve("minecarts-set-world.yml");
        Files.writeString(configuration, enabledConfiguration(16.0));
        MinecartSpeedService service = new MinecartSpeedService(plugin(fixture.server()), configuration.toFile());
        MinecartSpeedControl.WorldIdentity world = new MinecartSpeedControl.WorldIdentity(
                "spawn", UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

        assertTrue(service.reload().accepted());
        fixture.throwOnCall.set(2);

        MinecartSpeedControl.ChangeResult result = service.setWorldOverride(world, 24.0);

        assertFalse(result.accepted());
        assertFalse(service.temporaryOverrides().active());
        assertEquals(0.8, fixture.maxSpeed.get());
        assertEquals(0.7, fixture.originalSpeed.get());
    }

    @Test
    void failedDefaultOverrideRestoresOverlayAndCart() throws Exception {
        CartFixture fixture = cartFixture();
        Path configuration = temporaryDirectory.resolve("minecarts-set-default.yml");
        Files.writeString(configuration, enabledConfiguration(16.0));
        MinecartSpeedService service = new MinecartSpeedService(plugin(fixture.server()), configuration.toFile());

        assertTrue(service.reload().accepted());
        fixture.throwOnCall.set(2);

        MinecartSpeedControl.ChangeResult result = service.setDefaultOverride(24.0);

        assertFalse(result.accepted());
        assertFalse(service.temporaryOverrides().active());
        assertEquals(0.8, fixture.maxSpeed.get());
        assertEquals(0.7, fixture.originalSpeed.get());
    }

    @Test
    void failedWorldClearRestoresOverlayAndCart() throws Exception {
        CartFixture fixture = cartFixture();
        Path configuration = temporaryDirectory.resolve("minecarts-clear-world.yml");
        Files.writeString(configuration, enabledConfiguration(16.0));
        MinecartSpeedService service = new MinecartSpeedService(plugin(fixture.server()), configuration.toFile());
        MinecartSpeedControl.WorldIdentity world = new MinecartSpeedControl.WorldIdentity(
                "spawn", UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));

        assertTrue(service.reload().accepted());
        assertTrue(service.setWorldOverride(world, 24.0).accepted());
        fixture.throwOnCall.set(3);

        MinecartSpeedControl.ChangeResult result = service.clearWorldOverride(world);

        assertFalse(result.accepted());
        assertTrue(service.temporaryOverrides().active());
        assertEquals(1.2, fixture.maxSpeed.get());
        assertEquals(0.7, fixture.originalSpeed.get());
    }

    @Test
    void failedClearAllRestoresDefaultOverlayAndCart() throws Exception {
        CartFixture fixture = cartFixture();
        Path configuration = temporaryDirectory.resolve("minecarts-clear-all.yml");
        Files.writeString(configuration, enabledConfiguration(16.0));
        MinecartSpeedService service = new MinecartSpeedService(plugin(fixture.server()), configuration.toFile());

        assertTrue(service.reload().accepted());
        assertTrue(service.setDefaultOverride(24.0).accepted());
        fixture.throwOnCall.set(3);

        MinecartSpeedControl.ChangeResult result = service.clearAllOverrides();

        assertFalse(result.accepted());
        assertEquals(Optional.of(24.0), service.temporaryOverrides().defaultBlocksPerSecond());
        assertEquals(1.2, fixture.maxSpeed.get());
        assertEquals(0.7, fixture.originalSpeed.get());
    }

    private static String validConfiguration() {
        return "schema-version: 1\nenabled: false\n"
                + "default-terminal-speed-blocks-per-second: 16.0\nworlds: {}\n";
    }

    private static String enabledConfiguration(double speed) {
        return "schema-version: 1\nenabled: true\n"
                + "default-terminal-speed-blocks-per-second: " + speed + "\nworlds: {}\n";
    }

    private static CartFixture cartFixture() {
        UUID worldId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        AtomicReference<Double> maxSpeed = new AtomicReference<>(0.7);
        AtomicReference<Double> originalSpeed = new AtomicReference<>();
        AtomicInteger setCalls = new AtomicInteger();
        AtomicInteger throwOnCall = new AtomicInteger(-1);
        PersistentDataContainer data = (PersistentDataContainer) Proxy.newProxyInstance(
                PersistentDataContainer.class.getClassLoader(), new Class<?>[] { PersistentDataContainer.class },
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "get" -> originalSpeed.get();
                    case "has" -> originalSpeed.get() != null;
                    case "set" -> { originalSpeed.set((Double) arguments[2]); yield null; }
                    case "remove" -> { originalSpeed.set(null); yield null; }
                    default -> defaultValue(method.getReturnType());
                });
        AtomicReference<World> worldRef = new AtomicReference<>();
        RideableMinecart cart = (RideableMinecart) Proxy.newProxyInstance(
                RideableMinecart.class.getClassLoader(), new Class<?>[] { RideableMinecart.class },
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getWorld" -> worldRef.get();
                    case "getMaxSpeed" -> maxSpeed.get();
                    case "setMaxSpeed" -> {
                        int call = setCalls.incrementAndGet();
                        if (call == throwOnCall.get()) throw new IllegalStateException("synthetic cart failure");
                        maxSpeed.set((Double) arguments[0]);
                        yield null;
                    }
                    case "getPersistentDataContainer" -> data;
                    default -> defaultValue(method.getReturnType());
                });
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] { World.class },
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> "spawn";
                    case "getUID" -> worldId;
                    case "getEntitiesByClass" -> List.of(cart);
                    default -> defaultValue(method.getReturnType());
                });
        worldRef.set(world);
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] { Server.class },
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getWorlds" -> List.of(world);
                    case "getWorld" -> {
                        Object key = arguments[0];
                        yield key instanceof UUID uuid
                                ? (worldId.equals(uuid) ? world : null)
                                : ("spawn".equals(key) ? world : null);
                    }
                    default -> defaultValue(method.getReturnType());
                });
        return new CartFixture(server, maxSpeed, originalSpeed, setCalls, throwOnCall);
    }

    private record CartFixture(Server server, AtomicReference<Double> maxSpeed,
                               AtomicReference<Double> originalSpeed, AtomicInteger setCalls,
                               AtomicInteger throwOnCall) { }

    private static Server server() {
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] { Server.class },
                (proxy, method, arguments) -> method.getName().equals("getWorlds")
                        ? List.of() : defaultValue(method.getReturnType()));
    }

    private static Plugin plugin(Server server) {
        Logger logger = Logger.getLogger("minecart-service-test");
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] { Plugin.class },
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> "CIAACPlatform";
                    case "namespace" -> "ciaacplatform";
                    case "getServer" -> server;
                    case "getLogger" -> logger;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        return 0;
    }
}
