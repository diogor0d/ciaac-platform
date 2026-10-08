package com.ciaac.minecraft.minigames.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ColorFloorWorldLedgerTest {
    @TempDir Path temporary;
    private static final byte[] MANIFEST = {10, 20, 30, 40};

    @BeforeEach void canonicalTemporaryDirectory() throws Exception { temporary = temporary.toRealPath(); }

    @Test void persistsDefensiveLeaseAndSupportsPerPlayerLifecycleAcrossReopen() {
        PlayerStateOperation first = operation(PlayerStateOperation.Kind.CAPTURE, 1, 10, 20, 30, GameKey.COLOR_FLOOR);
        PlayerStateOperation second = operation(PlayerStateOperation.Kind.CAPTURE, 2, 10, 21, 31, GameKey.COLOR_FLOOR);
        Path directory = temporary.resolve("leases");
        byte[] supplied = MANIFEST.clone();
        try (var ledger = new ColorFloorWorldLedger(directory)) {
            var captured = ledger.capture(first, supplied);
            supplied[0] = 99;
            byte[] returned = captured.manifest(); returned[1] = 88;
            assertArrayEquals(MANIFEST, captured.manifest());
            assertEquals(ColorFloorWorldLedger.Status.CAPTURED, ledger.capture(second, MANIFEST).status());
            assertEquals(ColorFloorWorldLedger.Status.ARMED, ledger.arm(operation(PlayerStateOperation.Kind.ENTER, 3, 10, 20, 30, GameKey.COLOR_FLOOR)).status());
            assertEquals(ColorFloorWorldLedger.Status.PURGING, ledger.beginPurge(operation(PlayerStateOperation.Kind.PURGE, 4, 10, 20, 30, GameKey.COLOR_FLOOR)).status());
            assertEquals(ColorFloorWorldLedger.Status.PURGED, ledger.markPurged(operation(PlayerStateOperation.Kind.PURGE, 5, 10, 20, 30, GameKey.COLOR_FLOOR)).status());
        }
        try (var ledger = new ColorFloorWorldLedger(directory)) {
            var restore = operation(PlayerStateOperation.Kind.RESTORE, 6, 10, 20, 30, GameKey.COLOR_FLOOR);
            assertArrayEquals(MANIFEST, ledger.requireLease(restore).manifest());
            assertEquals(ColorFloorWorldLedger.Status.RESTORED, ledger.markRestored(restore).status());
            assertEquals(ColorFloorWorldLedger.Status.RESTORED,
                    ledger.requireLease(operation(PlayerStateOperation.Kind.PURGE, 7, 10, 20, 30, GameKey.COLOR_FLOOR)).status());
        }
    }

    @Test void freezesOneManifestAndOneUnfinishedMatchForTheFacility() {
        Path directory = temporary.resolve("facility");
        PlayerStateOperation first = operation(PlayerStateOperation.Kind.CAPTURE, 1, 10, 20, 30, GameKey.COLOR_FLOOR);
        try (var ledger = new ColorFloorWorldLedger(directory)) {
            ledger.capture(first, MANIFEST);
            PlayerStateOperation sameMatch = operation(PlayerStateOperation.Kind.CAPTURE, 2, 10, 21, 31, GameKey.COLOR_FLOOR);
            assertThrows(IllegalStateException.class, () -> ledger.capture(sameMatch, new byte[] {1, 2}));
            PlayerStateOperation differentMatch = operation(PlayerStateOperation.Kind.CAPTURE, 3, 11, 22, 32, GameKey.COLOR_FLOOR);
            assertThrows(IllegalStateException.class, () -> ledger.capture(differentMatch, MANIFEST));
            assertEquals(ColorFloorWorldLedger.Status.CAPTURED, ledger.capture(first, MANIFEST).status());
            assertThrows(IllegalStateException.class, () -> ledger.capture(first, new byte[] {1}));
            ledger.markPurged(operation(PlayerStateOperation.Kind.PURGE, 4, 10, 20, 30, GameKey.COLOR_FLOOR));
            ledger.markRestored(operation(PlayerStateOperation.Kind.RESTORE, 5, 10, 20, 30, GameKey.COLOR_FLOOR));
            assertEquals(ColorFloorWorldLedger.Status.CAPTURED, ledger.capture(differentMatch, MANIFEST).status());
        }
    }

    @Test void refusesWrongGameWrongPhaseAndCorruptStoredManifest() throws Exception {
        Path directory = temporary.resolve("tamper");
        PlayerStateOperation capture = operation(PlayerStateOperation.Kind.CAPTURE, 1, 10, 20, 30, GameKey.COLOR_FLOOR);
        try (var ledger = new ColorFloorWorldLedger(directory)) {
            assertThrows(IllegalArgumentException.class, () -> ledger.capture(
                    operation(PlayerStateOperation.Kind.CAPTURE, 2, 10, 21, 31, GameKey.ARENA), MANIFEST));
            assertThrows(IllegalArgumentException.class, () -> ledger.requireLease(
                    operation(PlayerStateOperation.Kind.PURGE, 2, 10, 20, 30, GameKey.ARENA)));
            assertThrows(IllegalArgumentException.class, () -> ledger.arm(capture));
            assertThrows(IllegalArgumentException.class, () -> ledger.markRestored(capture));
            assertThrows(IllegalArgumentException.class, () -> ledger.capture(capture, new byte[0]));
            ledger.capture(capture, MANIFEST);
        }
        Path database = directory.resolve("color-floor-world.sqlite");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var update = connection.prepareStatement("UPDATE color_floor_lease SET manifest = ?")) {
            update.setBytes(1, new byte[] {1, 2, 3});
            update.executeUpdate();
        }
        try (var ledger = new ColorFloorWorldLedger(directory)) {
            assertThrows(IllegalStateException.class, () -> ledger.requireLease(capture));
            assertThrows(IllegalStateException.class, () -> ledger.capture(
                    operation(PlayerStateOperation.Kind.CAPTURE, 3, 10, 22, 32, GameKey.COLOR_FLOOR), MANIFEST));
        }
    }

    @Test void boundsStoredPayloadBeforeReadingAndBindsFrozenContextFields() throws Exception {
        Path identityDirectory = temporary.resolve("identity-drift");
        PlayerStateOperation capture = operation(PlayerStateOperation.Kind.CAPTURE, 1, 10, 20, 30, GameKey.COLOR_FLOOR);
        try (var ledger = new ColorFloorWorldLedger(identityDirectory)) {
            ledger.capture(capture, MANIFEST);
            assertThrows(IllegalStateException.class, () -> ledger.requireLease(new PlayerStateOperation(
                    PlayerStateOperation.Kind.PURGE, uuid(50), capture.captureOperationId(), uuid(999),
                    capture.sessionId(), capture.matchId(), capture.playerId(), capture.capturedConnectionId(),
                    uuid(500), GameKey.COLOR_FLOOR, capture.capturedAt())));
            assertThrows(IllegalStateException.class, () -> ledger.requireLease(new PlayerStateOperation(
                    PlayerStateOperation.Kind.PURGE, uuid(51), capture.captureOperationId(), capture.snapshotId(),
                    capture.sessionId(), capture.matchId(), uuid(999), capture.capturedConnectionId(), uuid(500),
                    GameKey.COLOR_FLOOR, capture.capturedAt())));
            assertThrows(IllegalStateException.class, () -> ledger.requireLease(new PlayerStateOperation(
                    PlayerStateOperation.Kind.PURGE, uuid(52), capture.captureOperationId(), capture.snapshotId(),
                    capture.sessionId(), capture.matchId(), capture.playerId(), uuid(999), uuid(500),
                    GameKey.COLOR_FLOOR, capture.capturedAt())));
            assertThrows(IllegalStateException.class, () -> ledger.requireLease(new PlayerStateOperation(
                    PlayerStateOperation.Kind.PURGE, uuid(53), capture.captureOperationId(), capture.snapshotId(),
                    capture.sessionId(), capture.matchId(), capture.playerId(), capture.capturedConnectionId(), uuid(500),
                    GameKey.COLOR_FLOOR, capture.capturedAt().plusNanos(1))));
        }

        Path oversizedDirectory = temporary.resolve("oversized");
        try (var ledger = new ColorFloorWorldLedger(oversizedDirectory)) { ledger.capture(capture, MANIFEST); }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + oversizedDirectory.resolve("color-floor-world.sqlite"));
             var update = connection.prepareStatement("UPDATE color_floor_lease SET manifest = zeroblob(?)")) {
            update.setInt(1, 16 * 1024 * 1024 + 1);
            update.executeUpdate();
        }
        try (var ledger = new ColorFloorWorldLedger(oversizedDirectory)) {
            assertThrows(IllegalStateException.class, () -> ledger.requireLease(capture));
        }
    }

    private static PlayerStateOperation operation(PlayerStateOperation.Kind kind, long operation, long match,
            long session, long player, GameKey game) {
        UUID epoch = uuid(700 + session);
        UUID captureOperationId = uuid(session - 19);
        UUID operationId = kind == PlayerStateOperation.Kind.CAPTURE ? captureOperationId : uuid(operation);
        return new PlayerStateOperation(kind, operationId, captureOperationId, uuid(session + 200), uuid(session), uuid(match),
                uuid(player), epoch, epoch, game, Instant.parse("2026-10-07T12:00:00Z"));
    }

    private static UUID uuid(long value) { return new UUID(0, value); }
}
