package com.ciaac.minecraft.minigames.paper.isolation;

import static org.junit.jupiter.api.Assertions.*;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.persistence.AuditEvent;
import com.ciaac.minecraft.minigames.persistence.ExternalOperationJournal;
import com.ciaac.minecraft.minigames.persistence.SqliteAuditRepository;
import com.ciaac.minecraft.minigames.persistence.SqliteDatabase;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

class ReadGuardedExternalStatePortTest {
    @TempDir Path temporary;

    @BeforeEach void canonicalTemporaryDirectory() throws Exception {
        temporary = temporary.toRealPath();
    }
    private final UUID playerId = UUID.randomUUID();
    private final UUID epoch = UUID.randomUUID();
    private final UUID captureId = UUID.randomUUID();
    private final PlayerStateOperation capture = new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE,
            captureId, captureId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            playerId, epoch, epoch, GameKey.ARENA, Instant.EPOCH);

    // Capture's operation ID and capture ID must be equal.
    private PlayerStateOperation capture() {
        return new PlayerStateOperation(capture.kind(), capture.captureOperationId(), capture.captureOperationId(),
                capture.snapshotId(), capture.sessionId(), capture.matchId(), playerId, epoch, epoch, capture.game(), capture.capturedAt());
    }

    @Test void completeReadOnlyLifecycleReplaysAcrossReopenAndAuthenticatedRecoveryEpoch() {
        Authority authority = new Authority();
        Map<UUID, AuditEvent> audit = new LinkedHashMap<>();
        var ctx = capture();
        var enter = phase(ctx, PlayerStateOperation.Kind.ENTER, epoch);
        var purge = phase(ctx, PlayerStateOperation.Kind.PURGE, UUID.randomUUID());
        var restore = phase(ctx, PlayerStateOperation.Kind.RESTORE, purge.connectionId());
        byte[] snapshot;
        try (var journal = new ExternalOperationJournal(temporary.resolve("journal"))) {
            var port = port(authority, journal, audit);
            snapshot = port.capture(player(playerId), ctx);
            port.validateRestore(ctx, 1, snapshot);
            port.enterTemporaryState(player(playerId), enter);
            assertArrayEquals(new byte[] {1, 2}, authority.value);
        }
        try (var journal = new ExternalOperationJournal(temporary.resolve("journal"))) {
            var port = port(authority, journal, audit);
            port.enterTemporaryState(player(playerId), enter);
            port.purgeTemporaryState(player(playerId), purge);
            port.restore(player(playerId), restore, 1, snapshot);
            port.restore(player(playerId), restore, 1, snapshot);
            assertEquals(4, audit.size());
            assertArrayEquals(new byte[] {1, 2}, authority.value);
        }
    }

    @Test void externalDriftFailsBeforeExitAndNeverOverwritesTheNewBalance() {
        Authority authority = new Authority();
        var ctx = capture();
        try (var journal = new ExternalOperationJournal(temporary.resolve("journal"))) {
            var port = port(authority, journal, new LinkedHashMap<>());
            byte[] snapshot = port.capture(player(playerId), ctx);
            authority.value = new byte[] {7, 8};
            var restore = phase(ctx, PlayerStateOperation.Kind.RESTORE, UUID.randomUUID());
            assertThrows(IllegalStateException.class, () -> port.validateRestore(restore, 1, snapshot));
            assertThrows(IllegalStateException.class, () -> port.restore(player(playerId), restore, 1, snapshot));
            assertThrows(IllegalStateException.class, () -> port.capture(player(playerId), ctx));
            assertArrayEquals(new byte[] {7, 8}, authority.value);
        }
    }

    @Test void readOnlyPendingCheckpointCanRepeatValidationButCommittedReplayStillDetectsNewDrift() {
        Authority authority = new Authority();
        var ctx = capture();
        try (var journal = new ExternalOperationJournal(temporary.resolve("journal"))) {
            var port = port(authority, journal, new LinkedHashMap<>());
            byte[] snapshot = port.capture(player(playerId), ctx);
            var enter = phase(ctx, PlayerStateOperation.Kind.ENTER, epoch);
            journal.begin(port.id(), 1, enter, snapshot);
            port.enterTemporaryState(player(playerId), enter);
            assertEquals(ExternalOperationJournal.State.COMMITTED, journal.begin(port.id(), 1, enter, snapshot));
            authority.value = new byte[] {9, 10};
            assertThrows(IllegalStateException.class, () -> port.enterTemporaryState(player(playerId), enter));
        }
    }

    @Test void missingCaptureConflictingPayloadWrongPlayerAndProviderLossFailClosed() {
        Authority authority = new Authority();
        var ctx = capture();
        try (var journal = new ExternalOperationJournal(temporary.resolve("journal"))) {
            var port = port(authority, journal, new LinkedHashMap<>());
            var restore = phase(ctx, PlayerStateOperation.Kind.RESTORE, epoch);
            assertThrows(IllegalStateException.class, () -> port.restore(player(playerId), restore, 1, new byte[] {1,2}));
            assertThrows(IllegalStateException.class, () -> port.capture(player(UUID.randomUUID()), ctx));
            assertEquals(0, authority.reads);
            byte[] snapshot = port.capture(player(playerId), ctx);
            assertThrows(IllegalStateException.class, () -> port.validateRestore(restore, 1, new byte[] {3,4}));
            assertThrows(IllegalArgumentException.class, () -> port.validateRestore(restore, 2, snapshot));
            authority.healthy = false;
            assertThrows(IllegalStateException.class, () -> port.restore(player(playerId), restore, 1, snapshot));
        }
    }

    @Test void auditFailureAfterCommitIsRepairableWithoutChangingTheRecordedTime() {
        Authority authority = new Authority();
        var ctx = capture();
        AtomicBoolean fail = new AtomicBoolean(true);
        try (var database = new SqliteDatabase(temporary, Path.of("audit.sqlite"));
             var journal = new ExternalOperationJournal(temporary.resolve("journal"))) {
            var audit = new SqliteAuditRepository(database);
            var port = new ReadGuardedExternalStatePort("test-guard", Set.of(PlayerStateFacet.ECONOMY), authority, journal,
                    event -> { if (fail.getAndSet(false)) throw new IllegalStateException("injected audit failure"); return audit.append(event); });
            assertThrows(IllegalStateException.class, () -> port.capture(player(playerId), ctx));
            Instant committed = journal.committedAt(port.id(), 1, ctx, new byte[0]);
            port.capture(player(playerId), ctx);
            port.capture(player(playerId), ctx);
            database.read(connection -> {
                try (var statement = connection.createStatement(); var row = statement.executeQuery(
                        "SELECT count(*), min(occurred_at) FROM mg_audit_event")) {
                    assertEquals(1, row.getInt(1));
                    assertEquals(committed.toString(), row.getString(2));
                }
                return null;
            });
        }
    }

    private ReadGuardedExternalStatePort port(Authority authority, ExternalOperationJournal journal, Map<UUID, AuditEvent> audit) {
        return new ReadGuardedExternalStatePort("test-guard", Set.of(PlayerStateFacet.ECONOMY), authority, journal, event -> {
            AuditEvent previous = audit.putIfAbsent(event.eventId(), event);
            if (previous != null) assertEquals(previous, event);
            return previous == null;
        });
    }

    private static PlayerStateOperation phase(PlayerStateOperation capture, PlayerStateOperation.Kind kind, UUID connection) {
        return new PlayerStateOperation(kind, UUID.randomUUID(), capture.operationId(), capture.snapshotId(), capture.sessionId(),
                capture.matchId(), capture.playerId(), capture.capturedConnectionId(), connection, capture.game(), capture.capturedAt());
    }

    private static Player player(UUID id) {
        return (Player)Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[] {Player.class},
                (proxy, method, args) -> method.getName().equals("getUniqueId") ? id : null);
    }

    private final class Authority implements ExternalStateAuthority {
        byte[] value = {1, 2};
        boolean healthy = true;
        int reads;
        @Override public boolean available() { return healthy; }
        @Override public byte[] read(UUID id) { assertEquals(playerId, id); reads++; return value.clone(); }
        @Override public void validate(UUID id, byte[] payload) {
            if (!id.equals(playerId) || payload.length != 2) throw new IllegalArgumentException("invalid state");
        }
    }
}
