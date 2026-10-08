package com.ciaac.minecraft.minigames.paper.isolation;

import static org.junit.jupiter.api.Assertions.*;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArcheryWorldManifestTest {
    private static final int MAGIC = 0x43414157;
    private static final int SCHEMA = 1;
    private static final int MAX_MANIFEST_BYTES = 128 * 1024;
    private static final String VERSION = "Paper 26.2 build 84";
    private static final UUID WORLD = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test void sortsRegionsAndRoundTripsCanonicalDefensiveSnapshots() {
        ProtectedRegion second = region("lane-second", bounds(WORLD, 10, 0, 0, 11, 2, 2));
        ProtectedRegion first = region("lane-first", bounds(WORLD, 0, 0, 0, 1, 2, 2));
        List<ProtectedRegion> input = new ArrayList<>(List.of(second, first));

        ArcheryWorldManifest manifest = new ArcheryWorldManifest(VERSION, input);
        input.clear();
        assertEquals(List.of("lane-first", "lane-second"), manifest.regions().stream()
                .map(ProtectedRegion::id).toList());
        assertThrows(UnsupportedOperationException.class, () -> manifest.regions().clear());

        byte[] encoded = manifest.encode();
        byte[] expected = encoded.clone();
        ArcheryWorldManifest decoded = ArcheryWorldManifest.decode(encoded);
        encoded[0] ^= 0x7f;
        assertEquals(manifest, decoded);
        assertEquals(WORLD, decoded.worldId());
        assertArrayEquals(expected, decoded.encode());
        assertThrows(UnsupportedOperationException.class, () -> decoded.regions().clear());
        assertNotEquals(0x4341414D, ByteBuffer.wrap(expected).getInt()); // CAAM
        assertNotEquals(0x4341494D, ByteBuffer.wrap(expected).getInt()); // CAIM
    }

    @Test void supportsOneThrough256NonoverlappingRegions() {
        assertEquals(1, new ArcheryWorldManifest(VERSION, List.of(region("lane-0",
                bounds(WORLD, 0, 0, 0, 1, 1, 1)))).regions().size());
        List<ProtectedRegion> maximum = new ArrayList<>();
        for (int index = 0; index < 256; index++) {
            int x = index * 3;
            maximum.add(region("lane-" + index, bounds(WORLD, x, 0, 0, x + 1, 1, 1)));
        }
        ArcheryWorldManifest manifest = new ArcheryWorldManifest(VERSION, maximum);
        assertEquals(256, ArcheryWorldManifest.decode(manifest.encode()).regions().size());
        assertThrows(IllegalArgumentException.class,
                () -> new ArcheryWorldManifest(VERSION, List.of()));
        maximum.add(region("lane-extra", bounds(WORLD, 1000, 0, 0, 1001, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new ArcheryWorldManifest(VERSION, maximum));
    }

    @Test void validatesVersionUtf8ByteBoundsAndBlankness() {
        List<ProtectedRegion> regions = List.of(region("lane-0", bounds(WORLD, 0, 0, 0, 1, 1, 1)));
        assertEquals("α".repeat(128), new ArcheryWorldManifest("α".repeat(128), regions).paperVersion());
        assertThrows(IllegalArgumentException.class, () -> new ArcheryWorldManifest(" \t\n", regions));
        assertThrows(IllegalArgumentException.class, () -> new ArcheryWorldManifest("v".repeat(257), regions));
        assertThrows(IllegalArgumentException.class, () -> new ArcheryWorldManifest("α".repeat(129), regions));
        assertThrows(IllegalArgumentException.class,
                () -> new ArcheryWorldManifest("bad" + (char) 0xd800, regions));
    }

    @Test void rejectsWrongGameRoleMutableDuplicateCrossWorldAndOverlappingRegions() {
        ProtectedRegion first = region("lane-first", bounds(WORLD, 0, 0, 0, 5, 5, 5));
        ProtectedRegion second = region("lane-second", bounds(WORLD, 10, 0, 0, 15, 5, 5));

        assertThrows(IllegalArgumentException.class, () -> new ArcheryWorldManifest(VERSION,
                List.of(new ProtectedRegion("foreign", GameKey.ARENA, first.bounds(),
                        ProtectedRegionRole.PARTICIPANT_ONLY, true))));
        assertThrows(IllegalArgumentException.class, () -> new ArcheryWorldManifest(VERSION,
                List.of(new ProtectedRegion("public", GameKey.ARCHERY_RANGE, first.bounds(),
                        ProtectedRegionRole.SPECTATOR_PUBLIC, true))));
        assertThrows(IllegalArgumentException.class, () -> new ArcheryWorldManifest(VERSION,
                List.of(new ProtectedRegion("mutable", GameKey.ARCHERY_RANGE, first.bounds(),
                        ProtectedRegionRole.PARTICIPANT_ONLY, false))));
        assertThrows(IllegalArgumentException.class, () -> new ArcheryWorldManifest(VERSION,
                List.of(first, region("lane-first", bounds(WORLD, 10, 0, 0, 15, 5, 5)))));
        assertThrows(IllegalArgumentException.class, () -> new ArcheryWorldManifest(VERSION,
                List.of(first, region("other-world", bounds(UUID.randomUUID(), 10, 0, 0, 15, 5, 5)))));
        assertThrows(IllegalArgumentException.class, () -> new ArcheryWorldManifest(VERSION,
                List.of(first, region("overlap", bounds(WORLD, 5, 0, 0, 10, 5, 5)))));
        assertDoesNotThrow(() -> new ArcheryWorldManifest(VERSION, List.of(first, second)));
    }

    @Test void rejectsMalformedNoncanonicalTruncatedAndOversizedPayloads() {
        byte[] canonical = validManifest().encode();

        byte[] wrongMagic = canonical.clone();
        wrongMagic[0] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> ArcheryWorldManifest.decode(wrongMagic));

        byte[] wrongSchema = canonical.clone();
        ByteBuffer.wrap(wrongSchema).putInt(4, SCHEMA + 1);
        assertThrows(IllegalArgumentException.class, () -> ArcheryWorldManifest.decode(wrongSchema));

        byte[] zeroRegions = canonical.clone();
        ByteBuffer.wrap(zeroRegions).putInt(countOffset(canonical), 0);
        assertThrows(IllegalArgumentException.class, () -> ArcheryWorldManifest.decode(zeroRegions));

        byte[] tooManyRegions = canonical.clone();
        ByteBuffer.wrap(tooManyRegions).putInt(countOffset(canonical), 257);
        assertThrows(IllegalArgumentException.class, () -> ArcheryWorldManifest.decode(tooManyRegions));

        byte[] invalidUtf8 = canonical.clone();
        invalidUtf8[12] = (byte) 0xc3; // The following ASCII byte is not a UTF-8 continuation.
        assertThrows(IllegalArgumentException.class, () -> ArcheryWorldManifest.decode(invalidUtf8));

        byte[] oversizedVersion = ByteBuffer.allocate(12).putInt(MAGIC).putInt(SCHEMA).putInt(257).array();
        assertThrows(IllegalArgumentException.class, () -> ArcheryWorldManifest.decode(oversizedVersion));

        byte[] wrongGame = replaceFirstRegionString(canonical, 1, "ARENA");
        assertThrows(IllegalArgumentException.class, () -> ArcheryWorldManifest.decode(wrongGame));

        byte[] wrongRole = replaceFirstRegionString(canonical, 2, "SPECTATOR_PUBLIC");
        assertThrows(IllegalArgumentException.class, () -> ArcheryWorldManifest.decode(wrongRole));

        byte[] mutableFlag = canonical.clone();
        mutableFlag[immutableFlagOffset(canonical)] = 0;
        assertThrows(IllegalArgumentException.class, () -> ArcheryWorldManifest.decode(mutableFlag));

        byte[] noncanonicalFlag = canonical.clone();
        noncanonicalFlag[immutableFlagOffset(canonical)] = 2;
        assertThrows(IllegalArgumentException.class, () -> ArcheryWorldManifest.decode(noncanonicalFlag));

        byte[] invalidBounds = canonical.clone();
        ByteBuffer.wrap(invalidBounds).putInt(firstBoundsOffset(canonical), 99);
        assertThrows(IllegalArgumentException.class, () -> ArcheryWorldManifest.decode(invalidBounds));

        assertThrows(IllegalArgumentException.class,
                () -> ArcheryWorldManifest.decode(reverseRegionEntries(canonical)));
        byte[] trailing = Arrays.copyOf(canonical, canonical.length + 1);
        assertThrows(IllegalArgumentException.class, () -> ArcheryWorldManifest.decode(trailing));
        assertThrows(IllegalArgumentException.class,
                () -> ArcheryWorldManifest.decode(Arrays.copyOf(canonical, canonical.length - 1)));
        assertThrows(IllegalArgumentException.class,
                () -> ArcheryWorldManifest.decode(new byte[MAX_MANIFEST_BYTES + 1]));
    }

    private static ArcheryWorldManifest validManifest() {
        return new ArcheryWorldManifest(VERSION, List.of(
                region("lane-first", bounds(WORLD, 0, 0, 0, 5, 5, 5)),
                region("lane-second", bounds(WORLD, 10, 0, 0, 15, 5, 5))));
    }

    private static ProtectedRegion region(String id, CuboidRegion bounds) {
        return new ProtectedRegion(id, GameKey.ARCHERY_RANGE, bounds,
                ProtectedRegionRole.PARTICIPANT_ONLY, true);
    }

    private static CuboidRegion bounds(UUID world, int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ) {
        return new CuboidRegion(world, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static int countOffset(byte[] encoded) {
        return 12 + ByteBuffer.wrap(encoded).getInt(8);
    }

    private static int firstEntryOffset(byte[] encoded) {
        return countOffset(encoded) + Integer.BYTES;
    }

    private static int immutableFlagOffset(byte[] encoded) {
        int position = firstEntryOffset(encoded);
        position = skipString(encoded, position); // ID
        position = skipString(encoded, position); // game
        return skipString(encoded, position); // role, then immutable byte
    }

    private static int firstBoundsOffset(byte[] encoded) {
        return immutableFlagOffset(encoded) + 1 + 2 * Long.BYTES;
    }

    private static byte[] replaceFirstRegionString(byte[] encoded, int stringNumber, String replacement) {
        int position = firstEntryOffset(encoded);
        for (int index = 0; index < stringNumber; index++) position = skipString(encoded, position);
        int length = ByteBuffer.wrap(encoded).getInt(position);
        int end = position + Integer.BYTES + length;
        byte[] value = replacement.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream result = new ByteArrayOutputStream(encoded.length + value.length - length);
        result.write(encoded, 0, position);
        result.write(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array(), 0, Integer.BYTES);
        result.write(value, 0, value.length);
        result.write(encoded, end, encoded.length - end);
        return result.toByteArray();
    }

    private static byte[] reverseRegionEntries(byte[] encoded) {
        int prefixEnd = firstEntryOffset(encoded);
        int firstEnd = entryEnd(encoded, prefixEnd);
        int secondEnd = entryEnd(encoded, firstEnd);
        assertEquals(encoded.length, secondEnd);
        ByteArrayOutputStream reversed = new ByteArrayOutputStream(encoded.length);
        reversed.write(encoded, 0, prefixEnd);
        reversed.write(encoded, firstEnd, secondEnd - firstEnd);
        reversed.write(encoded, prefixEnd, firstEnd - prefixEnd);
        return reversed.toByteArray();
    }

    private static int entryEnd(byte[] encoded, int start) {
        int position = start;
        position = skipString(encoded, position); // ID
        position = skipString(encoded, position); // game
        position = skipString(encoded, position); // role
        return position + 1 + 2 * Long.BYTES + 6 * Integer.BYTES;
    }

    private static int skipString(byte[] encoded, int start) {
        int length = ByteBuffer.wrap(encoded).getInt(start);
        return start + Integer.BYTES + length;
    }
}
