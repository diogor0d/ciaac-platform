package com.ciaac.minecraft.minigames.paper.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.ConnectionRegistry;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.Server;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

final class AuthMeAuthenticationListenerTest {
    private static final Instant NOW = Instant.parse("2026-10-05T18:00:00Z");
    private Server previousServer;

    @BeforeEach void installEventThreadFixture() throws Exception {
        var field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        previousServer = (Server) field.get(null);
        field.set(null, proxy(Server.class, (method, args) -> method.equals("isPrimaryThread") ? true : null));
    }

    @AfterEach void restoreEventThreadFixture() throws Exception {
        var field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true);
        field.set(null, previousServer);
    }

    @Test
    void doesNotIssueBeforeColdStartupAndReloadCannotOpenTheGate() {
        Fixture fixture = new Fixture();
        fixture.listener.onLogin(fixture.player);
        assertTrue(fixture.playerTasks.isEmpty());
        assertNoEvidence(fixture);

        fixture.load(ServerLoadEvent.LoadType.RELOAD);
        fixture.listener.onLogin(fixture.player);
        fixture.runTasks();
        assertEquals(0, fixture.proof.establishCalls);
        assertNoEvidence(fixture);
    }

    @Test
    void loginIssuesOnlyAfterQueuedCompletionAndProviderAuthenticationProof() {
        Fixture fixture = new Fixture();
        fixture.load(ServerLoadEvent.LoadType.STARTUP);
        fixture.authenticated.set(false);
        fixture.listener.onLogin(fixture.player);

        assertNoEvidence(fixture);
        fixture.runTasks();
        assertNoEvidence(fixture);
        assertEquals(0, fixture.hooks);

        fixture.authenticated.set(true);
        fixture.listener.onLogin(fixture.player);
        fixture.runTasks();
        assertEquals(1, fixture.hooks);
        assertTrue(fixture.authentication.current(fixture.playerId, NOW).isPresent());
    }

    @Test
    void duplicateLoginEventsAuthorizeOnlyTheLatestQueuedAttemptOnce() {
        Fixture fixture = new Fixture();
        fixture.load(ServerLoadEvent.LoadType.STARTUP);
        fixture.listener.onLogin(fixture.player);
        fixture.listener.onLogin(fixture.player);
        assertEquals(2, fixture.playerTasks.size());

        fixture.runTasks();
        assertEquals(1, fixture.hooks);
        assertTrue(fixture.authentication.current(fixture.playerId, NOW).isPresent());
        fixture.listener.onLogin(fixture.player);
        assertEquals(0, fixture.playerTasks.size());
        assertEquals(1, fixture.hooks);
    }

    @Test
    void reloadAndRepeatedStartupRevokePreviouslyIssuedEvidence() {
        Fixture fixture = authenticatedFixture();
        fixture.load(ServerLoadEvent.LoadType.RELOAD);
        assertNoEvidence(fixture);
        assertEquals(1, fixture.hooks);

        Fixture repeatedStartup = authenticatedFixture();
        repeatedStartup.load(ServerLoadEvent.LoadType.STARTUP);
        assertNoEvidence(repeatedStartup);
    }

    @Test
    void logoutRevokesEvidenceAndCancelsQueuedLoginAttempt() {
        Fixture fixture = authenticatedFixture();
        fixture.listener.onLogin(fixture.player);
        fixture.listener.onLogout(fixture.player);
        fixture.runTasks();

        assertNoEvidence(fixture);
        assertEquals(1, fixture.hooks);
    }

    @Test
    void reconnectAndStalePlayerWithSameUuidCannotUseOldAttempt() {
        Fixture fixture = new Fixture();
        fixture.load(ServerLoadEvent.LoadType.STARTUP);
        fixture.listener.onLogin(fixture.player);
        Player stale = fixture.player;
        fixture.reconnect();
        fixture.listener.onLogin(stale);
        fixture.runTasks();
        assertNoEvidence(fixture);
        assertEquals(0, fixture.hooks);

        fixture.listener.onLogin(fixture.player);
        fixture.runTasks();
        assertEquals(1, fixture.hooks);
        assertTrue(fixture.authentication.current(fixture.playerId, NOW).isPresent());
    }

    @Test
    void providerDisableAndLostConfigurationProofRevokeExistingEvidence() {
        Fixture disabled = authenticatedFixture();
        disabled.listener.onProviderDisable(new PluginDisableEvent(disabled.provider));
        assertTrue(disabled.proof.revoked);
        assertNoEvidence(disabled);

        Fixture invalidProfile = authenticatedFixture();
        invalidProfile.proof.profileValid = false;
        assertNoEvidence(invalidProfile);
    }

    @Test
    void providerProofExceptionFailsClosedAndCannotReachRecoveryHook() {
        Fixture fixture = authenticatedFixture();
        fixture.proof.throwOnAuthenticated = true;
        assertNoEvidence(fixture);
        assertEquals(1, fixture.hooks);
    }

    private static Fixture authenticatedFixture() {
        Fixture fixture = new Fixture();
        fixture.load(ServerLoadEvent.LoadType.STARTUP);
        fixture.listener.onLogin(fixture.player);
        fixture.runTasks();
        assertEquals(1, fixture.hooks);
        assertTrue(fixture.authentication.current(fixture.playerId, NOW).isPresent());
        return fixture;
    }

    private static void assertNoEvidence(Fixture fixture) {
        assertTrue(fixture.authentication.current(fixture.playerId, NOW).isEmpty());
    }

    private static final class Fixture {
        private final UUID playerId = UUID.randomUUID();
        private final ConnectionRegistry connections = new ConnectionRegistry();
        private final AuthenticationRegistry authentication = new AuthenticationRegistry();
        private final AtomicBoolean authenticated = new AtomicBoolean(true);
        private final List<Runnable> playerTasks = new ArrayList<>();
        private final FakeProof proof = new FakeProof(authenticated);
        private Player player;
        private boolean online = true;
        private final Plugin provider = proxy(Plugin.class, (method, args) -> null);
        private final Server server = proxy(Server.class, (method, args) -> switch (method) {
            case "getPlayer" -> args[0].equals(playerId) && online ? player : null;
            default -> null;
        });
        private final Plugin owner = proxy(Plugin.class, (method, args) -> switch (method) {
            case "getServer" -> server;
            case "getLogger" -> Logger.getLogger("authme-test");
            default -> null;
        });
        private int hooks;
        private final AuthMeAuthenticationListener listener = new AuthMeAuthenticationListener(owner, provider,
                connections, authentication, Clock.fixed(NOW, ZoneOffset.UTC), ignored -> hooks++, proof);

        private Fixture() { reconnect(); }

        private void reconnect() {
            EntityScheduler scheduler = proxy(EntityScheduler.class, (method, args) -> {
                if (!method.equals("run")) return null;
                @SuppressWarnings("unchecked")
                Consumer<ScheduledTask> task = (Consumer<ScheduledTask>) args[1];
                playerTasks.add(() -> task.accept(null));
                return null;
            });
            Player replacement = proxy(Player.class, (method, args) -> switch (method) {
                case "getUniqueId" -> playerId;
                case "isOnline" -> online;
                case "getScheduler" -> scheduler;
                default -> null;
            });
            if (player != null) {
                online = false;
                connections.end(player);
                online = true;
            }
            player = replacement;
            connections.begin(player, NOW);
        }

        private void load(ServerLoadEvent.LoadType type) { listener.onServerLoad(new ServerLoadEvent(type)); }

        private void runTasks() {
            List.copyOf(playerTasks).forEach(Runnable::run);
            playerTasks.clear();
        }
    }

    private static final class FakeProof implements AuthMeAuthenticationListener.Proof {
        private boolean profileValid;
        private boolean revoked;
        private boolean throwOnAuthenticated;
        private int establishCalls;

        @Override public boolean establish() { establishCalls++; profileValid = true; revoked = false; return true; }
        @Override public boolean valid() { return profileValid && !revoked; }
        @Override public boolean authenticated(Player player) {
            if (throwOnAuthenticated) throw new IllegalStateException("synthetic proof failure");
            return authenticated.get();
        }
        private final AtomicBoolean authenticated;
        private FakeProof(AtomicBoolean authenticated) { this.authenticated = authenticated; }
        @Override public void revoke() { revoked = true; }
    }

    private interface Invocation { Object call(String method, Object[] args); }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> self == args[0];
                    case "hashCode" -> System.identityHashCode(self);
                    case "toString" -> type.getSimpleName() + " fixture";
                    default -> null;
                };
            }
            return invocation.call(method.getName(), args == null ? new Object[0] : args);
        }));
    }

}
