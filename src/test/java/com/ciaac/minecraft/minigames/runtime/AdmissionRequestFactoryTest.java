package com.ciaac.minecraft.minigames.runtime;

import static org.junit.jupiter.api.Assertions.*;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

final class AdmissionRequestFactoryTest {
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private final UUID playerId = UUID.randomUUID();
    private final Player player = player(playerId);
    private final ConnectionRegistry connections = new ConnectionRegistry();
    private final AuthenticationRegistry authentication = new AuthenticationRegistry();
    private final ConnectionRegistry.Connection connection = connections.begin(player, NOW.minusSeconds(2));
    private final AdmissionRequestFactory factory = new AdmissionRequestFactory(
            connections, authentication, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void connectedButUnauthenticatedPlayerCannotObtainAdmissionIdentity() {
        assertTrue(factory.create(player, GameKey.ARENA, UUID.randomUUID()).isEmpty());
    }

    @Test
    void matchingCurrentAuthenticationPreservesExactIdentity() {
        authenticate(NOW.plusSeconds(60));
        UUID matchId = UUID.randomUUID();
        AdmissionRequest request = factory.create(player, GameKey.ARENA, matchId).orElseThrow();
        assertEquals(playerId, request.playerId());
        assertEquals(connection.id(), request.connectionId());
        assertEquals(matchId, request.matchId());
        assertEquals(NOW, request.requestedAt());
    }

    @Test
    void authenticationFromPreviousConnectionCannotAuthorizeReconnect() {
        authenticate(NOW.plusSeconds(60));
        Player replacement = player(playerId);
        connections.begin(replacement, NOW);
        assertTrue(factory.create(replacement, GameKey.ARENA, UUID.randomUUID()).isEmpty());
        assertTrue(factory.create(player, GameKey.ARENA, UUID.randomUUID()).isEmpty());
    }

    @Test
    void authenticationAtExpiryCannotAuthorizeAdmission() {
        authenticate(NOW);
        assertTrue(factory.create(player, GameKey.ARENA, UUID.randomUUID()).isEmpty());
    }

    @Test
    void invalidatedAuthenticationCannotAuthorizeAdmission() {
        authenticate(NOW.plusSeconds(60));
        authentication.invalidatePlayer(playerId);
        assertTrue(factory.create(player, GameKey.ARENA, UUID.randomUUID()).isEmpty());
    }

    private void authenticate(Instant expiresAt) {
        authentication.authenticated(new AuthenticatedSession(UUID.randomUUID(), playerId,
                connection.id(), NOW.minusSeconds(1), expiresAt));
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (self, method, arguments) -> {
                    if (method.getName().equals("getUniqueId")) return id;
                    throw new AssertionError("Unexpected player method: " + method.getName());
                });
    }
}
