package com.ciaac.minecraft.minigames.paper.auth;

import static org.junit.jupiter.api.Assertions.*;

import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.ConnectionRegistry;
import com.nickuc.login.api.event.bukkit.auth.AuthenticateEvent;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.GameMode;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

final class NLoginAuthenticationListenerTest {
    private static final Instant NOW = Instant.parse("2026-10-05T14:00:00Z");

    @Test
    void publicBridgeNeverUsesAuthenticationEventOrElapsedTicksAsCompletion() {
        Fixture fixture = new Fixture(true, false);
        fixture.listener.onAuthenticated(new AuthenticateEvent(fixture.player));
        fixture.mode.set(GameMode.ADVENTURE);
        fixture.runTasks();

        assertTrue(fixture.authentication.current(fixture.playerId, NOW).isEmpty());
        assertEquals(0, fixture.hooks);
        assertEquals(GameMode.ADVENTURE, fixture.mode.get());
        assertTrue(fixture.playerTasks.isEmpty());
    }

    @Test
    void recoveryWaitsForExplicitProviderCompletionAfterLateStateMutation() {
        Fixture fixture = new Fixture(false, true);
        fixture.listener.onAuthenticated(new AuthenticateEvent(fixture.player));
        fixture.runTasks();
        fixture.mode.set(GameMode.ADVENTURE);
        fixture.runTasks();
        assertTrue(fixture.authentication.current(fixture.playerId, NOW).isEmpty());
        assertEquals(0, fixture.hooks);

        fixture.completeRestore();
        assertEquals(0, fixture.hooks);
        fixture.runTasks();

        assertEquals(GameMode.SURVIVAL, fixture.mode.get());
        assertEquals(1, fixture.hooks);
        assertTrue(fixture.authentication.current(fixture.playerId, NOW).isPresent());
    }

    @Test
    void primaryThreadCompletionAlsoWaitsForThePlayerQueue() {
        Fixture fixture = new Fixture(true, true);
        fixture.listener.onAuthenticated(new AuthenticateEvent(fixture.player));
        fixture.completeRestore();

        assertTrue(fixture.authentication.current(fixture.playerId, NOW).isEmpty());
        assertEquals(0, fixture.hooks);
        fixture.runTasks();
        assertEquals(1, fixture.hooks);
    }

    @Test
    void duplicateCompletionCannotRunRecoveryAgainForTheSameEvent() {
        Fixture fixture = new Fixture(false, true);
        fixture.listener.onAuthenticated(new AuthenticateEvent(fixture.player));
        fixture.completeRestore();
        fixture.completeRestore();
        fixture.runTasks();
        fixture.completeRestore();
        fixture.runTasks();

        assertEquals(1, fixture.hooks);
    }

    @Test
    void queuedCompletionCannotAuthorizeAReplacementConnectionWithTheSamePlayerObject() {
        Fixture fixture = new Fixture(false, true);
        fixture.listener.onAuthenticated(new AuthenticateEvent(fixture.player));
        fixture.completeRestore();
        fixture.connections.begin(fixture.player, NOW);
        fixture.runTasks();

        assertTrue(fixture.authentication.current(fixture.playerId, NOW).isEmpty());
        assertEquals(0, fixture.hooks);
    }

    @Test
    void completionAfterReconnectCannotAuthorizeTheNewConnection() {
        Fixture fixture = new Fixture(false, true);
        fixture.listener.onAuthenticated(new AuthenticateEvent(fixture.player));
        fixture.connections.begin(fixture.player, NOW);
        fixture.completeRestore();
        fixture.runTasks();

        assertTrue(fixture.authentication.current(fixture.playerId, NOW).isEmpty());
        assertEquals(0, fixture.hooks);
    }

    @Test
    void disconnectedPlayerDoesNotReceiveAuthenticationOrRecovery() {
        Fixture fixture = new Fixture(false, true);
        fixture.listener.onAuthenticated(new AuthenticateEvent(fixture.player));
        fixture.completeRestore();
        fixture.online = false;
        fixture.runTasks();

        assertTrue(fixture.authentication.current(fixture.playerId, NOW).isEmpty());
        assertEquals(0, fixture.hooks);
    }

    @Test
    void retiredSchedulerCannotGrantAuthenticationOrRecovery() {
        Fixture fixture = new Fixture(false, true);
        fixture.listener.onAuthenticated(new AuthenticateEvent(fixture.player));
        fixture.retired = true;
        fixture.completeRestore();
        fixture.runTasks();

        assertTrue(fixture.authentication.current(fixture.playerId, NOW).isEmpty());
        assertEquals(0, fixture.hooks);
    }

    @Test
    void eventWithoutACurrentConnectionDoesNotRequestCompletion() {
        Fixture fixture = new Fixture(false, true);
        fixture.connections.end(fixture.player);
        fixture.listener.onAuthenticated(new AuthenticateEvent(fixture.player));
        fixture.runTasks();

        assertNull(fixture.completion);
        assertTrue(fixture.authentication.current(fixture.playerId, NOW).isEmpty());
        assertEquals(0, fixture.hooks);
    }

    private static final class Fixture {
        private final UUID playerId = UUID.randomUUID();
        private final ConnectionRegistry connections = new ConnectionRegistry();
        private final AuthenticationRegistry authentication = new AuthenticationRegistry();
        private final AtomicReference<GameMode> mode = new AtomicReference<>(GameMode.ADVENTURE);
        private final List<Runnable> playerTasks = new ArrayList<>();
        private boolean online = true;
        private boolean retired;
        private int hooks;
        private Runnable completion;
        private final Player player;
        private final NLoginAuthenticationListener listener;

        @SuppressWarnings("unchecked")
        private Fixture(boolean primaryThread, boolean supportedCompletion) {
            EntityScheduler entityScheduler = proxy(EntityScheduler.class, (method, args) -> {
                if (!method.equals("run")) throw new AssertionError("Unexpected scheduler method: " + method);
                Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> action =
                        (Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask>) args[1];
                if (!retired) playerTasks.add(() -> action.accept(null));
                return null;
            });
            player = proxy(Player.class, (method, args) -> switch (method) {
                case "getUniqueId" -> playerId;
                case "isOnline" -> online;
                case "getScheduler" -> entityScheduler;
                default -> throw new AssertionError("Unexpected player method: " + method);
            });
            Server server = proxy(Server.class, (method, args) -> switch (method) {
                case "isPrimaryThread" -> primaryThread;
                case "getPlayer" -> online ? player : null;
                default -> throw new AssertionError("Unexpected server method: " + method);
            });
            Plugin plugin = proxy(Plugin.class, (method, args) -> {
                if (method.equals("getServer")) return server;
                throw new AssertionError("Unexpected plugin method: " + method);
            });
            UUID originalConnectionId = connections.begin(player, NOW.minusSeconds(1)).id();
            Consumer<Player> hook = ignored -> {
                hooks++;
                mode.set(GameMode.SURVIVAL);
            };
            if (supportedCompletion) {
                listener = new NLoginAuthenticationListener(plugin, connections, authentication,
                        Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(1), hook,
                        (eventPlayer, connectionId, completed) -> {
                            assertSame(player, eventPlayer);
                            assertEquals(originalConnectionId, connectionId);
                            completion = completed;
                        });
            } else {
                listener = new NLoginAuthenticationListener(plugin, connections, authentication,
                        Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(1), hook);
            }
        }

        private void completeRestore() { completion.run(); }

        private void runTasks() {
            List.copyOf(playerTasks).forEach(Runnable::run);
            playerTasks.clear();
        }
    }

    private interface Invocation { Object call(String method, Object[] args); }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> self == args[0];
                    case "hashCode" -> System.identityHashCode(self);
                    case "toString" -> type.getSimpleName() + " fixture";
                    default -> null;
                };
            }
            return invocation.call(method.getName(), args == null ? new Object[0] : args);
        });
    }
}
