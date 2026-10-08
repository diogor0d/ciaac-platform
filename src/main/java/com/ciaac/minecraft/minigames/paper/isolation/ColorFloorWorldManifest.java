package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.colorfloor.FloorColor;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import pt.ciaac.minigames.paper.template.TemplateArtifact;

/** Canonical bounded snapshot of the single immutable Color Floor facility and its reviewed floor template. */
public record ColorFloorWorldManifest(String paperVersion, ProtectedRegion boundary, TemplateArtifact artifact) {
    private static final int MAGIC = 0x43434657; // CCFW, distinct from CAAW, CAAM, and CAIM
    private static final int SCHEMA = 1;
    private static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;
    private static final int MAX_PAPER_VERSION_BYTES = 256;
    private static final int MAX_REGION_ID_BYTES = 64;
    private static final int MAX_ARTIFACT_ID_BYTES = 64;
    private static final int MAX_REVISION_BYTES = 64;
    private static final int MAX_WORLD_NAME_BYTES = 256;
    private static final int MAX_CHECKSUM_BYTES = 64;
    private static final int MAX_BLOCK_DATA_BYTES = 64;
    private static final int MAX_COLOR_ID_BYTES = 32;
    private static final Comparator<TemplateArtifact.BlockCoordinate> COORDINATE_ORDER =
            Comparator.comparingInt(TemplateArtifact.BlockCoordinate::x)
                    .thenComparingInt(TemplateArtifact.BlockCoordinate::y)
                    .thenComparingInt(TemplateArtifact.BlockCoordinate::z);
    private static final Pattern MATERIAL_PATTERN = Pattern.compile(
            "minecraft:(red|orange|yellow|lime|green|cyan|light_blue|blue|purple|magenta|pink|white|light_gray|gray|black|brown)_(concrete|wool)");

    public ColorFloorWorldManifest {
        Objects.requireNonNull(paperVersion, "paperVersion");
        strictUtf8(paperVersion, "Paper version", MAX_PAPER_VERSION_BYTES);
        if (paperVersion.isBlank()) throw new IllegalArgumentException("Paper version must be nonblank");
        Objects.requireNonNull(boundary, "boundary");
        Objects.requireNonNull(artifact, "artifact");
        validateBoundary(boundary);
        validateArtifact(boundary, artifact);
        if (encodedSize(paperVersion, boundary, artifact) > MAX_MANIFEST_BYTES)
            throw new IllegalArgumentException("Color Floor manifest exceeds 16 MiB");
        if (!artifact.checksumMatches()) throw new IllegalArgumentException("Color Floor template checksum is invalid");
    }

    public UUID worldId() { return boundary.bounds().worldId(); }

    /** Produces sorted coordinate entries and a fresh, canonical byte array. */
    public byte[] encode() {
        long size = encodedSize(paperVersion, boundary, artifact);
        if (size > MAX_MANIFEST_BYTES) throw new IllegalArgumentException("Color Floor manifest exceeds 16 MiB");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) size);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC);
                out.writeInt(SCHEMA);
                writeString(out, paperVersion, MAX_PAPER_VERSION_BYTES, "Paper version");
                writeString(out, boundary.id(), MAX_REGION_ID_BYTES, "boundary ID");
                writeString(out, boundary.game().id(), 32, "boundary game");
                writeString(out, boundary.role().name(), 32, "boundary role");
                out.writeByte(boundary.immutable() ? 1 : 0);
                writeRegion(out, boundary.bounds());
                writeString(out, artifact.artifactId(), MAX_ARTIFACT_ID_BYTES, "artifact ID");
                writeString(out, artifact.revision(), MAX_REVISION_BYTES, "artifact revision");
                writeUuid(out, artifact.worldId());
                writeString(out, artifact.worldName(), MAX_WORLD_NAME_BYTES, "world name");
                writeRegion(out, artifact.volume());
                writeString(out, artifact.checksumSha256(), MAX_CHECKSUM_BYTES, "artifact checksum");
                out.writeInt(artifact.blockData().size());
                ArrayList<TemplateArtifact.BlockCoordinate> coordinates = new ArrayList<>(artifact.blockData().keySet());
                coordinates.sort(COORDINATE_ORDER);
                for (TemplateArtifact.BlockCoordinate coordinate : coordinates) {
                    out.writeInt(coordinate.x()); out.writeInt(coordinate.y()); out.writeInt(coordinate.z());
                    writeString(out, artifact.blockData().get(coordinate), MAX_BLOCK_DATA_BYTES, "block data");
                    writeString(out, artifact.colorIds().get(coordinate), MAX_COLOR_ID_BYTES, "floor color");
                }
            }
            byte[] encoded = bytes.toByteArray();
            if (encoded.length != size) throw new IllegalStateException("Color Floor manifest size calculation differs");
            return encoded;
        } catch (IOException impossible) { throw new IllegalStateException("Could not encode Color Floor manifest", impossible); }
    }

    /** Parses a canonical manifest without Bukkit or other live server access. */
    public static ColorFloorWorldManifest decode(byte[] source) {
        Objects.requireNonNull(source, "source");
        if (source.length == 0 || source.length > MAX_MANIFEST_BYTES)
            throw new IllegalArgumentException("Color Floor manifest size is invalid");
        byte[] encoded = source.clone();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (in.readInt() != MAGIC) throw new IllegalArgumentException("Color Floor manifest magic is invalid");
            if (in.readInt() != SCHEMA) throw new IllegalArgumentException("Color Floor manifest schema is unsupported");
            String version = readString(in, MAX_PAPER_VERSION_BYTES, "Paper version");
            String boundaryId = readString(in, MAX_REGION_ID_BYTES, "boundary ID");
            String gameId = readString(in, 32, "boundary game");
            String roleName = readString(in, 32, "boundary role");
            int immutable = in.readUnsignedByte();
            if (immutable != 1) throw new IllegalArgumentException("Color Floor boundary immutable flag is invalid");
            CuboidRegion boundaryBounds = readRegion(in);
            if (!GameKey.COLOR_FLOOR.id().equals(gameId)
                    || !ProtectedRegionRole.PARTICIPANT_ONLY.name().equals(roleName))
                throw new IllegalArgumentException("Color Floor boundary role or game is invalid");
            ProtectedRegion boundary = new ProtectedRegion(boundaryId, GameKey.COLOR_FLOOR, boundaryBounds,
                    ProtectedRegionRole.PARTICIPANT_ONLY, true);

            String artifactId = readString(in, MAX_ARTIFACT_ID_BYTES, "artifact ID");
            String revision = readString(in, MAX_REVISION_BYTES, "artifact revision");
            UUID artifactWorld = readUuid(in);
            String worldName = readString(in, MAX_WORLD_NAME_BYTES, "world name");
            CuboidRegion volume = readRegion(in);
            String checksum = readString(in, MAX_CHECKSUM_BYTES, "artifact checksum");
            int count = in.readInt();
            if (count < 1 || count > TemplateArtifact.MAX_BLOCKS || count > in.available() / 22)
                throw new IllegalArgumentException("Color Floor block entry count is invalid");
            Map<TemplateArtifact.BlockCoordinate, String> blocks = new LinkedHashMap<>(Math.min(count, 65_536));
            Map<TemplateArtifact.BlockCoordinate, String> colors = new LinkedHashMap<>(Math.min(count, 65_536));
            TemplateArtifact.BlockCoordinate previous = null;
            for (int index = 0; index < count; index++) {
                TemplateArtifact.BlockCoordinate coordinate = new TemplateArtifact.BlockCoordinate(
                        in.readInt(), in.readInt(), in.readInt());
                if (previous != null && COORDINATE_ORDER.compare(previous, coordinate) >= 0)
                    throw new IllegalArgumentException("Color Floor block coordinates are not strictly sorted");
                previous = coordinate;
                String blockData = readString(in, MAX_BLOCK_DATA_BYTES, "block data");
                String color = readString(in, MAX_COLOR_ID_BYTES, "floor color");
                if (blocks.put(coordinate, blockData) != null || colors.put(coordinate, color) != null)
                    throw new IllegalArgumentException("Color Floor manifest contains duplicate coordinates");
            }
            if (in.available() != 0) throw new IllegalArgumentException("Color Floor manifest has trailing data");
            TemplateArtifact artifact = new TemplateArtifact(artifactId, revision, artifactWorld, worldName,
                    volume, checksum, blocks, colors);
            ColorFloorWorldManifest manifest = new ColorFloorWorldManifest(version, boundary, artifact);
            if (!Arrays.equals(encoded, manifest.encode()))
                throw new IllegalArgumentException("Color Floor manifest encoding is not canonical");
            return manifest;
        } catch (EOFException truncated) {
            throw new IllegalArgumentException("Color Floor manifest is truncated", truncated);
        } catch (CharacterCodingException malformedUtf8) {
            throw new IllegalArgumentException("Color Floor manifest contains invalid UTF-8", malformedUtf8);
        } catch (IOException malformed) {
            throw new IllegalArgumentException("Color Floor manifest cannot be decoded", malformed);
        }
    }

    private static void validateBoundary(ProtectedRegion boundary) {
        strictUtf8(boundary.id(), "boundary ID", MAX_REGION_ID_BYTES);
        if (boundary.game() != GameKey.COLOR_FLOOR || boundary.role() != ProtectedRegionRole.PARTICIPANT_ONLY
                || !boundary.immutable())
            throw new IllegalArgumentException("Color Floor boundary must be immutable participant-only Color Floor geometry");
    }

    private static void validateArtifact(ProtectedRegion boundary, TemplateArtifact artifact) {
        strictUtf8(artifact.artifactId(), "artifact ID", MAX_ARTIFACT_ID_BYTES);
        strictUtf8(artifact.revision(), "artifact revision", MAX_REVISION_BYTES);
        strictUtf8(artifact.worldName(), "world name", MAX_WORLD_NAME_BYTES);
        strictUtf8(artifact.checksumSha256(), "artifact checksum", MAX_CHECKSUM_BYTES);
        if (!"immutable-floor-template".equals(artifact.artifactId()))
            throw new IllegalArgumentException("Color Floor artifact ID is not the reviewed immutable floor template");
        CuboidRegion bounds = boundary.bounds();
        CuboidRegion volume = artifact.volume();
        if (!artifact.worldId().equals(bounds.worldId()) || !volume.worldId().equals(bounds.worldId()))
            throw new IllegalArgumentException("Color Floor artifact and boundary must share one world UUID");
        if (volume.minY() != volume.maxY() || volume.minX() < bounds.minX() || volume.maxX() > bounds.maxX()
                || volume.minY() < bounds.minY() || volume.maxY() > bounds.maxY()
                || volume.minZ() < bounds.minZ() || volume.maxZ() > bounds.maxZ())
            throw new IllegalArgumentException("Color Floor template must be a one-layer volume inside its boundary");
        if (!artifact.coversVolume() || artifact.blockData().isEmpty()
                || artifact.blockData().size() > TemplateArtifact.MAX_BLOCKS
                || !artifact.blockData().keySet().equals(artifact.colorIds().keySet()))
            throw new IllegalArgumentException("Color Floor template coverage, checksum, or color map is invalid");
        for (Map.Entry<TemplateArtifact.BlockCoordinate, String> entry : artifact.blockData().entrySet()) {
            TemplateArtifact.BlockCoordinate coordinate = entry.getKey();
            String data = entry.getValue();
            String colorId = artifact.colorIds().get(coordinate);
            FloorColor color;
            try { color = FloorColor.valueOf(colorId); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Color Floor template contains an unknown floor color", invalid); }
            if (!MATERIAL_PATTERN.matcher(data).matches())
                throw new IllegalArgumentException("Color Floor template block is not a canonical solid colored material");
            String expectedPrefix = "minecraft:" + color.name().toLowerCase(java.util.Locale.ROOT) + "_";
            if (!data.startsWith(expectedPrefix))
                throw new IllegalArgumentException("Color Floor block material does not match its floor color");
        }
    }

    private static long encodedSize(String version, ProtectedRegion boundary, TemplateArtifact artifact) {
        long size = 8;
        size = addStringSize(size, version, MAX_PAPER_VERSION_BYTES, "Paper version");
        size = addStringSize(size, boundary.id(), MAX_REGION_ID_BYTES, "boundary ID");
        size = addStringSize(size, boundary.game().id(), 32, "boundary game");
        size = addStringSize(size, boundary.role().name(), 32, "boundary role");
        size = Math.addExact(size, 1 + 16 + 6L * Integer.BYTES);
        size = addStringSize(size, artifact.artifactId(), MAX_ARTIFACT_ID_BYTES, "artifact ID");
        size = addStringSize(size, artifact.revision(), MAX_REVISION_BYTES, "artifact revision");
        size = Math.addExact(size, 16);
        size = addStringSize(size, artifact.worldName(), MAX_WORLD_NAME_BYTES, "world name");
        size = Math.addExact(size, 16 + 6L * Integer.BYTES);
        size = addStringSize(size, artifact.checksumSha256(), MAX_CHECKSUM_BYTES, "artifact checksum");
        size = Math.addExact(size, Integer.BYTES);
        for (Map.Entry<TemplateArtifact.BlockCoordinate, String> entry : artifact.blockData().entrySet()) {
            size = Math.addExact(size, 3L * Integer.BYTES);
            size = addAsciiStringSize(size, entry.getValue(), MAX_BLOCK_DATA_BYTES, "block data");
            size = addAsciiStringSize(size, artifact.colorIds().get(entry.getKey()), MAX_COLOR_ID_BYTES, "floor color");
            if (size > MAX_MANIFEST_BYTES) return size;
        }
        return size;
    }

    private static long addStringSize(long total, String value, int maximumBytes, String field) {
        return Math.addExact(total, Integer.BYTES + (long) strictUtf8(value, field, maximumBytes).length);
    }

    private static long addAsciiStringSize(long total, String value, int maximumBytes, String field) {
        if (value == null || value.isEmpty() || value.length() > maximumBytes)
            throw new IllegalArgumentException(field + " length is invalid");
        return Math.addExact(total, Integer.BYTES + (long) value.length());
    }

    private static void writeString(DataOutputStream out, String value, int maximumBytes, String field) throws IOException {
        byte[] bytes = strictUtf8(value, field, maximumBytes);
        if (bytes.length == 0) throw new IllegalArgumentException(field + " length is invalid");
        out.writeInt(bytes.length); out.write(bytes);
    }

    private static String readString(DataInputStream in, int maximumBytes, String field)
            throws IOException, CharacterCodingException {
        int length = in.readInt();
        if (length <= 0 || length > maximumBytes) throw new IllegalArgumentException(field + " length is invalid");
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Truncated " + field);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static byte[] strictUtf8(String value, String field, int maximumBytes) {
        Objects.requireNonNull(value, field);
        ByteBuffer out = ByteBuffer.allocate(maximumBytes + 1);
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CoderResult result = encoder.encode(CharBuffer.wrap(value), out, true);
            if (result.isError()) result.throwException();
            if (result.isOverflow()) throw new IllegalArgumentException(field + " exceeds its UTF-8 byte limit");
            result = encoder.flush(out);
            if (result.isError()) result.throwException();
            if (result.isOverflow()) throw new IllegalArgumentException(field + " exceeds its UTF-8 byte limit");
            if (out.position() > maximumBytes) throw new IllegalArgumentException(field + " exceeds its UTF-8 byte limit");
            out.flip(); byte[] bytes = new byte[out.remaining()]; out.get(bytes); return bytes;
        } catch (CharacterCodingException malformed) {
            throw new IllegalArgumentException(field + " is not valid Unicode", malformed);
        }
    }

    private static void writeRegion(DataOutputStream out, CuboidRegion region) throws IOException {
        writeUuid(out, region.worldId());
        out.writeInt(region.minX()); out.writeInt(region.minY()); out.writeInt(region.minZ());
        out.writeInt(region.maxX()); out.writeInt(region.maxY()); out.writeInt(region.maxZ());
    }

    private static CuboidRegion readRegion(DataInputStream in) throws IOException {
        UUID world = readUuid(in);
        return new CuboidRegion(world, in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt());
    }

    private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits()); out.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
}
