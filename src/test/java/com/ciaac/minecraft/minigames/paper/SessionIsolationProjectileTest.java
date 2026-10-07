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
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class SessionIsolationProjectileTest {
    @Test
    void projectileLaunchIsBlockedOnlyForIsolatedSumoAndHotPotatoSessions() {
        assertCancelled(GameKey.KNOCKBACK_SUMO, true);
        assertCancelled(GameKey.HOT_POTATO, true);
        assertCancelled(GameKey.ARENA, false);
    }

    private static void assertCancelled(GameKey game, boolean expected) {
        UUID playerId = UUID.randomUUID();
        Player player = proxy(Player.class, method -> method.equals("getUniqueId") ? playerId : null);
        Projectile projectile = proxy(Projectile.class,
                method -> method.equals("getShooter") ? player : null);
        SessionRegistry sessions = new SessionRegistry();
        PlayerSession session = new PlayerSession(UUID.randomUUID(), UUID.randomUUID(), playerId, game,
                Instant.parse("2026-10-04T12:00:00Z"));
        session.transition(UUID.randomUUID(), SessionPhase.REQUESTED, SessionPhase.SNAPSHOTTING,
                session.updatedAt().plusSeconds(1), "TEST");
        session.transition(UUID.randomUUID(), SessionPhase.SNAPSHOTTING, SessionPhase.SNAPSHOT_COMMITTED,
                session.updatedAt().plusSeconds(1), "TEST");
        session.transition(UUID.randomUUID(), SessionPhase.SNAPSHOT_COMMITTED, SessionPhase.PREPARING,
                session.updatedAt().plusSeconds(1), "TEST");
        session.transition(UUID.randomUUID(), SessionPhase.PREPARING, SessionPhase.ACTIVE,
                session.updatedAt().plusSeconds(1), "TEST");
        sessions.register(session);
        Plugin owner = proxy(Plugin.class, method -> switch (method) {
            case "getName", "namespace" -> "isolation-test";
            default -> null;
        });
        SessionIsolationListener listener = new SessionIsolationListener(owner, sessions,
                new AuthenticationRegistry(), new CombatPolicyRegistry(), new TemporaryItemTagger(owner),
                (ignoredPlayer, ignoredSession, ignoredViolation) -> { });

        ProjectileLaunchEvent event = new ProjectileLaunchEvent(projectile);
        listener.onProjectileLaunch(event);

        if (expected) assertTrue(event.isCancelled());
        else assertFalse(event.isCancelled());
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
