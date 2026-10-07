package com.ciaac.minecraft.minigames.paper.isolation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SimpleClaimStateAuthorityTest {
    private static final UUID PLAYER = UUID.fromString("4eec5106-659c-464e-a551-6524693e88b2");
    private static final UUID OTHER = UUID.fromString("bc052205-8ef2-42ea-a6dc-dd7c516f0fd7");
    private static final UUID WORLD = UUID.fromString("e90f5fd5-4169-46a4-9c42-c96e89cdfbc2");

    @Test
    void canonicalizesUnorderedFieldsButPreservesOrderedGroupRules() {
        var first = state(claim(PLAYER, 7, "Land", false, 0, 1), policy(false));
        var shuffledClaim = new SimpleClaimStateAuthority.Claim(PLAYER, 7, "owner", "Land", "description", WORLD,
                "world", new SimpleClaimStateAuthority.Position(1, 2, 3, 4, 5),
                Set.of(chunk(2), chunk(1)), Set.of(OTHER),
                Map.of("default", Map.of("build", false, "use", true)), false, 0, Set.of());
        var shuffled = state(shuffledClaim, policy(true));
        byte[] left = SimpleClaimStateAuthority.encode(first), right = SimpleClaimStateAuthority.encode(shuffled);
        assertArrayEquals(left, right);
        assertDoesNotThrow(() -> authority(shuffled).validate(PLAYER, left));
        assertFalse(java.util.Arrays.equals(left,
                SimpleClaimStateAuthority.encode(state(claim(PLAYER, 7, "Land", false, 0, 1), policy(false, true)))));
    }

    @Test
    void capturesEveryClaimAndPolicyFieldIncludingFalseSaleAndExplicitDenies() {
        var baseline = claim(PLAYER, 7, "Land", false, 0, 1);
        byte[] original = snapshot(baseline, policy(false));
        var otherOwner = claim(OTHER, 7, "Land", false, 0, 1);
        assertDrift(original, replace(otherOwner, otherOwner.ownerName(), otherOwner.name(), otherOwner.description(), WORLD, "world", otherOwner.location(), otherOwner.chunks(), Set.of(PLAYER), otherOwner.permissions(), false, 0, otherOwner.bans()));
        assertDrift(original, new SimpleClaimStateAuthority.Claim(PLAYER, 8, baseline.ownerName(), baseline.name(), baseline.description(), WORLD, "world", baseline.location(), baseline.chunks(), baseline.members(), baseline.permissions(), false, 0, baseline.bans()));
        assertDrift(original, replace(baseline, "renamed-owner", baseline.name(), baseline.description(), WORLD, "world", baseline.location(), baseline.chunks(), baseline.members(), baseline.permissions(), false, 0, baseline.bans()));
        assertDrift(original, replace(baseline, baseline.ownerName(), "renamed", baseline.description(), WORLD, "world", baseline.location(), baseline.chunks(), baseline.members(), baseline.permissions(), false, 0, baseline.bans()));
        assertDrift(original, replace(baseline, baseline.ownerName(), baseline.name(), "changed description", WORLD, "world", baseline.location(), baseline.chunks(), baseline.members(), baseline.permissions(), false, 0, baseline.bans()));
        assertDrift(original, replace(baseline, baseline.ownerName(), baseline.name(), baseline.description(), OTHER, "world", baseline.location(), Set.of(), baseline.members(), baseline.permissions(), false, 0, baseline.bans()));
        assertDrift(original, replace(baseline, baseline.ownerName(), baseline.name(), baseline.description(), WORLD, "renamed-world", baseline.location(), Set.of(new SimpleClaimStateAuthority.Chunk(WORLD, "renamed-world", 1, 0), new SimpleClaimStateAuthority.Chunk(WORLD, "renamed-world", 2, 0)), baseline.members(), baseline.permissions(), false, 0, baseline.bans()));
        assertDrift(original, replace(baseline, baseline.ownerName(), baseline.name(), baseline.description(), WORLD, "world", new SimpleClaimStateAuthority.Position(1.5, 2, 3, 4, 5), baseline.chunks(), baseline.members(), baseline.permissions(), false, 0, baseline.bans()));
        assertDrift(original, replace(baseline, baseline.ownerName(), baseline.name(), baseline.description(), WORLD, "world", new SimpleClaimStateAuthority.Position(1, 2.5, 3, 4, 5), baseline.chunks(), baseline.members(), baseline.permissions(), false, 0, baseline.bans()));
        assertDrift(original, replace(baseline, baseline.ownerName(), baseline.name(), baseline.description(), WORLD, "world", new SimpleClaimStateAuthority.Position(1, 2, 3.5, 4, 5), baseline.chunks(), baseline.members(), baseline.permissions(), false, 0, baseline.bans()));
        assertDrift(original, replace(baseline, baseline.ownerName(), baseline.name(), baseline.description(), WORLD, "world", new SimpleClaimStateAuthority.Position(1, 2, 3, 4.5f, 5), baseline.chunks(), baseline.members(), baseline.permissions(), false, 0, baseline.bans()));
        assertDrift(original, replace(baseline, baseline.ownerName(), baseline.name(), baseline.description(), WORLD, "world", new SimpleClaimStateAuthority.Position(1, 2, 3, 4, 5.5f), baseline.chunks(), baseline.members(), baseline.permissions(), false, 0, baseline.bans()));
        assertDrift(original, replace(baseline, baseline.ownerName(), baseline.name(), baseline.description(), WORLD, "world", baseline.location(), Set.of(chunk(3)), baseline.members(), baseline.permissions(), false, 0, baseline.bans()));
        assertThrows(IllegalArgumentException.class, () -> snapshot(replace(baseline, baseline.ownerName(), baseline.name(), baseline.description(), WORLD, "world", baseline.location(), Set.of(new SimpleClaimStateAuthority.Chunk(WORLD, "renamed", 1, 0)), baseline.members(), baseline.permissions(), false, 0, baseline.bans()), policy(false)));
        assertDrift(original, replace(baseline, baseline.ownerName(), baseline.name(), baseline.description(), WORLD, "world", baseline.location(), Set.of(new SimpleClaimStateAuthority.Chunk(WORLD, "world", 1, 1)), baseline.members(), baseline.permissions(), false, 0, baseline.bans()));
        assertDrift(original, replace(baseline, baseline.ownerName(), baseline.name(), baseline.description(), WORLD, "world", baseline.location(), Set.of(new SimpleClaimStateAuthority.Chunk(WORLD, "world", 1, 0), new SimpleClaimStateAuthority.Chunk(WORLD, "world", 2, 1)), baseline.members(), baseline.permissions(), false, 0, baseline.bans()));
        assertDrift(original, replace(baseline, baseline.ownerName(), baseline.name(), baseline.description(), WORLD, "world", baseline.location(), baseline.chunks(), Set.of(PLAYER), baseline.permissions(), false, 0, baseline.bans()));
        assertDrift(original, replace(baseline, baseline.ownerName(), baseline.name(), baseline.description(), WORLD, "world", baseline.location(), baseline.chunks(), baseline.members(), Map.of("default", Map.of("build", false)), false, 0, baseline.bans()));
        assertDrift(original, replace(baseline, baseline.ownerName(), baseline.name(), baseline.description(), WORLD, "world", baseline.location(), baseline.chunks(), baseline.members(), Map.of("renamed-group", baseline.permissions().get("default")), false, 0, baseline.bans()));
        assertDrift(original, replace(baseline, baseline.ownerName(), baseline.name(), baseline.description(), WORLD, "world", baseline.location(), baseline.chunks(), baseline.members(), Map.of("default", Map.of("build", true, "use", true)), false, 0, baseline.bans()));
        assertDrift(original, replace(baseline, baseline.ownerName(), baseline.name(), baseline.description(), WORLD, "world", baseline.location(), baseline.chunks(), baseline.members(), baseline.permissions(), true, 0, baseline.bans()));
        assertDrift(original, replace(baseline, baseline.ownerName(), baseline.name(), baseline.description(), WORLD, "world", baseline.location(), baseline.chunks(), baseline.members(), baseline.permissions(), false, 2, baseline.bans()));
        assertDrift(original, replace(baseline, baseline.ownerName(), baseline.name(), baseline.description(), WORLD, "world", baseline.location(), baseline.chunks(), baseline.members(), baseline.permissions(), false, 0, Set.of(OTHER)));
        assertDrift(original, baseline, policy(false, true));
        assertDrift(original, baseline, new SimpleClaimStateAuthority.Policy(Map.of("build", "deny"), List.of(new SimpleClaimStateAuthority.GroupRule("owner", "default")), Map.of("default", Map.of("price", 1.5)), Map.of("visit", 1.0)));
        assertDrift(original, baseline, new SimpleClaimStateAuthority.Policy(Map.of("claim", "allow", "build", "allow"), policy(false).orderedGroups(), policy(false).groupSettings(), policy(false).playerSettings()));
        assertDrift(original, baseline, new SimpleClaimStateAuthority.Policy(policy(false).defaults(), policy(false).orderedGroups(), Map.of("changed", Map.of("price", 1.0)), policy(false).playerSettings()));
        assertDrift(original, baseline, new SimpleClaimStateAuthority.Policy(policy(false).defaults(), policy(false).orderedGroups(), Map.of("default", Map.of("changed", 1.0)), policy(false).playerSettings()));
        assertDrift(original, baseline, new SimpleClaimStateAuthority.Policy(policy(false).defaults(), policy(false).orderedGroups(), Map.of("default", Map.of("price", 2.0)), policy(false).playerSettings()));
        assertDrift(original, baseline, new SimpleClaimStateAuthority.Policy(policy(false).defaults(), policy(false).orderedGroups(), policy(false).groupSettings(), Map.of("changed", 1.0)));
        assertDrift(original, baseline, new SimpleClaimStateAuthority.Policy(policy(false).defaults(), policy(false).orderedGroups(), policy(false).groupSettings(), Map.of("visit", 2.0)));
    }

    @Test
    void includesOnlyRelevantOwnerMemberOrBannedClaimsAndUsesCompositeIdentity() {
        var shared = new SimpleClaimStateAuthority.Claim(OTHER, 7, "friend", "Shared", "", WORLD, "world",
                new SimpleClaimStateAuthority.Position(0, 0, 0, 0, 0), Set.of(), Set.of(PLAYER), Map.of(), false, 0, Set.of());
        var banned = new SimpleClaimStateAuthority.Claim(UUID.fromString("b7f1102f-1fc8-4da2-8733-5c2a7927a961"), 7, "friend", "Banned", "", WORLD, "world",
                new SimpleClaimStateAuthority.Position(0, 0, 0, 0, 0), Set.of(), Set.of(), Map.of(), false, 0, Set.of(PLAYER));
        byte[] payload = SimpleClaimStateAuthority.encode(new SimpleClaimStateAuthority.State(PLAYER,
                List.of(shared, banned, claim(PLAYER, 7, "Mine", false, 0, 1)), policy(false)));
        assertDoesNotThrow(() -> authority(state(claim(PLAYER, 7, "Mine", false, 0, 1), policy(false))).validate(PLAYER, payload));
        assertThrows(IllegalArgumentException.class, () -> SimpleClaimStateAuthority.encode(
                new SimpleClaimStateAuthority.State(PLAYER, List.of(unrelated()), policy(false))));
        assertThrows(IllegalArgumentException.class, () -> SimpleClaimStateAuthority.encode(
                new SimpleClaimStateAuthority.State(PLAYER, List.of(claim(PLAYER, 7, "a", false, 0, 1), claim(PLAYER, 7, "b", false, 0, 1)), policy(false))));
    }

    @Test
    void rejectsMalformedHeadersCanonicalityBoundsAndInvalidValues() throws Exception {
        byte[] valid = snapshot(claim(PLAYER, 7, "Land", false, 0, 1), policy(false));
        assertThrows(IllegalArgumentException.class, () -> authority(state(claim(PLAYER, 7, "Land", false, 0, 1), policy(false))).validate(OTHER, valid));
        assertInvalid(java.util.Arrays.copyOf(valid, valid.length - 1));
        assertInvalid(java.util.Arrays.copyOf(valid, valid.length + 1));
        byte[] version = valid.clone(); version[4] = 2; assertInvalid(version);
        byte[] payload = countPayload(SimpleClaimStateAuthority.MAX_CLAIMS + 1);
        assertInvalid(payload);
        assertThrows(IllegalArgumentException.class, () -> snapshot(replace(claim(PLAYER, 7, "Land", false, 0, 1), " ", "Land", "", WORLD, "world", new SimpleClaimStateAuthority.Position(0, 0, 0, 0, 0), Set.of(), Set.of(), Map.of(), false, 0, Set.of()), policy(false)));
        assertThrows(IllegalArgumentException.class, () -> snapshot(replace(claim(PLAYER, 7, "Land", false, 0, 1), "owner", "Land", "", WORLD, "world", new SimpleClaimStateAuthority.Position(Double.NaN, 0, 0, 0, 0), Set.of(), Set.of(), Map.of(), false, 0, Set.of()), policy(false)));
        assertThrows(IllegalArgumentException.class, () -> snapshot(replace(claim(PLAYER, 7, "Land", false, 0, 1), "owner", "Land", "", WORLD, "world", new SimpleClaimStateAuthority.Position(0, 0, 0, 0, 0), Set.of(new SimpleClaimStateAuthority.Chunk(OTHER, "elsewhere", 0, 0)), Set.of(), Map.of(), false, 0, Set.of()), policy(false)));
        assertThrows(IllegalArgumentException.class, () -> snapshot(claim(PLAYER, 7, "Land", false, 0, 1),
                new SimpleClaimStateAuthority.Policy(Map.of("build", "allow"), List.of(), Map.of(), Map.of("x", Double.POSITIVE_INFINITY))));
        assertThrows(IllegalArgumentException.class, () -> snapshot(replace(claim(PLAYER, 7, "Land", false, 0, 1), "x".repeat(10_000), "Land", "", WORLD, "world", new SimpleClaimStateAuthority.Position(0, 0, 0, 0, 0), Set.of(), Set.of(), Map.of(), false, 0, Set.of()), policy(false)));
    }

    @Test
    void countsPermissionGroupMapsAndTheirEntriesAgainstOneAggregateBudget() {
        Map<String, Map<String, Boolean>> permissions = new LinkedHashMap<>();
        for (int i = 0; i < 50_000; i++) permissions.put("g" + i, Map.of("p", true));
        var base = claim(PLAYER, 7, "Land", false, 0, 1);
        var oversized = replace(base, base.ownerName(), base.name(), base.description(), WORLD, "world", base.location(),
                base.chunks(), base.members(), permissions, false, 0, base.bans());
        assertThrows(IllegalArgumentException.class, () -> snapshot(oversized, policy(false)));
    }

    @Test
    void rejectsSnapshotsForTheEarlierExperimentalModelVersion() throws Exception {
        byte[] current = snapshot(claim(PLAYER, 7, "Land", false, 0, 1), policy(false));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x53435331); out.writeByte(1); writeString(out, "1.13.0.9");
            out.write(current, 5 + Integer.BYTES + "1.13.1".length(),
                    current.length - 5 - Integer.BYTES - "1.13.1".length());
        }
        assertInvalid(bytes.toByteArray());
        assertDoesNotThrow(() -> SimpleClaimStateAuthority.decode(current));
    }

    @Test
    void checksProviderHealthAroundCaptureAndReturnsDefensiveData() {
        AtomicInteger calls = new AtomicInteger();
        var state = state(claim(PLAYER, 7, "Land", false, 0, 1), policy(false));
        var authority = new SimpleClaimStateAuthority(new SimpleClaimStateAuthority.Access() {
            @Override public boolean available() { return calls.incrementAndGet() == 1; }
            @Override public SimpleClaimStateAuthority.State readLoaded(UUID playerId) { return state; }
        });
        assertThrows(IllegalStateException.class, () -> authority.read(PLAYER));
        assertEquals(2, calls.get());
        assertThrows(UnsupportedOperationException.class, () -> state.claims().clear());
        assertThrows(UnsupportedOperationException.class, () -> state.policy().defaults().clear());
        var healthy = authority(true, state);
        byte[] payload = healthy.read(PLAYER); payload[0] ^= 1;
        assertDoesNotThrow(() -> healthy.validate(PLAYER, snapshot(state.claims().getFirst(), state.policy())));
    }

    private static void assertDrift(byte[] original, SimpleClaimStateAuthority.Claim changed) { assertFalse(java.util.Arrays.equals(original, snapshot(changed, policy(false)))); }
    private static void assertDrift(byte[] original, SimpleClaimStateAuthority.Claim claim, SimpleClaimStateAuthority.Policy policy) { assertFalse(java.util.Arrays.equals(original, snapshot(claim, policy))); }
    private static byte[] snapshot(SimpleClaimStateAuthority.Claim claim, SimpleClaimStateAuthority.Policy policy) { return SimpleClaimStateAuthority.encode(new SimpleClaimStateAuthority.State(PLAYER, List.of(claim), policy)); }
    private static SimpleClaimStateAuthority.State state(SimpleClaimStateAuthority.Claim claim, SimpleClaimStateAuthority.Policy policy) { return new SimpleClaimStateAuthority.State(PLAYER, List.of(claim), policy); }
    private static SimpleClaimStateAuthority authority(SimpleClaimStateAuthority.State state) { return authority(true, state); }
    private static SimpleClaimStateAuthority authority(boolean available, SimpleClaimStateAuthority.State state) {
        return new SimpleClaimStateAuthority(new SimpleClaimStateAuthority.Access() {
            @Override public boolean available() { return available; }
            @Override public SimpleClaimStateAuthority.State readLoaded(UUID playerId) { return state; }
        });
    }
    private static SimpleClaimStateAuthority.Claim claim(UUID owner, int id, String name, boolean sale, long price, int ignored) {
        return new SimpleClaimStateAuthority.Claim(owner, id, "owner", name, "description", WORLD, "world",
                new SimpleClaimStateAuthority.Position(1, 2, 3, 4, 5), Set.of(chunk(1), chunk(2)), Set.of(OTHER),
                Map.of("default", Map.of("build", false, "use", true)), sale, price, Set.of());
    }
    private static SimpleClaimStateAuthority.Claim replace(SimpleClaimStateAuthority.Claim old, String ownerName, String name, String desc,
            UUID worldId, String worldName, SimpleClaimStateAuthority.Position position, Set<SimpleClaimStateAuthority.Chunk> chunks,
            Set<UUID> members, Map<String, Map<String, Boolean>> permissions, boolean sale, long price, Set<UUID> bans) {
        return new SimpleClaimStateAuthority.Claim(old.ownerId(), old.claimId(), ownerName, name, desc, worldId, worldName,
                position, chunks, members, permissions, sale, price, bans);
    }
    private static SimpleClaimStateAuthority.Chunk chunk(int x) { return new SimpleClaimStateAuthority.Chunk(WORLD, "world", x, 0); }
    private static SimpleClaimStateAuthority.Policy policy(boolean reverse) {
        return policy(reverse, false);
    }
    private static SimpleClaimStateAuthority.Policy policy(boolean reverse, boolean reverseGroups) {
        Map<String, String> defaults = reverse ? Map.of("build", "allow", "claim", "deny") : Map.of("claim", "deny", "build", "allow");
        List<SimpleClaimStateAuthority.GroupRule> groups = reverseGroups
                ? List.of(new SimpleClaimStateAuthority.GroupRule("member", "allow"), new SimpleClaimStateAuthority.GroupRule("owner", "allow"))
                : List.of(new SimpleClaimStateAuthority.GroupRule("owner", "allow"), new SimpleClaimStateAuthority.GroupRule("member", "allow"));
        return new SimpleClaimStateAuthority.Policy(defaults, groups, Map.of("default", Map.of("price", 1.0)), Map.of("visit", 1.0));
    }
    private static SimpleClaimStateAuthority.Claim unrelated() {
        return new SimpleClaimStateAuthority.Claim(OTHER, 8, "other", "Other", "", WORLD, "world",
                new SimpleClaimStateAuthority.Position(0, 0, 0, 0, 0), Set.of(), Set.of(), Map.of(), false, 0, Set.of());
    }
    private static void assertInvalid(byte[] payload) { assertThrows(IllegalArgumentException.class, () -> SimpleClaimStateAuthority.decode(payload)); }
    private static byte[] countPayload(int count) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(0x53435331); out.writeByte(1); writeString(out, "1.13.1"); out.writeLong(PLAYER.getMostSignificantBits()); out.writeLong(PLAYER.getLeastSignificantBits()); out.writeInt(count);
        }
        return bytes.toByteArray();
    }
    private static void writeString(DataOutputStream out, String value) throws Exception { byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes); }
}
