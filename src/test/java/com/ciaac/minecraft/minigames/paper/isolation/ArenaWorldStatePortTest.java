package com.ciaac.minecraft.minigames.paper.isolation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.persistence.AuditEvent;
import com.ciaac.minecraft.minigames.persistence.AuditRepository;
import com.ciaac.minecraft.minigames.persistence.ArenaWorldLedger;
import com.ciaac.minecraft.minigames.persistence.ExternalOperationJournal;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArenaWorldStatePortTest {
    private static final UUID PLAYER_ID = uuid(1);
    private static final UUID CAPTURE_EPOCH = uuid(2);
    private static final Instant CAPTURED_AT = Instant.parse("2026-10-04T12:00:00Z");

    @TempDir Path temporary;

    @BeforeEach
    void useCanonicalTemporaryDirectory() throws Exception {
        temporary = temporary.toRealPath();
    }

    @Test
    void unreadyPortAdvertisesOnlyReviewedGamesAndRejectsOperationsBeforeReadingPlayer() {
        try (Environment environment = environment("unready")) {
            PlayerStateOperation capture = capture(GameKey.ARENA);
            PlayerStateOperation buildBattleCapture = capture(GameKey.BUILD_BATTLE);
            AtomicInteger playerLookups = new AtomicInteger();
            Player player = player(PLAYER_ID, playerLookups);

            assertEquals("paper-26.2-84-arena-world-v1", environment.port.id());
            assertEquals(2, environment.port.contractVersion());
            assertEquals(1, environment.port.snapshotVersion());
            assertEquals(Set.of(PlayerStateFacet.TEMPORARY_WORLD_BLOCKS_AND_ENTITIES), environment.port.facets());
            assertEquals(Set.of(GameKey.ARENA, GameKey.KNOCKBACK_SUMO, GameKey.HOT_POTATO,
                    GameKey.CHECKPOINT_PARKOUR, GameKey.ARCHERY_RANGE), environment.port.supportedGames());
            assertFalse(environment.port.available());

            assertThrows(IllegalStateException.class, () -> environment.port.capture(player, capture));
            assertThrows(IllegalStateException.class, () -> environment.port.capture(player, buildBattleCapture));
            assertThrows(IllegalStateException.class,
                    () -> environment.port.validateRestore(phase(capture, PlayerStateOperation.Kind.RESTORE), 1, new byte[0]));
            assertThrows(IllegalStateException.class,
                    () -> environment.port.enterTemporaryState(player, phase(capture, PlayerStateOperation.Kind.ENTER)));
            assertThrows(IllegalStateException.class,
                    () -> environment.port.purgeTemporaryState(player, phase(capture, PlayerStateOperation.Kind.PURGE)));
            assertThrows(IllegalStateException.class,
                    () -> environment.port.restore(player, phase(capture, PlayerStateOperation.Kind.RESTORE), 1, new byte[0]));

            assertEquals(0, playerLookups.get());
            assertEquals(0, environment.audit.events.size());
            assertThrows(IllegalStateException.class, () -> environment.ledger.requireLease(capture));
            assertThrows(IllegalStateException.class,
                    () -> environment.journal.committedResult(environment.port.id(), 1, capture, new byte[0]));
        }
    }

    @Test
    void lifecycleFailureIsStickyAndLifecycleChangesRequireThePrimaryThread() {
        try (Environment environment = environment("lifecycle-failure")) {
            environment.serverState.primaryThread = false;
            assertThrows(IllegalStateException.class, environment.port::lifecycleReady);
            assertThrows(IllegalStateException.class, environment.port::lifecycleFailed);
            assertFalse(environment.port.available());

            environment.serverState.primaryThread = true;
            environment.port.lifecycleFailed();
            assertFalse(environment.port.available());
            assertThrows(IllegalStateException.class, environment.port::lifecycleReady);
            assertFalse(environment.port.available());

            AtomicInteger playerLookups = new AtomicInteger();
            assertThrows(IllegalStateException.class,
                    () -> environment.port.capture(player(PLAYER_ID, playerLookups), capture(GameKey.ARENA)));
            assertEquals(0, playerLookups.get());
            assertEquals(0, environment.audit.events.size());
        }
    }

    private Environment environment(String name) {
        ServerState serverState = new ServerState();
        Server server = proxy(Server.class, serverState::invoke);
        Plugin plugin = proxy(Plugin.class, (instance, method, arguments) -> switch (method.getName()) {
            case "getServer" -> server;
            case "getName" -> "CIAACPlatform";
            case "namespace" -> "ciaacplatform";
            default -> defaultValue(method.getReturnType());
        });
        ArenaWorldLedger ledger = new ArenaWorldLedger(temporary.resolve(name + "-ledger"));
        ExternalOperationJournal journal = new ExternalOperationJournal(temporary.resolve(name + "-journal"));
        RecordingAudit audit = new RecordingAudit();
        var ownership = new ArenaProjectileOwnership(plugin, ledger);
        ArenaWorldStatePort port = new ArenaWorldStatePort(server, new ProtectedRegionRegistry(), ledger,
                ownership, journal, audit);
        return new Environment(serverState, port, ledger, journal, audit);
    }

    private static PlayerStateOperation capture(GameKey game) {
        UUID captureId = uuid(10);
        return new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE, captureId, captureId,
                uuid(11), uuid(12), uuid(13), PLAYER_ID, CAPTURE_EPOCH, CAPTURE_EPOCH, game, CAPTURED_AT);
    }

    private static PlayerStateOperation phase(PlayerStateOperation capture, PlayerStateOperation.Kind kind) {
        UUID connectionId = kind == PlayerStateOperation.Kind.ENTER ? CAPTURE_EPOCH : uuid(30 + kind.ordinal());
        return new PlayerStateOperation(kind, uuid(20 + kind.ordinal()), capture.captureOperationId(),
                capture.snapshotId(), capture.sessionId(), capture.matchId(), capture.playerId(),
                capture.capturedConnectionId(), connectionId, capture.game(), capture.capturedAt());
    }

    private static Player player(UUID id, AtomicInteger uniqueIdReads) {
        return proxy(Player.class, (instance, method, arguments) -> {
            if (method.getName().equals("getUniqueId")) {
                uniqueIdReads.incrementAndGet();
                return id;
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        if (type == char.class) return '\0';
        throw new IllegalArgumentException("Unsupported primitive type: " + type);
    }

    private static final class Environment implements AutoCloseable {
        private final ServerState serverState;
        private final ArenaWorldStatePort port;
        private final ArenaWorldLedger ledger;
        private final ExternalOperationJournal journal;
        private final RecordingAudit audit;

        private Environment(ServerState serverState, ArenaWorldStatePort port, ArenaWorldLedger ledger,
                ExternalOperationJournal journal, RecordingAudit audit) {
            this.serverState = serverState;
            this.port = port;
            this.ledger = ledger;
            this.journal = journal;
            this.audit = audit;
        }

        @Override public void close() {
            journal.close();
            ledger.close();
        }
    }

    private static final class ServerState {
        private boolean primaryThread = true;

        private Object invoke(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "isPrimaryThread" -> primaryThread;
                case "toString" -> "test-server";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> defaultValue(method.getReturnType());
            };
        }
    }

    private static final class RecordingAudit implements AuditRepository {
        private final List<AuditEvent> events = new ArrayList<>();

        @Override public boolean append(AuditEvent event) {
            if (events.contains(event)) return false;
            events.add(event);
            return true;
        }
    }
}
