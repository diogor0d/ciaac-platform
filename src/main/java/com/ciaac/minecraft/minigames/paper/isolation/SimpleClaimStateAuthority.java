package com.ciaac.minecraft.minigames.paper.isolation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** Read-only canonical snapshot of the player's relevant SimpleClaims state. */
public final class SimpleClaimStateAuthority implements ExternalStateAuthority {
    private static final String SIMPLECLAIMS_VERSION = "1.13.1";
    private static final int MAGIC = 0x53435331; // SCS1
    private static final int SCHEMA = 1;
    static final int MAX_PAYLOAD_BYTES = 4 * 1024 * 1024;
    static final int MAX_CLAIMS = 4096;
    static final int MAX_NODES = 100_000;
    static final int MAX_STRING_BYTES = 4096;
    private static final Comparator<Claim> CLAIM_ORDER = Comparator.comparing(Claim::ownerId)
            .thenComparingInt(Claim::claimId);
    private static final Comparator<Chunk> CHUNK_ORDER = Comparator.comparing(Chunk::worldId)
            .thenComparingInt(Chunk::x).thenComparingInt(Chunk::z)
            .thenComparing(Chunk::worldName);

    interface Access {
        boolean available();
        State readLoaded(UUID playerId);
    }

    record State(UUID playerId, List<Claim> claims, Policy policy) {
        State {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(claims, "claims");
            if (claims.size() > MAX_CLAIMS) throw new IllegalArgumentException("Too many claims");
            claims = List.copyOf(claims);
            Objects.requireNonNull(policy, "policy");
        }
    }

    record Claim(UUID ownerId, int claimId, String ownerName, String name, String description,
            UUID worldId, String worldName, Position location, Set<Chunk> chunks, Set<UUID> members,
            Map<String, Map<String, Boolean>> permissions, boolean sale, long price, Set<UUID> bans) {
        Claim {
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(ownerName, "ownerName");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(worldName, "worldName");
            Objects.requireNonNull(location, "location");
            checkSize(chunks, "chunks"); checkSize(members, "members"); checkSize(bans, "bans");
            checkSize(permissions, "permissions");
            chunks = immutableChunks(Objects.requireNonNull(chunks, "chunks"));
            members = immutableSet(Objects.requireNonNull(members, "members"));
            permissions = immutableNestedBooleanMap(Objects.requireNonNull(permissions, "permissions"));
            bans = immutableSet(Objects.requireNonNull(bans, "bans"));
        }
    }

    record Position(double x, double y, double z, float yaw, float pitch) {}
    record Chunk(UUID worldId, String worldName, int x, int z) {}

    record Policy(Map<String, String> defaults, List<GroupRule> orderedGroups,
            Map<String, Map<String, Double>> groupSettings, Map<String, Double> playerSettings) {
        Policy {
            checkSize(defaults, "policy defaults"); checkSize(orderedGroups, "ordered groups");
            checkSize(groupSettings, "group settings"); checkSize(playerSettings, "player settings");
            defaults = immutableSortedMap(Objects.requireNonNull(defaults, "defaults"));
            orderedGroups = List.copyOf(Objects.requireNonNull(orderedGroups, "orderedGroups"));
            groupSettings = immutableNestedDoubleMap(Objects.requireNonNull(groupSettings, "groupSettings"));
            playerSettings = immutableSortedMap(Objects.requireNonNull(playerSettings, "playerSettings"));
        }
    }

    record GroupRule(String key, String value) {}

    private final Access access;

    SimpleClaimStateAuthority(Access access) {
        this.access = Objects.requireNonNull(access, "access");
    }

    @Override public boolean available() {
        try { return access.available(); }
        catch (RuntimeException unavailable) { return false; }
    }

    @Override public byte[] read(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        requireAvailable();
        State state = Objects.requireNonNull(access.readLoaded(playerId), "loaded claims state");
        if (!playerId.equals(state.playerId())) throw new IllegalStateException("Loaded claims state UUID differs");
        byte[] payload = encode(state);
        requireAvailable();
        return payload;
    }

    @Override public void validate(UUID playerId, byte[] payload) {
        Objects.requireNonNull(playerId, "playerId");
        State state = decode(payload);
        if (!playerId.equals(state.playerId())) throw new IllegalArgumentException("Claims snapshot belongs to a different UUID");
    }

    private void requireAvailable() {
        if (!available()) throw new IllegalStateException("SimpleClaims authority is unavailable");
    }

    static byte[] encode(State state) {
        Objects.requireNonNull(state, "state");
        if (state.claims().size() > MAX_CLAIMS) throw new IllegalArgumentException("Too many claims");
        require(!state.policy().defaults().isEmpty(), "Policy defaults must not be empty");
        Budget budget = new Budget();
        budget.add(state.claims().size());
        List<Claim> claims = new ArrayList<>(state.claims());
        claims.sort(CLAIM_ORDER);
        Set<ClaimIdentity> identities = new java.util.HashSet<>();
        try {
            ByteArrayOutputStream bytes = new BoundedByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC); out.writeByte(SCHEMA);
                writeString(out, SIMPLECLAIMS_VERSION); writeUuid(out, state.playerId());
                out.writeInt(claims.size());
                for (Claim claim : claims) {
                    validateClaim(state.playerId(), claim, identities, budget);
                    writeClaim(out, claim, budget);
                }
                writePolicy(out, state.policy(), budget);
            }
            if (bytes.size() > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("Claims payload is too large");
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalArgumentException("Could not encode claims", impossible); }
    }

    static State decode(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES)
            throw new IllegalArgumentException("Invalid claims payload size");
        byte[] stablePayload = payload.clone();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(stablePayload))) {
            if (in.readInt() != MAGIC || in.readUnsignedByte() != SCHEMA
                    || !SIMPLECLAIMS_VERSION.equals(readString(in))) throw new IllegalArgumentException("Unsupported claims payload header");
            UUID playerId = readUuid(in);
            int count = readCount(in, MAX_CLAIMS, 1);
            Budget budget = new Budget(); budget.add(count);
            List<Claim> claims = new ArrayList<>(count);
            Set<ClaimIdentity> identities = new java.util.HashSet<>();
            for (int i = 0; i < count; i++) claims.add(readClaim(in, playerId, identities, budget));
            Policy policy = readPolicy(in, budget);
            if (policy.defaults().isEmpty()) throw new IllegalArgumentException("Policy defaults must not be empty");
            BinaryStateIo.requireExhausted(in);
            State decoded = new State(playerId, claims, policy);
            if (!java.util.Arrays.equals(stablePayload, encode(decoded))) throw new IllegalArgumentException("Claims payload is not canonical");
            return decoded;
        } catch (IOException | ArithmeticException malformed) {
            throw new IllegalArgumentException("Malformed claims payload", malformed);
        }
    }

    private static void validateClaim(UUID playerId, Claim c, Set<ClaimIdentity> identities, Budget budget) {
        Objects.requireNonNull(c, "claim");
        requireIdentifier(c.ownerName(), "owner name"); requireIdentifier(c.name(), "claim name");
        requireText(c.description(), "description"); requireIdentifier(c.worldName(), "world name");
        finite(c.location());
        ClaimIdentity id = new ClaimIdentity(c.ownerId(), c.claimId());
        if (!identities.add(id)) throw new IllegalArgumentException("Duplicate claim identity");
        if (!playerId.equals(c.ownerId()) && !c.members().contains(playerId) && !c.bans().contains(playerId))
            throw new IllegalArgumentException("Unrelated claim in player state");
        for (Chunk chunk : c.chunks()) {
            Objects.requireNonNull(chunk, "chunk");
            if (!c.worldId().equals(chunk.worldId()) || !c.worldName().equals(chunk.worldName()))
                throw new IllegalArgumentException("Claim chunk world identity differs");
            requireIdentifier(chunk.worldName(), "chunk world name");
            budget.add(1);
        }
        budget.add(c.members().size()); budget.add(c.bans().size());
        budget.add(c.permissions().size());
        for (var entry : c.permissions().entrySet()) {
            requireIdentifier(entry.getKey(), "permission group");
            Map<String, Boolean> perms = Objects.requireNonNull(entry.getValue(), "permission map");
            budget.add(perms.size());
            for (var permission : perms.entrySet()) {
                requireIdentifier(permission.getKey(), "permission key");
                Objects.requireNonNull(permission.getValue(), "permission value");
            }
        }
        if (c.price() < 0) throw new IllegalArgumentException("Negative claim price");
    }

    private static void writeClaim(DataOutputStream out, Claim c, Budget budget) throws IOException {
        writeUuid(out, c.ownerId()); out.writeInt(c.claimId()); writeString(out, c.ownerName());
        writeString(out, c.name()); writeString(out, c.description()); writeUuid(out, c.worldId()); writeString(out, c.worldName());
        Position p = c.location(); out.writeDouble(p.x()); out.writeDouble(p.y()); out.writeDouble(p.z()); out.writeFloat(p.yaw()); out.writeFloat(p.pitch());
        List<Chunk> chunks = new ArrayList<>(c.chunks()); chunks.sort(CHUNK_ORDER); out.writeInt(chunks.size());
        for (Chunk ch : chunks) { writeUuid(out, ch.worldId()); writeString(out, ch.worldName()); out.writeInt(ch.x()); out.writeInt(ch.z()); }
        writeUuidSet(out, c.members());
        out.writeInt(c.permissions().size());
        for (var group : new TreeMap<>(c.permissions()).entrySet()) {
            writeString(out, group.getKey()); out.writeInt(group.getValue().size());
            for (var perm : new TreeMap<>(group.getValue()).entrySet()) { writeString(out, perm.getKey()); out.writeBoolean(perm.getValue()); }
        }
        out.writeBoolean(c.sale()); out.writeLong(c.price()); writeUuidSet(out, c.bans());
    }

    private static Claim readClaim(DataInputStream in, UUID playerId, Set<ClaimIdentity> identities, Budget budget) throws IOException {
        UUID owner = readUuid(in); int claimId = in.readInt(); String ownerName = readString(in), name = readString(in), desc = readString(in);
        UUID world = readUuid(in); String worldName = readString(in);
        Position pos = new Position(in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat()); finite(pos);
        int chunkCount = readCount(in, MAX_NODES, 20); budget.add(chunkCount);
        Set<Chunk> chunks = new LinkedHashSet<>();
        for (int i = 0; i < chunkCount; i++) {
            Chunk chunk = new Chunk(readUuid(in), readString(in), in.readInt(), in.readInt());
            if (!world.equals(chunk.worldId()) || !chunks.add(chunk)) throw new IllegalArgumentException("Invalid or duplicate claim chunk");
        }
        Set<UUID> members = readUuidSet(in, budget), bans;
        int groupCount = readCount(in, MAX_NODES, 8); budget.add(groupCount);
        Map<String, Map<String, Boolean>> permissions = new LinkedHashMap<>();
        for (int i = 0; i < groupCount; i++) {
            String group = readString(in); int permissionCount = readCount(in, MAX_NODES, 5); budget.add(permissionCount);
            Map<String, Boolean> values = new LinkedHashMap<>();
            for (int j = 0; j < permissionCount; j++) {
                String key = readString(in); int flag = in.readUnsignedByte();
                if (flag > 1 || values.putIfAbsent(key, flag == 1) != null) throw new IllegalArgumentException("Invalid duplicate permission");
            }
            if (permissions.putIfAbsent(group, values) != null) throw new IllegalArgumentException("Duplicate permission group");
        }
        int saleFlag = in.readUnsignedByte(); if (saleFlag > 1) throw new IllegalArgumentException("Unknown sale flag");
        long price = in.readLong(); bans = readUuidSet(in, budget);
        Claim claim = new Claim(owner, claimId, ownerName, name, desc, world, worldName, pos,
                chunks, members, permissions, saleFlag == 1, price, bans);
        validateClaim(playerId, claim, identities, new Budget());
        return claim;
    }

    private static void writePolicy(DataOutputStream out, Policy p, Budget budget) throws IOException {
        budget.add(p.defaults().size()); out.writeInt(p.defaults().size());
        for (var entry : new TreeMap<>(p.defaults()).entrySet()) { requireIdentifier(entry.getKey(), "policy default key"); writeString(out, entry.getKey()); writeString(out, entry.getValue()); }
        budget.add(p.orderedGroups().size()); out.writeInt(p.orderedGroups().size());
        for (GroupRule group : p.orderedGroups()) { Objects.requireNonNull(group, "group rule"); requireIdentifier(group.key(), "group rule key"); requireIdentifier(group.value(), "group rule value"); writeString(out, group.key()); writeString(out, group.value()); }
        budget.add(p.groupSettings().size()); out.writeInt(p.groupSettings().size());
        for (var group : new TreeMap<>(p.groupSettings()).entrySet()) {
            requireIdentifier(group.getKey(), "settings group"); writeString(out, group.getKey()); budget.add(group.getValue().size()); out.writeInt(group.getValue().size());
            for (var setting : new TreeMap<>(group.getValue()).entrySet()) { requireIdentifier(setting.getKey(), "group setting key"); writeString(out, setting.getKey()); writeFinite(out, setting.getValue()); }
        }
        budget.add(p.playerSettings().size()); out.writeInt(p.playerSettings().size());
        for (var setting : new TreeMap<>(p.playerSettings()).entrySet()) { requireIdentifier(setting.getKey(), "player setting key"); writeString(out, setting.getKey()); writeFinite(out, setting.getValue()); }
    }

    private static Policy readPolicy(DataInputStream in, Budget budget) throws IOException {
        int defaultsCount = readCount(in, MAX_NODES, 8); budget.add(defaultsCount);
        Map<String, String> defaults = new LinkedHashMap<>();
        for (int i = 0; i < defaultsCount; i++) { String key = readString(in), value = readString(in); requireIdentifier(key, "policy default key"); if (defaults.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate policy default"); }
        int orderedCount = readCount(in, MAX_NODES, 8); budget.add(orderedCount);
        List<GroupRule> ordered = new ArrayList<>(orderedCount);
        for (int i = 0; i < orderedCount; i++) { String key = readString(in), value = readString(in); requireIdentifier(key, "group rule key"); requireIdentifier(value, "group rule value"); ordered.add(new GroupRule(key, value)); }
        int groupCount = readCount(in, MAX_NODES, 8); budget.add(groupCount);
        Map<String, Map<String, Double>> groups = new LinkedHashMap<>();
        for (int i = 0; i < groupCount; i++) {
            String group = readString(in); requireIdentifier(group, "settings group"); int settingsCount = readCount(in, MAX_NODES, 8); budget.add(settingsCount);
            Map<String, Double> settings = new LinkedHashMap<>();
            for (int j = 0; j < settingsCount; j++) { String key = readString(in); requireIdentifier(key, "group setting key"); if (settings.putIfAbsent(key, readFinite(in)) != null) throw new IllegalArgumentException("Duplicate group setting"); }
            if (groups.putIfAbsent(group, settings) != null) throw new IllegalArgumentException("Duplicate policy group");
        }
        int playerCount = readCount(in, MAX_NODES, 8); budget.add(playerCount);
        Map<String, Double> players = new LinkedHashMap<>();
        for (int i = 0; i < playerCount; i++) { String key = readString(in); requireIdentifier(key, "player setting key"); if (players.putIfAbsent(key, readFinite(in)) != null) throw new IllegalArgumentException("Duplicate player setting"); }
        return new Policy(defaults, ordered, groups, players);
    }

    private static Set<UUID> readUuidSet(DataInputStream in, Budget budget) throws IOException {
        int count = readCount(in, MAX_NODES, 16); budget.add(count); Set<UUID> set = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) if (!set.add(readUuid(in))) throw new IllegalArgumentException("Duplicate UUID");
        return set;
    }

    private static void writeUuidSet(DataOutputStream out, Set<UUID> values) throws IOException {
        List<UUID> sorted = new ArrayList<>(values); sorted.sort(UUID::compareTo); out.writeInt(sorted.size());
        for (UUID value : sorted) writeUuid(out, value);
    }

    private static int readCount(DataInputStream in, int max, int minimumBytesPerEntry) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > max || (long)count * minimumBytesPerEntry > in.available()) throw new IllegalArgumentException("Invalid claims collection count");
        return count;
    }

    private static void writeFinite(DataOutputStream out, Double value) throws IOException {
        Objects.requireNonNull(value, "policy setting"); if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite policy value"); out.writeDouble(value);
    }
    private static double readFinite(DataInputStream in) throws IOException { double d = in.readDouble(); if (!Double.isFinite(d)) throw new IllegalArgumentException("Non-finite policy value"); return d; }
    private static void finite(Position p) { if (!Double.isFinite(p.x()) || !Double.isFinite(p.y()) || !Double.isFinite(p.z()) || !Float.isFinite(p.yaw()) || !Float.isFinite(p.pitch())) throw new IllegalArgumentException("Non-finite claim position"); }
    private static void writeUuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID readUuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        Objects.requireNonNull(value, "string");
        if (value.length() > MAX_STRING_BYTES) throw new IllegalArgumentException("Claims string is too long");
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            if (encoded.remaining() > MAX_STRING_BYTES) throw new IllegalArgumentException("Claims string is too long");
            out.writeInt(encoded.remaining()); out.write(encoded.array(), encoded.position(), encoded.remaining());
        } catch (CharacterCodingException e) { throw new IllegalArgumentException("Invalid UTF-16 string", e); }
    }
    private static String readString(DataInputStream in) throws IOException {
        int size = in.readInt(); if (size < 0 || size > MAX_STRING_BYTES || size > in.available()) throw new IllegalArgumentException("Invalid claims string size");
        byte[] raw = in.readNBytes(size);
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString(); }
        catch (CharacterCodingException e) { throw new IllegalArgumentException("Malformed UTF-8", e); }
    }

    private static void requireIdentifier(String value, String what) {
        requireText(value, what); if (value.isBlank()) throw new IllegalArgumentException("Blank " + what);
    }
    private static void requireText(String value, String what) { Objects.requireNonNull(value, what); if (utf8Length(value) > MAX_STRING_BYTES) throw new IllegalArgumentException(what + " is too long"); }
    private static int utf8Length(String value) {
        if (value.length() > MAX_STRING_BYTES) throw new IllegalArgumentException("Claims string is too long");
        try { return StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining(); }
        catch (CharacterCodingException e) { throw new IllegalArgumentException("Invalid UTF-16 string", e); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }

    private static <T extends Comparable<? super T>> Set<T> immutableSet(Set<T> source) {
        checkSize(source, "set");
        TreeSet<T> sorted = new TreeSet<>(); for (T item : source) sorted.add(Objects.requireNonNull(item, "set entry"));
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }
    private static Set<Chunk> immutableChunks(Set<Chunk> source) {
        checkSize(source, "chunks");
        TreeSet<Chunk> sorted = new TreeSet<>(CHUNK_ORDER);
        java.util.HashSet<ChunkIdentity> identities = new java.util.HashSet<>();
        for (Chunk item : source) {
            Objects.requireNonNull(item, "chunk");
            if (!identities.add(new ChunkIdentity(item.worldId(), item.x(), item.z())))
                throw new IllegalArgumentException("Duplicate or conflicting claim chunk");
            sorted.add(item);
        }
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(sorted));
    }
    private static <V> Map<String,V> immutableSortedMap(Map<String,V> source) {
        checkSize(source, "map");
        TreeMap<String,V> sorted = new TreeMap<>(); source.forEach((k,v) -> sorted.put(Objects.requireNonNull(k, "map key"), Objects.requireNonNull(v, "map value")));
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }
    private static Map<String,Map<String,Boolean>> immutableNestedBooleanMap(Map<String,Map<String,Boolean>> source) {
        checkSize(source, "permission groups");
        TreeMap<String,Map<String,Boolean>> sorted = new TreeMap<>(); source.forEach((key, values) -> sorted.put(Objects.requireNonNull(key, "permission group"), immutableSortedMap(Objects.requireNonNull(values, "permission map"))));
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }
    private static Map<String,Map<String,Double>> immutableNestedDoubleMap(Map<String,Map<String,Double>> source) {
        checkSize(source, "settings groups");
        TreeMap<String,Map<String,Double>> sorted = new TreeMap<>(); source.forEach((key, values) -> sorted.put(Objects.requireNonNull(key, "settings group"), immutableSortedMap(Objects.requireNonNull(values, "settings map"))));
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }
    private record ClaimIdentity(UUID ownerId, int claimId) {}
    private record ChunkIdentity(UUID worldId, int x, int z) {}
    private static final class Budget {
        private int nodes;
        void add(int count) { if (count < 0 || (long)nodes + count > MAX_NODES) throw new IllegalArgumentException("Claims node limit exceeded"); nodes += count; }
    }
    private static void checkSize(java.util.Collection<?> values, String what) {
        Objects.requireNonNull(values, what); if (values.size() > MAX_NODES) throw new IllegalArgumentException(what + " exceed the claims node limit");
    }
    private static void checkSize(Map<?, ?> values, String what) {
        Objects.requireNonNull(values, what); if (values.size() > MAX_NODES) throw new IllegalArgumentException(what + " exceed the claims node limit");
    }
    private static final class BoundedByteArrayOutputStream extends ByteArrayOutputStream {
        @Override public synchronized void write(int value) {
            if ((long)size() + 1 > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("Claims payload is too large");
            super.write(value);
        }
        @Override public synchronized void write(byte[] values, int offset, int length) {
            if ((long)size() + length > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("Claims payload is too large");
            super.write(values, offset, length);
        }
    }
}
