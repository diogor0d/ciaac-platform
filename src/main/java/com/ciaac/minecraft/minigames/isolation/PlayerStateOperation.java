package com.ciaac.minecraft.minigames.isolation;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Identity and replay context; a connection ID alone is not authentication evidence. */
public record PlayerStateOperation(
        Kind kind,
        UUID operationId,
        UUID captureOperationId,
        UUID snapshotId,
        UUID sessionId,
        UUID matchId,
        UUID playerId,
        UUID capturedConnectionId,
        UUID connectionId,
        GameKey game,
        Instant capturedAt) {

    public enum Kind { CAPTURE, ENTER, PURGE, RESTORE }

    public PlayerStateOperation {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(captureOperationId, "captureOperationId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(game, "game");
        Objects.requireNonNull(capturedAt, "capturedAt");
        if ((kind == Kind.CAPTURE || kind == Kind.ENTER)
                && !connectionId.equals(capturedConnectionId)) {
            throw new IllegalStateException("Temporary entry requires the captured connection epoch");
        }
        if (kind == Kind.CAPTURE && !operationId.equals(captureOperationId)) {
            throw new IllegalArgumentException("Capture operation identity is inconsistent");
        }
    }

    public static PlayerStateOperation fromSnapshot(
            Kind kind, UUID operationId, PlayerStateSnapshot snapshot, UUID connectionId) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new PlayerStateOperation(kind, operationId, snapshot.operationId(),
                snapshot.snapshotId(), snapshot.sessionId(), snapshot.matchId(), snapshot.playerId(),
                snapshot.capturedConnectionId(), connectionId, snapshot.game(), snapshot.capturedAt());
    }
}
