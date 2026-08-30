package com.ciaac.minecraft.minigames.isolation;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** Bounded deterministic binary envelope; facet payloads remain adapter-owned. */
public final class SnapshotEnvelopeCodec {
    private static final int MAGIC = 0x43494141;
    private static final int MAX_FACET_BYTES = 8 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 32 * 1024 * 1024;

    public byte[] encode(PlayerStateSnapshot snapshot) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeInt(snapshot.schemaVersion());
                writeUuid(output, snapshot.snapshotId());
                writeUuid(output, snapshot.operationId());
                writeUuid(output, snapshot.sessionId());
                writeUuid(output, snapshot.matchId());
                writeUuid(output, snapshot.playerId());
                writeString(output, snapshot.game().id());
                output.writeLong(snapshot.capturedAt().toEpochMilli());
                Map<PlayerStateFacet, byte[]> facets = snapshot.facets();
                output.writeInt(facets.size());
                for (PlayerStateFacet facet : PlayerStateFacet.values()) {
                    byte[] payload = facets.get(facet);
                    if (payload == null) {
                        continue;
                    }
                    if (payload.length > MAX_FACET_BYTES) {
                        throw new IllegalArgumentException("Facet payload exceeds bounded size: " + facet);
                    }
                    writeString(output, facet.name());
                    output.writeInt(payload.length);
                    output.write(payload);
                }
            }
            if (bytes.size() > MAX_TOTAL_BYTES) {
                throw new IllegalArgumentException("Snapshot envelope exceeds bounded size");
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unexpected in-memory snapshot encoding failure", exception);
        }
    }

    public PlayerStateSnapshot decode(byte[] envelope) {
        if (envelope == null || envelope.length == 0 || envelope.length > MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("Snapshot envelope has an invalid size");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(envelope))) {
            if (input.readInt() != MAGIC) {
                throw new IllegalArgumentException("Snapshot envelope magic does not match");
            }
            int schema = input.readInt();
            UUID snapshotId = readUuid(input);
            UUID operationId = readUuid(input);
            UUID sessionId = readUuid(input);
            UUID matchId = readUuid(input);
            UUID playerId = readUuid(input);
            GameKey game = GameKey.fromId(readString(input))
                    .orElseThrow(() -> new IllegalArgumentException("Unknown snapshot game"));
            Instant capturedAt = Instant.ofEpochMilli(input.readLong());
            int count = input.readInt();
            if (count < 0 || count > PlayerStateFacet.values().length) {
                throw new IllegalArgumentException("Snapshot facet count is invalid");
            }
            EnumMap<PlayerStateFacet, byte[]> facets = new EnumMap<>(PlayerStateFacet.class);
            for (int index = 0; index < count; index++) {
                PlayerStateFacet facet = PlayerStateFacet.valueOf(readString(input));
                int length = input.readInt();
                if (length < 0 || length > MAX_FACET_BYTES) {
                    throw new IllegalArgumentException("Snapshot facet length is invalid");
                }
                if (facets.put(facet, input.readNBytes(length)) != null) {
                    throw new IllegalArgumentException("Duplicate snapshot facet " + facet);
                }
                if (facets.get(facet).length != length) {
                    throw new IllegalArgumentException("Truncated snapshot facet " + facet);
                }
            }
            if (input.available() != 0) {
                throw new IllegalArgumentException("Snapshot envelope contains trailing data");
            }
            return new PlayerStateSnapshot(
                    schema, snapshotId, operationId, sessionId, matchId, playerId, game, capturedAt, facets);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid snapshot envelope", exception);
        }
    }

    public String checksum(byte[] envelope) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(envelope));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 128) {
            throw new IllegalArgumentException("Snapshot string is too long");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > 128) {
            throw new IllegalArgumentException("Snapshot string length is invalid");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IllegalArgumentException("Truncated snapshot string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
