package com.ciaac.minecraft.minigames.isolation;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class SnapshotEnvelopeCodecTest {
    private static final String SCHEMA_1_FIXTURE = "4349414100000001"
            + "00000000000000000000000000000001"
            + "00000000000000000000000000000002"
            + "00000000000000000000000000000003"
            + "00000000000000000000000000000004"
            + "00000000000000000000000000000005"
            + "0000000c6275696c642d626174746c65"
            + "000001a033a44a00"
            + "0000000100000009494e56454e544f5259"
            + "00000003010203";

    private final SnapshotEnvelopeCodec codec = new SnapshotEnvelopeCodec();

    @Test
    void schemaOneEncodingRemainsByteForByteCompatible() {
        PlayerStateSnapshot snapshot = schemaOneSnapshot();

        assertEquals(SCHEMA_1_FIXTURE, java.util.HexFormat.of().formatHex(codec.encode(snapshot)));
        PlayerStateSnapshot decoded = codec.decode(java.util.HexFormat.of().parseHex(SCHEMA_1_FIXTURE));
        assertNull(decoded.capturedConnectionId());
        assertEquals(snapshot.snapshotId(), decoded.snapshotId());
        assertEquals(snapshot.operationId(), decoded.operationId());
        assertEquals(snapshot.capturedAt(), decoded.capturedAt());
        assertArrayEquals(snapshot.requireFacet(PlayerStateFacet.INVENTORY),
                decoded.requireFacet(PlayerStateFacet.INVENTORY));
    }

    @Test
    void schemaTwoRoundTripsConnectionAndNanosecondTimestamp() {
        Instant capturedAt = Instant.parse("2026-10-04T17:42:11.123456789Z");
        UUID connectionId = uuid(6);
        PlayerStateSnapshot snapshot = new PlayerStateSnapshot(
                2, uuid(1), uuid(2), uuid(3), uuid(4), uuid(5), connectionId,
                GameKey.BUILD_BATTLE, capturedAt, Map.of(PlayerStateFacet.INVENTORY, new byte[] {1, 2, 3}));

        PlayerStateSnapshot decoded = codec.decode(codec.encode(snapshot));

        assertEquals(connectionId, decoded.capturedConnectionId());
        assertEquals(capturedAt, decoded.capturedAt());
        assertArrayEquals(snapshot.requireFacet(PlayerStateFacet.INVENTORY),
                decoded.requireFacet(PlayerStateFacet.INVENTORY));
    }

    @Test
    void rejectsMissingConnectionAndInvalidSchemaMetadata() {
        PlayerStateSnapshot schemaTwo = new PlayerStateSnapshot(
                2, uuid(1), uuid(2), uuid(3), uuid(4), uuid(5), uuid(6),
                GameKey.BUILD_BATTLE, Instant.EPOCH, Map.of(PlayerStateFacet.INVENTORY, new byte[] {1}));
        byte[] encoded = codec.encode(schemaTwo);
        byte[] withoutConnection = new byte[encoded.length - 16];
        System.arraycopy(encoded, 0, withoutConnection, 0, 88);
        System.arraycopy(encoded, 104, withoutConnection, 88, encoded.length - 104);

        assertThrows(IllegalArgumentException.class, () -> codec.decode(withoutConnection));
        assertThrows(IllegalArgumentException.class, () -> new PlayerStateSnapshot(
                2, uuid(1), uuid(2), uuid(3), uuid(4), uuid(5), null,
                GameKey.BUILD_BATTLE, Instant.EPOCH, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new PlayerStateSnapshot(
                1, uuid(1), uuid(2), uuid(3), uuid(4), uuid(5), uuid(6),
                GameKey.BUILD_BATTLE, Instant.EPOCH, Map.of()));

        byte[] unsupportedSchema = encoded.clone();
        unsupportedSchema[7] = 3;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(unsupportedSchema));
        assertThrows(IllegalArgumentException.class, () -> new PlayerStateSnapshot(
                3, uuid(1), uuid(2), uuid(3), uuid(4), uuid(5), uuid(6),
                GameKey.BUILD_BATTLE, Instant.EPOCH, Map.of()));
    }

    @Test
    void rejectsTruncatedAndTrailingEnvelopeData() {
        byte[] encoded = codec.encode(schemaOneSnapshot());
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(java.util.Arrays.copyOf(encoded, encoded.length - 1)));
        byte[] withTrailingByte = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(withTrailingByte));
    }

    @Test
    void rejectsInvalidNanosecondsAndOutOfRangeEpochSeconds() {
        var snapshot = new PlayerStateSnapshot(2, uuid(1), uuid(2), uuid(3), uuid(4), uuid(5), uuid(6),
                GameKey.BUILD_BATTLE, Instant.EPOCH, Map.of());
        byte[] encoded = codec.encode(snapshot);
        int timeOffset = 104 + 4 + snapshot.game().id().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        for (int nanos : new int[] {-1, 1_000_000_000}) {
            byte[] corrupt = encoded.clone();
            java.nio.ByteBuffer.wrap(corrupt).putInt(timeOffset + 8, nanos);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(corrupt));
        }
        for (long seconds : new long[] {Long.MIN_VALUE, Long.MAX_VALUE}) {
            byte[] corrupt = encoded.clone();
            java.nio.ByteBuffer.wrap(corrupt).putLong(timeOffset, seconds);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(corrupt));
        }
    }

    private static PlayerStateSnapshot schemaOneSnapshot() {
        return new PlayerStateSnapshot(
                1, uuid(1), uuid(2), uuid(3), uuid(4), uuid(5), GameKey.BUILD_BATTLE,
                Instant.parse("2026-08-24T12:00:00Z"), Map.of(PlayerStateFacet.INVENTORY, new byte[] {1, 2, 3}));
    }

    private static UUID uuid(long leastSignificantBits) {
        return new UUID(0, leastSignificantBits);
    }
}
