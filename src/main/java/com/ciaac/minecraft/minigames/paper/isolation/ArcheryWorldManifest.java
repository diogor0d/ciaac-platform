package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Canonical, bounded snapshot of the immutable Archery participant regions. */
public record ArcheryWorldManifest(String paperVersion, List<ProtectedRegion> regions) {
    private static final int MAGIC = 0x43414157; // CAAW, distinct from CAAM and CAIM
    private static final int SCHEMA = 1;
    private static final int MAX_MANIFEST_BYTES = 128 * 1024;
    private static final int MAX_VERSION_BYTES = 256;
    private static final int MAX_REGION_ID_BYTES = 64;
    private static final int MAX_ENUM_ID_BYTES = 32;
    private static final int MAX_REGIONS = 256;
    private static final Comparator<ProtectedRegion> REGION_ORDER = Comparator.comparing(ProtectedRegion::id);

    public ArcheryWorldManifest {
        Objects.requireNonNull(paperVersion, "paperVersion");
        strictUtf8(paperVersion, "Paper version", MAX_VERSION_BYTES);
        if (paperVersion.isBlank())
            throw new IllegalArgumentException("Paper version must be nonblank and at most 256 UTF-8 bytes");
        Objects.requireNonNull(regions, "regions");
        if (regions.isEmpty() || regions.size() > MAX_REGIONS)
            throw new IllegalArgumentException("Archery manifest requires 1..256 regions");

        ArrayList<ProtectedRegion> copy = new ArrayList<>(List.copyOf(regions));
        Set<String> ids = new HashSet<>();
        UUID worldId = null;
        for (ProtectedRegion region : copy) {
            Objects.requireNonNull(region, "region");
            if (!ids.add(region.id())) throw new IllegalArgumentException("Archery region IDs must be unique");
            if (region.game() != GameKey.ARCHERY_RANGE
                    || region.role() != ProtectedRegionRole.PARTICIPANT_ONLY
                    || !region.immutable()) {
                throw new IllegalArgumentException(
                        "Archery manifest regions must be immutable Archery participant regions");
            }
            if (worldId == null) worldId = region.bounds().worldId();
            else if (!worldId.equals(region.bounds().worldId()))
                throw new IllegalArgumentException("Archery regions must share one world identity");
        }
        copy.sort(REGION_ORDER);
        for (int left = 0; left < copy.size(); left++) {
            for (int right = left + 1; right < copy.size(); right++) {
                if (copy.get(left).bounds().intersects(copy.get(right).bounds()))
                    throw new IllegalArgumentException("Archery manifest regions must not overlap");
            }
        }
        regions = List.copyOf(copy);
    }

    /** World UUID shared by every region in this manifest. */
    public UUID worldId() {
        return regions.getFirst().bounds().worldId();
    }

    /** Encodes a fresh canonical byte array. */
    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeInt(SCHEMA);
                writeString(output, paperVersion, MAX_VERSION_BYTES, "Paper version");
                output.writeInt(regions.size());
                for (ProtectedRegion region : regions) {
                    writeString(output, region.id(), MAX_REGION_ID_BYTES, "region ID");
                    writeString(output, region.game().id(), MAX_ENUM_ID_BYTES, "game ID");
                    writeString(output, region.role().name(), MAX_ENUM_ID_BYTES, "region role");
                    output.writeByte(region.immutable() ? 1 : 0);
                    writeUuid(output, region.bounds().worldId());
                    output.writeInt(region.bounds().minX());
                    output.writeInt(region.bounds().minY());
                    output.writeInt(region.bounds().minZ());
                    output.writeInt(region.bounds().maxX());
                    output.writeInt(region.bounds().maxY());
                    output.writeInt(region.bounds().maxZ());
                }
            }
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_MANIFEST_BYTES)
                throw new IllegalArgumentException("Archery manifest exceeds 128 KiB");
            return encoded;
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not encode Archery manifest", impossible);
        }
    }

    /** Parses and validates a canonical bounded manifest without consulting server state. */
    public static ArcheryWorldManifest decode(byte[] source) {
        Objects.requireNonNull(source, "source");
        if (source.length == 0 || source.length > MAX_MANIFEST_BYTES)
            throw new IllegalArgumentException("Archery manifest size is invalid");
        byte[] encoded = source.clone();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) throw new IllegalArgumentException("Archery manifest magic is invalid");
            if (input.readInt() != SCHEMA) throw new IllegalArgumentException("Archery manifest schema is unsupported");
            String version = readString(input, MAX_VERSION_BYTES, "Paper version");
            int count = input.readInt();
            if (count < 1 || count > MAX_REGIONS)
                throw new IllegalArgumentException("Archery manifest requires 1..256 regions");

            List<ProtectedRegion> regions = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                String id = readString(input, MAX_REGION_ID_BYTES, "region ID");
                String gameId = readString(input, MAX_ENUM_ID_BYTES, "game ID");
                String roleName = readString(input, MAX_ENUM_ID_BYTES, "region role");
                int immutableByte = input.readUnsignedByte();
                if (immutableByte > 1) throw new IllegalArgumentException("Archery immutable flag is noncanonical");
                UUID worldId = readUuid(input);
                CuboidRegion bounds = new CuboidRegion(worldId, input.readInt(), input.readInt(), input.readInt(),
                        input.readInt(), input.readInt(), input.readInt());
                GameKey game = GameKey.fromId(gameId)
                        .orElseThrow(() -> new IllegalArgumentException("Archery region game is invalid"));
                ProtectedRegionRole role;
                try { role = ProtectedRegionRole.valueOf(roleName); }
                catch (IllegalArgumentException malformed) {
                    throw new IllegalArgumentException("Archery region role is invalid", malformed);
                }
                regions.add(new ProtectedRegion(id, game, bounds, role, immutableByte == 1));
            }
            if (input.available() != 0) throw new IllegalArgumentException("Archery manifest has trailing data");

            ArcheryWorldManifest manifest = new ArcheryWorldManifest(version, regions);
            if (!Arrays.equals(encoded, manifest.encode()))
                throw new IllegalArgumentException("Archery manifest encoding is not canonical");
            return manifest;
        } catch (EOFException truncated) {
            throw new IllegalArgumentException("Archery manifest is truncated", truncated);
        } catch (CharacterCodingException malformedUtf8) {
            throw new IllegalArgumentException("Archery manifest contains invalid UTF-8", malformedUtf8);
        } catch (IOException malformed) {
            throw new IllegalArgumentException("Archery manifest cannot be decoded", malformed);
        }
    }

    private static void writeString(DataOutputStream output, String value, int maximumBytes, String field)
            throws IOException {
        byte[] encoded = strictUtf8(value, field, maximumBytes);
        if (encoded.length == 0) throw new IllegalArgumentException(field + " length is invalid");
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String readString(DataInputStream input, int maximumBytes, String field)
            throws IOException {
        int length = input.readInt();
        if (length <= 0 || length > maximumBytes)
            throw new IllegalArgumentException(field + " length is invalid");
        byte[] encoded = input.readNBytes(length);
        if (encoded.length != length) throw new EOFException("Truncated " + field);
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(encoded)).toString();
    }

    private static byte[] strictUtf8(String value, String field, int maximumBytes) {
        ByteBuffer encoded = ByteBuffer.allocate(maximumBytes + 1);
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CoderResult result = encoder.encode(CharBuffer.wrap(value), encoded, true);
            if (result.isError()) result.throwException();
            if (result.isOverflow()) throw new IllegalArgumentException(field + " exceeds its UTF-8 byte limit");
            result = encoder.flush(encoded);
            if (result.isError()) result.throwException();
            if (result.isOverflow()) throw new IllegalArgumentException(field + " exceeds its UTF-8 byte limit");
            if (encoded.position() > maximumBytes)
                throw new IllegalArgumentException(field + " exceeds its UTF-8 byte limit");
            encoded.flip();
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException(field + " is not valid Unicode", malformed);
        }
    }

    private static void writeUuid(DataOutputStream output, UUID id) throws IOException {
        output.writeLong(id.getMostSignificantBits());
        output.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }
}
