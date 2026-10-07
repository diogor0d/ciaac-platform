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

class ArenaWorldManifestTest {
    private static final int MAGIC = 0x4341414D;
    private static final int SCHEMA = 1;
    private static final int MAX_MANIFEST_BYTES = 128 * 1024;
    private static final String VERSION = "Paper 1.21.4-α";
    private static final UUID WORLD = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test void sortsRegionsAndRoundTripsCanonicalDefensiveSnapshots() {
        ProtectedRegion participant = participant("arena-floor", bounds(WORLD, 0, 0, 0, 5, 5, 5));
        ProtectedRegion spectator = spectator("spectator-seats", bounds(WORLD, 10, 0, 0, 15, 5, 5));
        List<ProtectedRegion> input = new ArrayList<>(List.of(spectator, participant));

        ArenaWorldManifest manifest = new ArenaWorldManifest(VERSION, input);
        input.clear();
        assertEquals(List.of("arena-floor", "spectator-seats"), manifest.regions().stream()
                .map(ProtectedRegion::id).toList());
        assertThrows(UnsupportedOperationException.class, () -> manifest.regions().clear());

        byte[] encoded = manifest.encode();
        byte[] expected = encoded.clone();
        ArenaWorldManifest decoded = ArenaWorldManifest.decode(encoded);
        encoded[0] ^= 0x7f;
        assertEquals(manifest, decoded);
        assertArrayEquals(expected, decoded.encode());
        assertThrows(UnsupportedOperationException.class, () -> decoded.regions().clear());
    }

    @Test void preservesExactPaperWorldAndCuboidIdentity() {
        ArenaWorldManifest manifest = validManifest();
        ArenaWorldManifest decoded = ArenaWorldManifest.decode(manifest.encode());

        assertEquals(VERSION, decoded.paperVersion());
        assertTrue(decoded.regions().stream().allMatch(region -> region.game() == GameKey.ARENA));
        assertTrue(decoded.regions().stream().allMatch(ProtectedRegion::immutable));
        assertTrue(decoded.regions().stream().allMatch(region -> region.bounds().worldId().equals(WORLD)));
        assertEquals(bounds(WORLD, 0, 0, 0, 5, 5, 5), decoded.regions().getFirst().bounds());
        assertEquals(bounds(WORLD, 10, 0, 0, 15, 5, 5), decoded.regions().get(1).bounds());
    }

    @Test void validatesPaperVersionUtf8ByteBoundsAndBlankness() {
        List<ProtectedRegion> regions = validManifest().regions();
        assertEquals("α".repeat(128), new ArenaWorldManifest("α".repeat(128), regions).paperVersion());
        assertThrows(IllegalArgumentException.class, () -> new ArenaWorldManifest(" \t\n", regions));
        assertThrows(IllegalArgumentException.class, () -> new ArenaWorldManifest("v".repeat(257), regions));
        assertThrows(IllegalArgumentException.class, () -> new ArenaWorldManifest("α".repeat(129), regions));
        String invalidSurrogate = "bad" + (char) 0xd800;
        assertThrows(IllegalArgumentException.class, () -> new ArenaWorldManifest(invalidSurrogate, regions));
    }

    @Test void rejectsMutableForeignWrongWorldMissingExtraAndOverlappingRegions() {
        List<ProtectedRegion> valid = validManifest().regions();
        ProtectedRegion floor = valid.getFirst();
        ProtectedRegion seats = valid.get(1);

        assertThrows(IllegalArgumentException.class, () -> new ArenaWorldManifest(VERSION, List.of(floor)));
        assertThrows(IllegalArgumentException.class, () -> new ArenaWorldManifest(VERSION,
                List.of(floor, seats, participant("arena-extra", bounds(WORLD, 20, 0, 0, 25, 5, 5)))));
        assertThrows(IllegalArgumentException.class, () -> new ArenaWorldManifest(VERSION, List.of(floor,
                new ProtectedRegion("mutable-seat", GameKey.ARENA, seats.bounds(),
                        ProtectedRegionRole.SPECTATOR_PUBLIC, false))));
        assertThrows(IllegalArgumentException.class, () -> new ArenaWorldManifest(VERSION, List.of(floor,
                new ProtectedRegion("foreign-seat", GameKey.BUILD_BATTLE, seats.bounds(),
                        ProtectedRegionRole.SPECTATOR_PUBLIC, true))));
        assertThrows(IllegalArgumentException.class, () -> new ArenaWorldManifest(VERSION, List.of(floor,
                participant("second-floor", bounds(WORLD, 10, 0, 0, 15, 5, 5)))));
        assertThrows(IllegalArgumentException.class, () -> new ArenaWorldManifest(VERSION, List.of(floor,
                spectator("arena-floor", bounds(WORLD, 10, 0, 0, 15, 5, 5)))));
        assertThrows(IllegalArgumentException.class, () -> new ArenaWorldManifest(VERSION, List.of(floor,
                new ProtectedRegion("boundary", GameKey.ARENA, seats.bounds(),
                        ProtectedRegionRole.GAME_WORLD_BOUNDARY, true))));
        assertThrows(IllegalArgumentException.class, () -> new ArenaWorldManifest(VERSION, List.of(floor,
                spectator("other-world", bounds(UUID.randomUUID(), 10, 0, 0, 15, 5, 5)))));
        assertThrows(IllegalArgumentException.class, () -> new ArenaWorldManifest(VERSION, List.of(floor,
                spectator("overlap", bounds(WORLD, 5, 0, 0, 10, 5, 5)))));
    }

    @Test void rejectsMalformedNoncanonicalTruncatedAndOversizedPayloads() {
        byte[] canonical = validManifest().encode();

        byte[] wrongMagic = canonical.clone();
        wrongMagic[0] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> ArenaWorldManifest.decode(wrongMagic));

        byte[] wrongSchema = canonical.clone();
        ByteBuffer.wrap(wrongSchema).putInt(4, SCHEMA + 1);
        assertThrows(IllegalArgumentException.class, () -> ArenaWorldManifest.decode(wrongSchema));

        byte[] wrongRegionCount = canonical.clone();
        ByteBuffer.wrap(wrongRegionCount).putInt(12 + VERSION.getBytes(StandardCharsets.UTF_8).length, 1);
        assertThrows(IllegalArgumentException.class, () -> ArenaWorldManifest.decode(wrongRegionCount));

        byte[] invalidUtf8 = canonical.clone();
        invalidUtf8[12] = (byte) 0xc3; // The following ASCII byte is not a UTF-8 continuation.
        assertThrows(IllegalArgumentException.class, () -> ArenaWorldManifest.decode(invalidUtf8));

        byte[] oversizedVersion = ByteBuffer.allocate(12).putInt(MAGIC).putInt(SCHEMA).putInt(257).array();
        assertThrows(IllegalArgumentException.class, () -> ArenaWorldManifest.decode(oversizedVersion));

        byte[] noncanonicalBoolean = canonical.clone();
        noncanonicalBoolean[immutableFlagOffset(canonical)] = 2;
        assertThrows(IllegalArgumentException.class, () -> ArenaWorldManifest.decode(noncanonicalBoolean));

        byte[] invalidBounds = canonical.clone();
        ByteBuffer.wrap(invalidBounds).putInt(firstBoundsOffset(canonical), 99);
        assertThrows(IllegalArgumentException.class, () -> ArenaWorldManifest.decode(invalidBounds));

        assertThrows(IllegalArgumentException.class,
                () -> ArenaWorldManifest.decode(reverseRegionEntries(canonical)));
        assertThrows(IllegalArgumentException.class,
                () -> ArenaWorldManifest.decode(Arrays.copyOf(canonical, canonical.length - 1)));
        assertThrows(IllegalArgumentException.class,
                () -> ArenaWorldManifest.decode(Arrays.copyOf(canonical, canonical.length + 1)));
        assertThrows(IllegalArgumentException.class,
                () -> ArenaWorldManifest.decode(new byte[MAX_MANIFEST_BYTES + 1]));
    }

    private static ArenaWorldManifest validManifest() {
        return new ArenaWorldManifest(VERSION, List.of(
                participant("arena-floor", bounds(WORLD, 0, 0, 0, 5, 5, 5)),
                spectator("spectator-seats", bounds(WORLD, 10, 0, 0, 15, 5, 5))));
    }

    private static ProtectedRegion participant(String id, CuboidRegion bounds) {
        return new ProtectedRegion(id, GameKey.ARENA, bounds, ProtectedRegionRole.PARTICIPANT_ONLY, true);
    }

    private static ProtectedRegion spectator(String id, CuboidRegion bounds) {
        return new ProtectedRegion(id, GameKey.ARENA, bounds, ProtectedRegionRole.SPECTATOR_PUBLIC, true);
    }

    private static CuboidRegion bounds(UUID world, int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ) {
        return new CuboidRegion(world, minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static int immutableFlagOffset(byte[] encoded) {
        int position = 12 + VERSION.getBytes(StandardCharsets.UTF_8).length;
        position += Integer.BYTES; // region count
        position = skipString(encoded, position); // ID
        position = skipString(encoded, position); // game
        return skipString(encoded, position); // role, then immutable byte
    }

    private static int firstBoundsOffset(byte[] encoded) {
        int position = immutableFlagOffset(encoded) + 1 + 2 * Long.BYTES;
        return position;
    }

    private static byte[] reverseRegionEntries(byte[] encoded) {
        int prefixEnd = 12 + VERSION.getBytes(StandardCharsets.UTF_8).length + Integer.BYTES;
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
