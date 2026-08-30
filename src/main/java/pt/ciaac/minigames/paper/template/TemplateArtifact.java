package pt.ciaac.minigames.paper.template;

import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, operator-reviewed block artifact. It contains no live Bukkit
 * handles and can therefore be checked before any world access is attempted.
 */
public record TemplateArtifact(
        String artifactId,
        String revision,
        UUID worldId,
        String worldName,
        CuboidRegion volume,
        String checksumSha256,
        Map<BlockCoordinate, String> blockData,
        Map<BlockCoordinate, String> colorIds) {
    public static final int MAX_BLOCKS = 1_000_000;
    public static final int MAX_DATA_LENGTH = 512;

    public TemplateArtifact {
        artifactId = boundedId(artifactId, "artifactId", 64);
        revision = boundedId(revision, "revision", 64);
        worldId = Objects.requireNonNull(worldId, "worldId");
        worldName = boundedText(worldName, "worldName", 128);
        volume = Objects.requireNonNull(volume, "volume");
        if (!volume.worldId().equals(worldId)) {
            throw new IllegalArgumentException("template volume belongs to another world");
        }
        long volumeBlocks = ((long) volume.maxX() - volume.minX() + 1)
                * ((long) volume.maxY() - volume.minY() + 1)
                * ((long) volume.maxZ() - volume.minZ() + 1);
        if (volumeBlocks <= 0 || volumeBlocks > MAX_BLOCKS) {
            throw new IllegalArgumentException("template volume is outside the safe bound");
        }
        checksumSha256 = Objects.requireNonNull(checksumSha256, "checksumSha256").toLowerCase(java.util.Locale.ROOT);
        if (!checksumSha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("checksumSha256 must be a SHA-256 hex digest");
        }
        blockData = immutableBlocks(blockData, volume);
        colorIds = immutableColors(colorIds, volume);
        if (blockData.isEmpty() || blockData.size() > MAX_BLOCKS) {
            throw new IllegalArgumentException("template block count is outside the safe bound");
        }
        if (!colorIds.isEmpty() && !colorIds.keySet().equals(blockData.keySet())) {
            throw new IllegalArgumentException("template color coordinates must match block coordinates");
        }
    }

    public boolean checksumMatches() {
        return checksumSha256.equals(calculateChecksum());
    }

    /** Because every entry is unique and bounded, size equality proves full coverage. */
    public boolean coversVolume() {
        long volumeBlocks = ((long) volume.maxX() - volume.minX() + 1)
                * ((long) volume.maxY() - volume.minY() + 1)
                * ((long) volume.maxZ() - volume.minZ() + 1);
        return blockData.size() == volumeBlocks;
    }

    public String calculateChecksum() {
        StringBuilder canonical = new StringBuilder();
        canonical.append(artifactId).append('\n')
                .append(revision).append('\n')
                .append(worldId).append('\n')
                .append(worldName).append('\n')
                .append(volume.worldId()).append('|')
                .append(volume.minX()).append('|').append(volume.minY()).append('|').append(volume.minZ()).append('|')
                .append(volume.maxX()).append('|').append(volume.maxY()).append('|').append(volume.maxZ()).append('\n');
        blockData.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(BlockCoordinate::x)
                        .thenComparingInt(BlockCoordinate::y).thenComparingInt(BlockCoordinate::z)))
                .forEach(entry -> canonical.append("block|")
                        .append(entry.getKey().x()).append('|').append(entry.getKey().y()).append('|')
                        .append(entry.getKey().z()).append('|').append(entry.getValue()).append('|')
                        .append(colorIds.getOrDefault(entry.getKey(), "")).append('\n'));
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    public record BlockCoordinate(int x, int y, int z) {
        public boolean inside(CuboidRegion bounds) {
            return bounds.contains(x, y, z);
        }
    }

    private static Map<BlockCoordinate, String> immutableBlocks(
            Map<BlockCoordinate, String> values, CuboidRegion bounds) {
        Objects.requireNonNull(values, "blockData");
        LinkedHashMap<BlockCoordinate, String> copy = new LinkedHashMap<>();
        for (Map.Entry<BlockCoordinate, String> entry : values.entrySet()) {
            BlockCoordinate coordinate = Objects.requireNonNull(entry.getKey(), "block coordinate");
            String data = boundedText(entry.getValue(), "blockData", MAX_DATA_LENGTH);
            if (!coordinate.inside(bounds) || data.indexOf('|') >= 0) {
                throw new IllegalArgumentException("template block is outside bounds or not canonical");
            }
            if (copy.put(coordinate, data) != null) {
                throw new IllegalArgumentException("duplicate template block coordinate");
            }
        }
        return Map.copyOf(copy);
    }

    private static Map<BlockCoordinate, String> immutableColors(
            Map<BlockCoordinate, String> values, CuboidRegion bounds) {
        Objects.requireNonNull(values, "colorIds");
        LinkedHashMap<BlockCoordinate, String> copy = new LinkedHashMap<>();
        for (Map.Entry<BlockCoordinate, String> entry : values.entrySet()) {
            BlockCoordinate coordinate = Objects.requireNonNull(entry.getKey(), "color coordinate");
            String color = boundedId(entry.getValue(), "colorId", 32).toUpperCase(java.util.Locale.ROOT);
            if (!coordinate.inside(bounds)) {
                throw new IllegalArgumentException("template color is outside bounds");
            }
            if (copy.put(coordinate, color) != null) {
                throw new IllegalArgumentException("duplicate template color coordinate");
            }
        }
        return Map.copyOf(copy);
    }

    private static String boundedId(String value, String name, int max) {
        String normalized = boundedText(value, name, max);
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0," + (max - 1) + "}")) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return normalized;
    }

    private static String boundedText(String value, String name, int max) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > max || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(name + " is outside the safe bound");
        }
        return value;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("JRE must provide SHA-256", impossible);
        }
    }
}
