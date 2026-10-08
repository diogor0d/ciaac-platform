package com.ciaac.minecraft.minigames.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.CombatPolicyRegistry;
import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import com.ciaac.minecraft.minigames.runtime.SessionPhase;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class SessionIsolationGameModeTest {
    @Test
    void externalSurvivalAndSpectatorChangesAreBlockedDuringActiveAdventureSession() {
        Fixture fixture = new Fixture(GameKey.ELYTRA_RINGS, SessionPhase.ACTIVE, ignored -> GameMode.ADVENTURE);

        assertCancelled(fixture, GameMode.SURVIVAL, true);
        assertCancelled(fixture, GameMode.SPECTATOR, true);
    }

    @Test
    void preparingSessionAlsoOwnsItsTemporaryMode() {
        Fixture fixture = new Fixture(GameKey.ELYTRA_RINGS, SessionPhase.PREPARING, ignored -> GameMode.ADVENTURE);

        assertCancelled(fixture, GameMode.SURVIVAL, true);
    }

    @Test
    void buildBattleCanDynamicallyOwnCreativeThenAdventure() {
        AtomicReference<GameMode> expected = new AtomicReference<>(GameMode.CREATIVE);
        Fixture fixture = new Fixture(GameKey.BUILD_BATTLE, SessionPhase.ACTIVE, ignored -> expected.get());

        assertCancelled(fixture, GameMode.CREATIVE, false);
        assertCancelled(fixture, GameMode.SURVIVAL, true);

        expected.set(GameMode.ADVENTURE);
        assertCancelled(fixture, GameMode.ADVENTURE, false);
        assertCancelled(fixture, GameMode.CREATIVE, true);
    }

    @Test
    void unavailableModePolicyFailsClosed() {
        Fixture throwing = new Fixture(GameKey.ELYTRA_RINGS, SessionPhase.ACTIVE,
                ignored -> { throw new IllegalStateException("policy unavailable"); });
        Fixture nullMode = new Fixture(GameKey.ELYTRA_RINGS, SessionPhase.ACTIVE, ignored -> null);

        assertCancelled(throwing, GameMode.SURVIVAL, true);
        assertCancelled(nullMode, GameMode.SURVIVAL, true);
    }

    @Test
    void restoringAllowsOriginalCreativeModeAndExistingCancellationIsPreserved() {
        Fixture restoring = new Fixture(GameKey.BUILD_BATTLE, SessionPhase.RESTORING,
                ignored -> GameMode.ADVENTURE);
        assertCancelled(restoring, GameMode.CREATIVE, false);

        Fixture active = new Fixture(GameKey.ELYTRA_RINGS, SessionPhase.ACTIVE,
                ignored -> GameMode.ADVENTURE);
        assertCancelled(active, GameMode.ADVENTURE, false, true);
    }

    @Test
    void otherLifecyclePhasesDoNotOwnOrBlockGameModeChanges() {
        for (SessionPhase phase : new SessionPhase[] {SessionPhase.SNAPSHOTTING,
                SessionPhase.SNAPSHOT_COMMITTED, SessionPhase.FINISHING,
                SessionPhase.RECOVERING, SessionPhase.CLOSED}) {
            Fixture fixture = new Fixture(GameKey.ELYTRA_RINGS, phase, ignored -> GameMode.ADVENTURE);
            assertCancelled(fixture, GameMode.SURVIVAL, false);
        }
    }

    @Test
    void otherGamesKeepTheirOwnEliminationAndSpectatorModes() {
        for (GameKey game : GameKey.values()) {
            if (game == GameKey.ELYTRA_RINGS || game == GameKey.BUILD_BATTLE) continue;
            Fixture fixture = new Fixture(game, SessionPhase.ACTIVE, ignored -> GameMode.ADVENTURE);
            assertCancelled(fixture, GameMode.SPECTATOR, false);
        }
    }

    private static void assertCancelled(Fixture fixture, GameMode requested, boolean expectedCancelled) {
        assertCancelled(fixture, requested, expectedCancelled, false);
    }

    private static void assertCancelled(
            Fixture fixture, GameMode requested, boolean expectedCancelled, boolean initiallyCancelled) {
        PlayerGameModeChangeEvent event = new PlayerGameModeChangeEvent(fixture.player, requested);
        event.setCancelled(initiallyCancelled);

        fixture.listener.onGameModeChange(event);

        if (expectedCancelled || initiallyCancelled) assertTrue(event.isCancelled());
        else assertFalse(event.isCancelled());
    }

    private static final class Fixture {
        private final UUID playerId = UUID.randomUUID();
        private final Player player = proxy(Player.class, method -> method.equals("getUniqueId") ? playerId : null);
        private final SessionRegistry sessions = new SessionRegistry();
        private final SessionIsolationListener listener;

        private Fixture(GameKey game, SessionPhase phase,
                        java.util.function.Function<PlayerSession, GameMode> expectedMode) {
            PlayerSession session = session(playerId, game, phase);
            sessions.register(session);
            Plugin owner = proxy(Plugin.class, method -> switch (method) {
                case "getName", "namespace" -> "isolation-test";
                default -> null;
            });
            listener = new SessionIsolationListener(owner, sessions, new AuthenticationRegistry(),
                    new CombatPolicyRegistry(), new TemporaryItemTagger(owner),
                    (ignoredPlayer, ignoredSession, ignoredViolation) -> { }, expectedMode);
        }
    }

    private static PlayerSession session(UUID playerId, GameKey game, SessionPhase target) {
        Instant at = Instant.parse("2026-10-08T12:00:00Z");
        PlayerSession session = new PlayerSession(UUID.randomUUID(), UUID.randomUUID(), playerId, game, at);
        if (target == SessionPhase.CLOSED) {
            transition(session, SessionPhase.REQUESTED, SessionPhase.CLOSED, at);
            return session;
        }
        if (target == SessionPhase.REQUESTED) return session;
        transition(session, SessionPhase.REQUESTED, SessionPhase.SNAPSHOTTING, at);
        if (target == SessionPhase.SNAPSHOTTING) return session;
        transition(session, SessionPhase.SNAPSHOTTING, SessionPhase.SNAPSHOT_COMMITTED, at);
        if (target == SessionPhase.SNAPSHOT_COMMITTED) return session;
        transition(session, SessionPhase.SNAPSHOT_COMMITTED, SessionPhase.PREPARING, at);
        if (target == SessionPhase.ACTIVE || target == SessionPhase.FINISHING || target == SessionPhase.RESTORING) {
            transition(session, SessionPhase.PREPARING, SessionPhase.ACTIVE, at);
        }
        if (target == SessionPhase.FINISHING || target == SessionPhase.RESTORING) {
            transition(session, SessionPhase.ACTIVE, SessionPhase.FINISHING, at);
        }
        if (target == SessionPhase.RESTORING) transition(session, SessionPhase.FINISHING, SessionPhase.RESTORING, at);
        if (target == SessionPhase.RECOVERING) {
            transition(session, SessionPhase.PREPARING, SessionPhase.ACTIVE, at);
            transition(session, SessionPhase.ACTIVE, SessionPhase.RECOVERING, at);
        }
        return session;
    }

    private static void transition(PlayerSession session, SessionPhase from, SessionPhase to, Instant at) {
        session.transition(UUID.randomUUID(), from, to, at, "TEST");
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.util.function.Function<String, Object> values) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("equals")) return proxy == arguments[0];
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    Object value = values.apply(method.getName());
                    if (value != null) return value;
                    Class<?> result = method.getReturnType();
                    if (!result.isPrimitive()) return null;
                    if (result == boolean.class) return false;
                    if (result == char.class) return '\0';
                    return 0;
                });
    }
}
