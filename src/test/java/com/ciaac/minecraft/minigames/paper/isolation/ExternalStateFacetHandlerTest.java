package com.ciaac.minecraft.minigames.paper.isolation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.core.GameKey;
import java.time.Instant;
import java.util.UUID;
import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.bukkit.entity.Player;

class ExternalStateFacetHandlerTest {
    private static final UUID PLAYER = UUID.randomUUID();
    private static final PlayerStateOperation CAPTURE = new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE,
            new UUID(0,1), new UUID(0,1), new UUID(0,2), new UUID(0,3), new UUID(0,4), PLAYER,
            new UUID(0,5), new UUID(0,5), GameKey.ARENA, Instant.parse("2026-10-04T01:00:00.123456789Z"));
    private static final Player PLAYER_ENTITY = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(),
            new Class<?>[] {Player.class}, (proxy, method, args) -> method.getName().equals("getUniqueId") ? PLAYER : null);
    private static final PlayerStateOperation RESTORE = operation(PlayerStateOperation.Kind.RESTORE, PLAYER,
            CAPTURE.sessionId(), CAPTURE.snapshotId(), CAPTURE.capturedConnectionId());

    private static PlayerStateOperation operation(PlayerStateOperation.Kind kind, UUID player, UUID session, UUID snapshot, UUID capturedEpoch) {
        return new PlayerStateOperation(kind, UUID.randomUUID(), CAPTURE.captureOperationId(), snapshot,
                session, CAPTURE.matchId(), player, capturedEpoch, UUID.randomUUID(), CAPTURE.game(), CAPTURE.capturedAt());
    }

    @Test
    void rejectsAdapterIdentityChangeBeforeExternalRestore() {
        MutablePort port = new MutablePort();
        ExternalStateFacetHandler handler = new ExternalStateFacetHandler(port);

        byte[] envelope = handler.capture(PLAYER_ENTITY, CAPTURE);
        port.id = "changed-adapter";
        port.version = 2;

        assertThrows(IllegalStateException.class, () -> handler.validateRestore(RESTORE, envelope));
        assertThrows(IllegalStateException.class, () -> handler.restore(PLAYER_ENTITY, RESTORE, envelope));

        assertEquals(0, port.restoredVersion);
        assertNull(port.restoredPayload);
    }

    @Test
    void freezesAndValidatesPortGameCatalogAndRejectsUnsupportedGameBeforeCapture() {
        MutablePort port = new MutablePort();
        port.games = Set.of(GameKey.ARENA);
        ExternalStateFacetHandler handler = new ExternalStateFacetHandler(port);

        assertEquals(Set.of(GameKey.ARENA), handler.supportedGames());
        assertThrows(IllegalStateException.class, () -> handler.preflight(GameKey.BUILD_BATTLE));
        PlayerStateOperation otherGameCapture = new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE,
                CAPTURE.operationId(), CAPTURE.captureOperationId(), CAPTURE.snapshotId(), CAPTURE.sessionId(),
                CAPTURE.matchId(), CAPTURE.playerId(), CAPTURE.capturedConnectionId(),
                CAPTURE.connectionId(), GameKey.BUILD_BATTLE, CAPTURE.capturedAt());
        assertThrows(IllegalStateException.class, () -> handler.capture(PLAYER_ENTITY, otherGameCapture));
        assertEquals(0, port.captureCalls);

        port.games = Set.copyOf(EnumSet.allOf(GameKey.class));
        assertThrows(IllegalStateException.class, () -> handler.preflight(GameKey.ARENA));
    }

    @Test
    void validatesEnvelopeWithoutCallingExternalRestore() {
        MutablePort port = new MutablePort();
        ExternalStateFacetHandler handler = new ExternalStateFacetHandler(port);

        byte[] envelope = handler.capture(PLAYER_ENTITY, CAPTURE);
        handler.validateRestore(RESTORE, envelope);

        assertEquals(0, port.restoredVersion);
        assertNull(port.restoredPayload);
    }

    @Test
    void preflightFailsClosedWhenAdapterBecomesUnavailable() {
        MutablePort port = new MutablePort();
        ExternalStateFacetHandler handler = new ExternalStateFacetHandler(port);
        port.available = false;

        assertThrows(IllegalStateException.class, handler::preflight);
    }

    @Test
    void rejectsInvalidProviderPayloadBeforeCaptureOrExternalRestore() {
        MutablePort port = new MutablePort();
        ExternalStateFacetHandler handler = new ExternalStateFacetHandler(port);
        byte[] envelope = handler.capture(PLAYER_ENTITY, CAPTURE);
        port.rejectPayload = true;

        assertThrows(IllegalArgumentException.class, () -> handler.capture(PLAYER_ENTITY, CAPTURE));
        assertThrows(IllegalArgumentException.class, () -> handler.validateRestore(RESTORE, envelope));
        assertThrows(IllegalArgumentException.class, () -> handler.restore(PLAYER_ENTITY, RESTORE, envelope));
        assertNull(port.restoredPayload);
    }

    @Test
    void legacyProviderWithoutPayloadValidationFailsClosed() {
        ExternalStateFacetPort legacy = new ExternalStateFacetPort() {
            @Override public String id() { return "legacy-adapter"; }
            @Override public int snapshotVersion() { return 1; }
            @Override public Set<PlayerStateFacet> facets() { return Set.of(PlayerStateFacet.ECONOMY); }
            @Override public boolean available() { return true; }
            @Override public byte[] capture(Player player) { return new byte[] {1}; }
            @Override public void enterTemporaryState(Player player) { throw new AssertionError("must not mutate"); }
            @Override public void purgeTemporaryState(Player player) { throw new AssertionError("must not mutate"); }
            @Override public void restore(Player player, int version, byte[] payload) { throw new AssertionError("must not mutate"); }
        };

        assertThrows(IllegalArgumentException.class, () -> new ExternalStateFacetHandler(legacy));
    }

    @Test
    void bindsExternalEnvelopeToItsSnapshotPlayerSessionAndCaptureEpoch() {
        MutablePort port = new MutablePort();
        ExternalStateFacetHandler handler = new ExternalStateFacetHandler(port);
        byte[] envelope = handler.capture(PLAYER_ENTITY, CAPTURE);
        for (PlayerStateOperation foreign : java.util.List.of(
                operation(PlayerStateOperation.Kind.RESTORE, UUID.randomUUID(), CAPTURE.sessionId(), CAPTURE.snapshotId(), CAPTURE.capturedConnectionId()),
                operation(PlayerStateOperation.Kind.RESTORE, PLAYER, UUID.randomUUID(), CAPTURE.snapshotId(), CAPTURE.capturedConnectionId()),
                operation(PlayerStateOperation.Kind.RESTORE, PLAYER, CAPTURE.sessionId(), UUID.randomUUID(), CAPTURE.capturedConnectionId()),
                operation(PlayerStateOperation.Kind.RESTORE, PLAYER, CAPTURE.sessionId(), CAPTURE.snapshotId(), UUID.randomUUID()),
                operation(PlayerStateOperation.Kind.RESTORE, PLAYER, CAPTURE.sessionId(), CAPTURE.snapshotId(), null))) {
            assertThrows(IllegalStateException.class, () -> handler.validateRestore(foreign, envelope));
        }
        assertEquals(0, port.restoredVersion);
        handler.restore(PLAYER_ENTITY, RESTORE, envelope);
        assertEquals(RESTORE, port.restoredContext);
        assertEquals(1, port.restoredVersion);
    }

    @Test
    void rejectsContextFreeCallsAndWrongLivePlayerBeforeProviderMutation() {
        MutablePort port = new MutablePort();
        ExternalStateFacetHandler handler = new ExternalStateFacetHandler(port);
        byte[] envelope = handler.capture(PLAYER_ENTITY, CAPTURE);
        PlayerStateOperation foreign = operation(PlayerStateOperation.Kind.RESTORE, UUID.randomUUID(),
                CAPTURE.sessionId(), CAPTURE.snapshotId(), CAPTURE.capturedConnectionId());
        assertThrows(IllegalStateException.class, () -> handler.restore(PLAYER_ENTITY, foreign, envelope));
        assertThrows(IllegalStateException.class, () -> handler.capture(PLAYER_ENTITY));
        assertThrows(IllegalStateException.class, () -> handler.enterTemporaryState(PLAYER_ENTITY));
        assertThrows(IllegalStateException.class, () -> handler.purgeTemporaryState(PLAYER_ENTITY));
        assertThrows(IllegalStateException.class, () -> handler.restore(PLAYER_ENTITY, envelope));
        assertEquals(0, port.restoredVersion);
    }

    private static final class MutablePort implements ExternalStateFacetPort {
        private String id = "economy-adapter";
        private int version = 1;
        private boolean available = true;
        private int restoredVersion;
        private byte[] restoredPayload;
        private boolean rejectPayload;
        private int captureCalls;
        private Set<GameKey> games = Set.copyOf(EnumSet.allOf(GameKey.class));
        private PlayerStateOperation restoredContext;
        @Override public int contractVersion() { return 2; }

        @Override public String id() { return id; }
        @Override public int snapshotVersion() { return version; }
        @Override public Set<PlayerStateFacet> facets() { return Set.of(PlayerStateFacet.ECONOMY); }
        @Override public Set<GameKey> supportedGames() { return games; }
        @Override public boolean available() { return available; }
        @Override public byte[] capture(Player player, PlayerStateOperation context) {
            captureCalls++;
            return new byte[] {1, 2, 3};
        }
        @Override public void validateRestore(PlayerStateOperation context, int snapshotVersion, byte[] payload) {
            if (rejectPayload) throw new IllegalArgumentException("malformed provider payload");
            if (snapshotVersion != version || !java.util.Arrays.equals(payload, new byte[] {1, 2, 3})) {
                throw new IllegalArgumentException("unsupported provider payload");
            }
        }
        @Override public void enterTemporaryState(Player player, PlayerStateOperation context) {}
        @Override public void purgeTemporaryState(Player player, PlayerStateOperation context) {}
        @Override public void restore(Player player, PlayerStateOperation context, int snapshotVersion, byte[] payload) {
            restoredContext = context;
            restoredVersion = snapshotVersion;
            restoredPayload = payload.clone();
        }
    }
}
