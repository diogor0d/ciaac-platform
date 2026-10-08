package com.ciaac.minecraft.minigames.paper.isolation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pt.ciaac.minigames.paper.template.TemplateArtifact;

class ColorFloorWorldManifestTest {
    private static final int MAGIC = 0x43434657;
    private static final int SCHEMA = 1;
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final String VERSION = "Paper 26.2 build 84";
    private static final UUID WORLD = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test void sortsEntriesAndRoundTripsImmutableReviewedSnapshot() {
        ColorFloorWorldManifest manifest = validManifest();
        byte[] encoded = manifest.encode();
        byte[] expected = encoded.clone();
        ColorFloorWorldManifest decoded = ColorFloorWorldManifest.decode(encoded);
        encoded[0] ^= 1;
        assertEquals(manifest, decoded);
        assertEquals(WORLD, decoded.worldId());
        assertArrayEquals(expected, decoded.encode());
        assertNotEquals(0x43414157, ByteBuffer.wrap(expected).getInt());
        assertNotEquals(0x4341414D, ByteBuffer.wrap(expected).getInt());
        assertNotEquals(0x4341494D, ByteBuffer.wrap(expected).getInt());
        assertEquals(1, ByteBuffer.wrap(expected).getInt(firstEntryOffset(expected)));
    }

    @Test void rejectsWrongBoundaryWorldRoleGameAndArtifactIdentityGeometryOrChecksum() {
        TemplateArtifact artifact = artifact(WORLD, volume(WORLD, 1, 1, 1, 2, 1, 2), "immutable-floor-template",
                blocks("minecraft:red_concrete", "minecraft:blue_wool", "minecraft:green_concrete", "minecraft:yellow_wool"),
                colors("RED", "BLUE", "GREEN", "YELLOW"), null);
        assertThrows(IllegalArgumentException.class, () -> new ColorFloorWorldManifest(VERSION,
                new ProtectedRegion("boundary", GameKey.ARENA, bounds(WORLD, 0, 0, 0, 4, 5, 4), ProtectedRegionRole.PARTICIPANT_ONLY, true), artifact));
        assertThrows(IllegalArgumentException.class, () -> new ColorFloorWorldManifest(VERSION,
                new ProtectedRegion("boundary", GameKey.COLOR_FLOOR, bounds(WORLD, 0, 0, 0, 4, 5, 4), ProtectedRegionRole.SPECTATOR_PUBLIC, true), artifact));
        assertThrows(IllegalArgumentException.class, () -> new ColorFloorWorldManifest(VERSION,
                new ProtectedRegion("boundary", GameKey.COLOR_FLOOR, bounds(WORLD, 0, 0, 0, 4, 5, 4), ProtectedRegionRole.PARTICIPANT_ONLY, false), artifact));
        assertThrows(IllegalArgumentException.class, () -> new ColorFloorWorldManifest(VERSION,
                boundary(UUID.randomUUID()), artifact));
        assertThrows(IllegalArgumentException.class, () -> new ColorFloorWorldManifest(VERSION,
                boundary(WORLD), artifact(WORLD, volume(WORLD, 1, 1, 1, 2, 1, 2), "other-template",
                        blocks("minecraft:red_concrete", "minecraft:blue_wool", "minecraft:green_concrete", "minecraft:yellow_wool"),
                        colors("RED", "BLUE", "GREEN", "YELLOW"), null)));
        assertThrows(IllegalArgumentException.class, () -> new ColorFloorWorldManifest(VERSION, boundary(WORLD),
                artifact(WORLD, volume(WORLD, 1, 1, 1, 2, 2, 2), "immutable-floor-template",
                        blocks("minecraft:red_concrete", "minecraft:blue_wool", "minecraft:green_concrete", "minecraft:yellow_wool"),
                        colors("RED", "BLUE", "GREEN", "YELLOW"), null)));
        assertThrows(IllegalArgumentException.class, () -> new ColorFloorWorldManifest(VERSION, boundary(WORLD),
                artifact(WORLD, volume(WORLD, 4, 1, 1, 5, 1, 2), "immutable-floor-template",
                        blocks("minecraft:red_concrete", "minecraft:blue_wool", "minecraft:green_concrete", "minecraft:yellow_wool"),
                        colors("RED", "BLUE", "GREEN", "YELLOW"), null)));
        assertThrows(IllegalArgumentException.class, () -> new ColorFloorWorldManifest(VERSION, boundary(WORLD),
                artifact(WORLD, volume(WORLD, 1, 1, 1, 2, 1, 2), "immutable-floor-template",
                        blocks("minecraft:red_concrete", "minecraft:blue_wool", "minecraft:green_concrete", "minecraft:yellow_wool"),
                        colors("RED", "BLUE", "GREEN", "YELLOW"), "0".repeat(64))));
    }

    @Test void acceptsOnlyCanonicalSolidColoredConcreteOrWoolMatchingEachPaletteId() {
        assertThrows(IllegalArgumentException.class, () -> new ColorFloorWorldManifest(VERSION, boundary(WORLD),
                artifact(WORLD, volume(WORLD, 1, 1, 1, 2, 1, 2), "immutable-floor-template",
                        blocks("minecraft:chest", "minecraft:blue_wool", "minecraft:green_concrete", "minecraft:yellow_wool"),
                        colors("RED", "BLUE", "GREEN", "YELLOW"), null)));
        assertThrows(IllegalArgumentException.class, () -> new ColorFloorWorldManifest(VERSION, boundary(WORLD),
                artifact(WORLD, volume(WORLD, 1, 1, 1, 2, 1, 2), "immutable-floor-template",
                        blocks("minecraft:blue_concrete", "minecraft:blue_wool", "minecraft:green_concrete", "minecraft:yellow_wool"),
                        colors("RED", "BLUE", "GREEN", "YELLOW"), null)));
        assertThrows(IllegalArgumentException.class, () -> new ColorFloorWorldManifest(VERSION, boundary(WORLD),
                artifact(WORLD, volume(WORLD, 1, 1, 1, 2, 1, 2), "immutable-floor-template",
                        blocks("minecraft:red_concrete", "minecraft:blue_wool", "minecraft:green_concrete", "minecraft:yellow_wool"),
                        colors("RED", "UNKNOWN", "GREEN", "YELLOW"), null)));
        assertThrows(IllegalArgumentException.class, () -> new ColorFloorWorldManifest(VERSION, boundary(WORLD),
                artifact(WORLD, volume(WORLD, 1, 1, 1, 2, 1, 2), "immutable-floor-template",
                        blocks("minecraft:red_concrete", "minecraft:blue_wool", "minecraft:green_concrete", "minecraft:yellow_wool"),
                        Map.of(), null)));
    }

    @Test void supportsEveryFloorColorEnumWithItsMatchingConcreteOrWoolMaterial() {
        String[] ids = {"RED", "BLUE", "GREEN", "YELLOW", "PURPLE", "ORANGE", "CYAN"};
        String[] materials = {"minecraft:red_concrete", "minecraft:blue_wool", "minecraft:green_concrete",
                "minecraft:yellow_wool", "minecraft:purple_concrete", "minecraft:orange_wool", "minecraft:cyan_concrete"};
        Map<TemplateArtifact.BlockCoordinate, String> blocks = new LinkedHashMap<>();
        Map<TemplateArtifact.BlockCoordinate, String> colors = new LinkedHashMap<>();
        for (int index = 0; index < ids.length; index++) {
            TemplateArtifact.BlockCoordinate coordinate = coordinate(index, 1, 1);
            blocks.put(coordinate, materials[index]);
            colors.put(coordinate, ids[index]);
        }
        CuboidRegion boundaryVolume = bounds(WORLD, 0, 0, 0, 6, 5, 2);
        ProtectedRegion boundary = new ProtectedRegion("color-floor-boundary", GameKey.COLOR_FLOOR,
                boundaryVolume, ProtectedRegionRole.PARTICIPANT_ONLY, true);
        TemplateArtifact allColors = artifact(WORLD, volume(WORLD, 0, 1, 1, 6, 1, 1),
                "immutable-floor-template", blocks, colors, null);
        assertEquals(7, new ColorFloorWorldManifest(VERSION, boundary, allColors).artifact().colorIds().size());
    }

    @Test void rejectsMalformedNoncanonicalTruncatedOversizedAndTamperedPayloads() {
        byte[] canonical = validManifest().encode();
        byte[] wrongMagic = canonical.clone(); wrongMagic[0] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> ColorFloorWorldManifest.decode(wrongMagic));
        byte[] wrongSchema = canonical.clone(); ByteBuffer.wrap(wrongSchema).putInt(4, SCHEMA + 1);
        assertThrows(IllegalArgumentException.class, () -> ColorFloorWorldManifest.decode(wrongSchema));
        byte[] unknownFlag = canonical.clone(); unknownFlag[boundaryFlagOffset(canonical)] = 2;
        assertThrows(IllegalArgumentException.class, () -> ColorFloorWorldManifest.decode(unknownFlag));
        byte[] invalidUtf8 = canonical.clone(); invalidUtf8[12] = (byte) 0xc3;
        assertThrows(IllegalArgumentException.class, () -> ColorFloorWorldManifest.decode(invalidUtf8));
        int colorOffset = firstColorStringOffset(canonical);
        byte[] invalidColor = replaceString(canonical, colorOffset,
                "X".repeat(ByteBuffer.wrap(canonical).getInt(colorOffset)));
        assertThrows(IllegalArgumentException.class, () -> ColorFloorWorldManifest.decode(invalidColor));
        int blockOffset = firstBlockStringOffset(canonical);
        byte[] invalidMaterial = replaceString(canonical, blockOffset,
                "?".repeat(ByteBuffer.wrap(canonical).getInt(blockOffset)));
        assertThrows(IllegalArgumentException.class, () -> ColorFloorWorldManifest.decode(invalidMaterial));
        byte[] outside = canonical.clone(); ByteBuffer.wrap(outside).putInt(firstEntryOffset(canonical), 99);
        assertThrows(IllegalArgumentException.class, () -> ColorFloorWorldManifest.decode(outside));
        byte[] unsorted = reverseEntries(canonical);
        assertThrows(IllegalArgumentException.class, () -> ColorFloorWorldManifest.decode(unsorted));
        byte[] badChecksum = canonical.clone();
        int checksumCharacter = checksumPayloadOffset(canonical);
        badChecksum[checksumCharacter] = badChecksum[checksumCharacter] == '0' ? (byte) '1' : (byte) '0';
        assertThrows(IllegalArgumentException.class, () -> ColorFloorWorldManifest.decode(badChecksum));
        byte[] trailing = java.util.Arrays.copyOf(canonical, canonical.length + 1);
        assertThrows(IllegalArgumentException.class, () -> ColorFloorWorldManifest.decode(trailing));
        assertThrows(IllegalArgumentException.class, () -> ColorFloorWorldManifest.decode(
                java.util.Arrays.copyOf(canonical, canonical.length - 1)));
        byte[] tooMany = canonical.clone(); ByteBuffer.wrap(tooMany).putInt(countOffset(canonical), TemplateArtifact.MAX_BLOCKS + 1);
        assertThrows(IllegalArgumentException.class, () -> ColorFloorWorldManifest.decode(tooMany));
        assertThrows(IllegalArgumentException.class, () -> ColorFloorWorldManifest.decode(new byte[MAX_BYTES + 1]));
    }

    @Test void enforcesStrictUtf8AndByteBounds() {
        assertThrows(IllegalArgumentException.class, () -> new ColorFloorWorldManifest("bad" + (char) 0xd800,
                boundary(WORLD), validArtifact()));
        assertThrows(IllegalArgumentException.class, () -> new ColorFloorWorldManifest("α".repeat(129),
                boundary(WORLD), validArtifact()));
    }

    private static ColorFloorWorldManifest validManifest() {
        return new ColorFloorWorldManifest(VERSION, boundary(WORLD), validArtifact());
    }

    private static TemplateArtifact validArtifact() {
        return artifact(WORLD, volume(WORLD, 1, 1, 1, 2, 1, 2), "immutable-floor-template",
                blocks("minecraft:red_concrete", "minecraft:blue_wool", "minecraft:green_concrete", "minecraft:yellow_wool"),
                colors("RED", "BLUE", "GREEN", "YELLOW"), null);
    }

    private static TemplateArtifact artifact(UUID world, CuboidRegion volume, String id,
            Map<TemplateArtifact.BlockCoordinate, String> blocks, Map<TemplateArtifact.BlockCoordinate, String> colors,
            String overrideChecksum) {
        TemplateArtifact draft = new TemplateArtifact(id, "floor-r1", world, "minigames", volume,
                "0".repeat(64), blocks, colors);
        String checksum = overrideChecksum == null ? draft.calculateChecksum() : overrideChecksum;
        return new TemplateArtifact(id, "floor-r1", world, "minigames", volume, checksum, blocks, colors);
    }

    private static ProtectedRegion boundary(UUID world) {
        return new ProtectedRegion("color-floor-boundary", GameKey.COLOR_FLOOR, bounds(world, 0, 0, 0, 4, 5, 4),
                ProtectedRegionRole.PARTICIPANT_ONLY, true);
    }

    private static CuboidRegion bounds(UUID world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return new CuboidRegion(world, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static Map<TemplateArtifact.BlockCoordinate, String> blocks(String... data) {
        Map<TemplateArtifact.BlockCoordinate, String> values = new LinkedHashMap<>();
        values.put(coordinate(2, 1, 2), data[0]); values.put(coordinate(1, 1, 2), data[1]);
        values.put(coordinate(2, 1, 1), data[2]); values.put(coordinate(1, 1, 1), data[3]);
        return values;
    }

    private static Map<TemplateArtifact.BlockCoordinate, String> colors(String... values) {
        Map<TemplateArtifact.BlockCoordinate, String> colors = new LinkedHashMap<>();
        colors.put(coordinate(2, 1, 2), values[0]); colors.put(coordinate(1, 1, 2), values[1]);
        colors.put(coordinate(2, 1, 1), values[2]); colors.put(coordinate(1, 1, 1), values[3]);
        return colors;
    }

    private static TemplateArtifact.BlockCoordinate coordinate(int x, int y, int z) {
        return new TemplateArtifact.BlockCoordinate(x, y, z);
    }
    private static CuboidRegion volume(UUID world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return bounds(world, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static int boundaryFlagOffset(byte[] bytes) {
        int position = 8;
        position = skipString(bytes, position); position = skipString(bytes, position);
        position = skipString(bytes, position); position = skipString(bytes, position);
        return position;
    }
    private static int firstEntryOffset(byte[] bytes) { return countOffset(bytes) + Integer.BYTES; }
    private static int countOffset(byte[] bytes) {
        int position = boundaryFlagOffset(bytes) + 1 + 16 + 6 * Integer.BYTES;
        position = skipString(bytes, position); position = skipString(bytes, position) + 16;
        position = skipString(bytes, position) + 16 + 6 * Integer.BYTES;
        return skipString(bytes, position);
    }
    private static int firstBlockStringOffset(byte[] bytes) { return firstEntryOffset(bytes) + 3 * Integer.BYTES; }
    private static int firstColorStringOffset(byte[] bytes) {
        return skipString(bytes, firstBlockStringOffset(bytes));
    }
    private static int checksumPayloadOffset(byte[] bytes) {
        int position = boundaryFlagOffset(bytes) + 1 + 16 + 6 * Integer.BYTES;
        position = skipString(bytes, position); position = skipString(bytes, position) + 16;
        position = skipString(bytes, position) + 16 + 6 * Integer.BYTES;
        return position + Integer.BYTES;
    }
    private static int skipString(byte[] bytes, int position) { return position + Integer.BYTES + ByteBuffer.wrap(bytes).getInt(position); }
    private static byte[] replaceString(byte[] bytes, int offset, String replacement) {
        int length = ByteBuffer.wrap(bytes).getInt(offset);
        byte[] value = replacement.getBytes(StandardCharsets.UTF_8);
        if (length != value.length) throw new IllegalArgumentException("replacement must preserve encoded length");
        byte[] changed = bytes.clone(); System.arraycopy(value, 0, changed, offset + Integer.BYTES, value.length); return changed;
    }
    private static byte[] reverseEntries(byte[] bytes) {
        int start = firstEntryOffset(bytes);
        int firstEnd = entryEnd(bytes, start);
        int secondEnd = entryEnd(bytes, firstEnd);
        int thirdEnd = entryEnd(bytes, secondEnd);
        int fourthEnd = entryEnd(bytes, thirdEnd);
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        out.write(bytes, 0, start);
        out.write(bytes, thirdEnd, fourthEnd - thirdEnd);
        out.write(bytes, secondEnd, thirdEnd - secondEnd);
        out.write(bytes, firstEnd, secondEnd - firstEnd);
        out.write(bytes, start, firstEnd - start);
        return out.toByteArray();
    }
    private static int entryEnd(byte[] bytes, int position) {
        position += 3 * Integer.BYTES;
        position = skipString(bytes, position);
        return skipString(bytes, position);
    }
}
