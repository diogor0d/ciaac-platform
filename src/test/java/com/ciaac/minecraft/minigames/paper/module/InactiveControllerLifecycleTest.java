package com.ciaac.minecraft.minigames.paper.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.module.ModuleAvailability;
import com.ciaac.minecraft.minigames.module.ModuleStatus;
import com.ciaac.minecraft.minigames.region.RegionAdmissionToken;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequest;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequestFactory;
import com.ciaac.minecraft.minigames.runtime.AdmissionResult;
import com.ciaac.minecraft.minigames.runtime.AdmissionStatus;
import com.ciaac.minecraft.minigames.runtime.AuthenticatedSession;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.ConnectionRegistry;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class InactiveControllerLifecycleTest {
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void preparingPortRejectsJoinWithoutCreatingControllerThenReadyPreparationServesFirstJoin() {
        Fixture fixture = new Fixture();

        assertEquals(ModuleAvailability.STARTING, fixture.module.status().availability());
        assertFalse(fixture.module.status().joinable());
        assertFalse(fixture.module.join(fixture.player).accepted());
        assertEquals(0, fixture.port.creates);
        assertEquals(0, fixture.port.joins);
        assertEquals(0, fixture.tokens);

        fixture.module.tick(NOW);
        fixture.module.tick(NOW.plusSeconds(1));
        assertTrue(fixture.module.status().joinable());
        assertTrue(fixture.module.join(fixture.player).accepted());
        assertEquals(1, fixture.port.creates);
        assertEquals(1, fixture.port.joins);
        assertNotNull(fixture.port.controllerPreparation);
        assertEquals(fixture.port.preparationIdentity, fixture.port.controllerPreparation);
    }

    @Test
    void inactiveShutdownReleasesOwnedResourcesAndCanBeRepeated() {
        Fixture fixture = new Fixture();
        fixture.module.tick(NOW);
        assertEquals(3, fixture.port.ticketCount);

        fixture.module.shutdown();
        fixture.module.shutdown();

        assertEquals(0, fixture.port.ticketCount);
        assertEquals(1, fixture.port.inactiveShutdowns);
    }

    @Test
    void idleTickFailureFaultsClosedAndReleasesResources() {
        Fixture fixture = new Fixture();
        fixture.port.failOnTick = true;

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> fixture.module.tick(NOW));
        assertEquals("PREPARATION_FAILED", failure.getMessage());

        assertFalse(fixture.module.status().joinable());
        assertEquals(ModuleAvailability.CLOSED, fixture.module.status().availability());
        assertEquals(0, fixture.port.ticketCount);
        assertEquals(1, fixture.port.inactiveShutdowns);
        assertEquals(0, fixture.port.creates);
    }

    private static final class Fixture {
        private final UUID playerId = UUID.randomUUID();
        private final Player player = player(playerId);
        private final ConnectionRegistry connections = new ConnectionRegistry();
        private final AuthenticationRegistry authentication = new AuthenticationRegistry();
        private final TestPort port = new TestPort();
        private int tokens;
        private final TestModule module;

        private Fixture() {
            var connection = connections.begin(player, NOW.minusSeconds(1));
            authentication.authenticated(new AuthenticatedSession(UUID.randomUUID(), playerId,
                    connection.id(), NOW.minusSeconds(1), NOW.plusSeconds(60)));
            ModuleIdentity identity = new ModuleIdentity(connections,
                    new AdmissionRequestFactory(connections, authentication, CLOCK));
            module = new TestModule(identity, port,
                    (game, request, region, now, lifetime) -> {
                        tokens++;
                        return new RegionAdmissionToken(UUID.randomUUID(), request.sessionId(),
                                request.playerId(), region, now, now.plus(lifetime));
                    });
        }
    }

    private static final class TestModule extends AbstractFixedModule<TestController> {
        private TestModule(ModuleIdentity identity, FixedControllerPort<TestController> port,
                           RegionTokenFactory tokens) {
            super(GameKey.ELYTRA_RINGS, identity, port, tokens, "course-boundary",
                    Duration.ofMinutes(2), CLOCK);
        }
    }

    private static final class TestController { }

    private static final class TestPort implements FixedControllerPort<TestController> {
        private final Object preparationIdentity = new Object();
        private Object controllerPreparation;
        private int ticks;
        private int ticketCount;
        private int creates;
        private int joins;
        private int inactiveShutdowns;
        private boolean failOnTick;

        @Override public ModuleStatus inactiveStatus() {
            if (ticks < 2) return new ModuleStatus(GameKey.ELYTRA_RINGS,
                    ModuleAvailability.STARTING, false, 0, OptionalInt.of(1), "A preparar.");
            return new ModuleStatus(GameKey.ELYTRA_RINGS,
                    ModuleAvailability.WAITING, true, 0, OptionalInt.of(1), "Disponível.");
        }

        @Override public TestController create(UUID matchId, Player initialPlayer) {
            creates++;
            controllerPreparation = preparationIdentity;
            return new TestController();
        }

        @Override public ModuleStatus status(TestController controller, UUID matchId) {
            return inactiveStatus();
        }

        @Override public AdmissionResult join(TestController controller, Player player,
                                               AdmissionRequest request, RegionAdmissionToken token) {
            joins++;
            return new AdmissionResult(AdmissionStatus.PREPARED, "JOINED", "Entraste.", Optional.empty());
        }

        @Override public void leave(TestController controller, UUID playerId, UUID operationId) { }
        @Override public void tick(TestController controller, Instant now) { }
        @Override public void shutdown(TestController controller, UUID operationId) { }
        @Override public boolean terminal(TestController controller) { return false; }

        @Override public void tickInactive(Instant now) {
            ticks++;
            ticketCount = 3;
            if (failOnTick) throw new IllegalStateException("PREPARATION_FAILED");
        }

        @Override public void shutdownInactive() {
            if (ticketCount > 0) {
                ticketCount = 0;
                inactiveShutdowns++;
            }
        }
    }

    private static Player player(UUID playerId) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> playerId;
                    case "isOnline", "isValid" -> true;
                    case "toString" -> "fixture-player";
                    default -> throw new AssertionError("Unexpected Player method: " + method.getName());
                });
    }
}
