package com.ciaac.minecraft.minigames.paper.isolation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.Context;
import net.luckperms.api.model.data.DataType;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;

/**
 * Read-only LuckPerms snapshot authority. It only looks up objects which LuckPerms already has in
 * memory; it never loads, creates, edits, or saves a user, group, or node.
 */
public final class LuckPermsStateAuthority implements ExternalStateAuthority {
    private static final int MAGIC = 0x4c505331; // LPS1
    private static final int VERSION = 1;
    private static final int MAX_PAYLOAD_BYTES = 8 * 1024 * 1024;
    private static final int MAX_STRING_BYTES = 64 * 1024;
    private static final int MAX_GROUPS = 4096;
    private static final int MAX_NODES = 100_000;
    private static final int MAX_CONTEXTS_PER_NODE = 1024;

    private static final Comparator<ContextPair> CONTEXT_ORDER = Comparator
            .comparing(ContextPair::key)
            .thenComparing(ContextPair::value);
    private static final Comparator<NodeRecord> NODE_ORDER = (left, right) -> {
        int compared = left.key().compareTo(right.key());
        if (compared != 0) return compared;
        compared = Boolean.compare(left.value(), right.value());
        if (compared != 0) return compared;
        compared = compareContexts(left.contexts(), right.contexts());
        if (compared != 0) return compared;
        compared = compareNullableInstants(left.expiry(), right.expiry());
        if (compared != 0) return compared;
        return compareNullableStrings(left.inheritanceTarget(), right.inheritanceTarget());
    };

    private final LuckPerms luckPerms;
    private final BooleanSupplier health;

    public LuckPermsStateAuthority(LuckPerms luckPerms, BooleanSupplier health) {
        this.luckPerms = Objects.requireNonNull(luckPerms, "luckPerms");
        this.health = Objects.requireNonNull(health, "health");
    }

    @Override
    public boolean available() {
        try {
            return health.getAsBoolean();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    @Override
    public byte[] read(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        requireAvailable();

        User user = luckPerms.getUserManager().getUser(playerId);
        if (user == null) {
            throw new IllegalStateException("LuckPerms user is not already loaded: " + playerId);
        }
        if (!playerId.equals(user.getUniqueId())) {
            throw new IllegalStateException("LuckPerms returned a user with a different UUID");
        }

        String primaryGroupName = requireName(user.getPrimaryGroup(), "primary group");
        Group primaryGroup = requireLoadedGroup(primaryGroupName);
        String primaryGroupIdentity = requireName(primaryGroup.getName(), "loaded group name");

        TreeMap<String, GroupRecord> groups = new TreeMap<>();
        TreeMap<String, String> resolvedGroupNames = new TreeMap<>();
        ArrayDeque<Group> pendingGroups = new ArrayDeque<>();
        ReadBudget budget = new ReadBudget();
        budget.addString(primaryGroupIdentity);
        List<NodeRecord> userPersistent = snapshotNodes(user, DataType.NORMAL, resolvedGroupNames, pendingGroups, budget);
        List<NodeRecord> userTransient = snapshotNodes(user, DataType.TRANSIENT, resolvedGroupNames, pendingGroups, budget);
        budget.reserve(Integer.BYTES); // Group count.
        resolvedGroupNames.put(primaryGroupName, primaryGroupIdentity);
        pendingGroups.add(primaryGroup);

        while (!pendingGroups.isEmpty()) {
            Group group = pendingGroups.removeFirst();
            String groupName = requireName(group.getName(), "loaded group name");
            if (groups.containsKey(groupName)) continue; // Also makes cyclic group graphs safe.

            budget.addString(groupName);
            List<NodeRecord> persistent = snapshotNodes(group, DataType.NORMAL, resolvedGroupNames, pendingGroups, budget);
            List<NodeRecord> transientNodes = snapshotNodes(group, DataType.TRANSIENT, resolvedGroupNames, pendingGroups, budget);
            groups.put(groupName, new GroupRecord(groupName, persistent, transientNodes));
            if (groups.size() > MAX_GROUPS) {
                throw new IllegalStateException("LuckPerms inherited group graph exceeds the snapshot limit");
            }
        }

        requireAvailable();
        return encode(new Snapshot(playerId, primaryGroupIdentity, userPersistent, userTransient, groups));
    }

    @Override
    public void validate(UUID playerId, byte[] payload) {
        Objects.requireNonNull(playerId, "playerId");
        Snapshot snapshot = decode(payload);
        if (!playerId.equals(snapshot.playerId())) {
            throw new IllegalArgumentException("LuckPerms snapshot belongs to a different UUID");
        }
    }

    private void requireAvailable() {
        if (!available()) {
            throw new IllegalStateException("LuckPerms authority is unavailable");
        }
    }

    private Group requireLoadedGroup(String name) {
        Group group = luckPerms.getGroupManager().getGroup(name);
        if (group == null) {
            throw new IllegalStateException("LuckPerms group is not already loaded: " + name);
        }
        String returnedName = requireName(group.getName(), "loaded group name");
        if (!name.equalsIgnoreCase(returnedName)) {
            throw new IllegalStateException("LuckPerms returned a different group for " + name + ": " + returnedName);
        }
        return group;
    }

    private List<NodeRecord> snapshotNodes(Object holder, DataType dataType,
            TreeMap<String, String> resolvedGroupNames, ArrayDeque<Group> pendingGroups, ReadBudget budget) {
        Collection<Node> source;
        if (holder instanceof User user) {
            source = user.getData(dataType).toCollection();
        } else if (holder instanceof Group group) {
            source = group.getData(dataType).toCollection();
        } else {
            throw new IllegalArgumentException("Unsupported LuckPerms permission holder");
        }
        if (source == null) {
            throw new IllegalStateException("LuckPerms returned a null node collection");
        }

        budget.reserve(Integer.BYTES); // This node list's count.
        ArrayList<NodeRecord> records = new ArrayList<>();
        for (Node node : source) {
            budget.addOne();
            if (node == null) throw new IllegalStateException("LuckPerms returned a null node");
            // A node can remain in LuckPerms' maps until periodic cleanup; never snapshot it after expiry.
            if (node.hasExpired()) continue;

            String key = requireName(node.getKey(), "node key");
            budget.addString(key);
            boolean hasExpiry = node.hasExpiry();
            budget.reserve(2L + (hasExpiry ? 3L * Integer.BYTES : 0L) + 1L + Integer.BYTES);
            String inheritanceTarget = null;
            if (node instanceof InheritanceNode inheritance) {
                String referencedName = requireName(inheritance.getGroupName(), "inherited group name");
                inheritanceTarget = resolvedGroupNames.get(referencedName);
                if (inheritanceTarget == null) {
                    Group inherited = requireLoadedGroup(referencedName);
                    inheritanceTarget = requireName(inherited.getName(), "loaded group name");
                    resolvedGroupNames.put(referencedName, inheritanceTarget);
                    pendingGroups.addLast(inherited);
                }
                budget.addString(inheritanceTarget);
            }

            ArrayList<ContextPair> contexts = new ArrayList<>();
            var nodeContexts = node.getContexts();
            if (nodeContexts == null || nodeContexts.size() > MAX_CONTEXTS_PER_NODE) {
                throw new IllegalStateException("LuckPerms node contexts exceed the snapshot limit");
            }
            for (Context context : nodeContexts) {
                if (contexts.size() >= MAX_CONTEXTS_PER_NODE) {
                    throw new IllegalStateException("LuckPerms node contexts exceed the snapshot limit");
                }
                if (context == null) throw new IllegalStateException("LuckPerms returned a null node context");
                String contextKey = requireString(context.getKey(), "context key");
                String contextValue = requireString(context.getValue(), "context value");
                budget.addString(contextKey);
                budget.addString(contextValue);
                contexts.add(new ContextPair(contextKey, contextValue));
            }
            contexts.sort(CONTEXT_ORDER);
            rejectDuplicateContexts(contexts);

            Instant expiry = hasExpiry ? node.getExpiry() : null;
            if (hasExpiry && expiry == null) {
                throw new IllegalStateException("LuckPerms node reported an expiry without an instant");
            }
            records.add(new NodeRecord(key, node.getValue(), List.copyOf(contexts), expiry, inheritanceTarget));
        }

        records.sort(NODE_ORDER);
        rejectDuplicateOrConflictingNodes(records, "LuckPerms node collection");
        return List.copyOf(records);
    }

    private static byte[] encode(Snapshot snapshot) {
        LimitedByteArrayOutputStream bytes = new LimitedByteArrayOutputStream(MAX_PAYLOAD_BYTES);
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeByte(VERSION);
            output.writeLong(snapshot.playerId().getMostSignificantBits());
            output.writeLong(snapshot.playerId().getLeastSignificantBits());
            writeString(output, snapshot.primaryGroup());
            writeNodes(output, snapshot.userPersistent());
            writeNodes(output, snapshot.userTransient());
            output.writeInt(snapshot.groups().size());
            for (GroupRecord group : snapshot.groups().values()) {
                writeString(output, group.name());
                writeNodes(output, group.persistent());
                writeNodes(output, group.transientNodes());
            }
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode LuckPerms snapshot", exception);
        }
    }

    private static Snapshot decode(byte[] payload) {
        if (payload == null) throw new IllegalArgumentException("LuckPerms snapshot is null");
        if (payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("LuckPerms snapshot size is invalid");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC) throw new IllegalArgumentException("Invalid LuckPerms snapshot magic");
            if (input.readUnsignedByte() != VERSION) throw new IllegalArgumentException("Unsupported LuckPerms snapshot version");
            UUID playerId = new UUID(input.readLong(), input.readLong());
            String primaryGroup = readRequiredString(input, "primary group");
            NodeBudget budget = new NodeBudget();
            List<NodeRecord> userPersistent = readNodes(input, budget);
            List<NodeRecord> userTransient = readNodes(input, budget);

            int groupCount = readCount(input, MAX_GROUPS, "group count");
            TreeMap<String, GroupRecord> groups = new TreeMap<>();
            String previousName = null;
            for (int index = 0; index < groupCount; index++) {
                String name = readRequiredString(input, "group name");
                if (previousName != null && previousName.compareTo(name) >= 0) {
                    throw new IllegalArgumentException("LuckPerms groups are duplicated or not canonical");
                }
                previousName = name;
                List<NodeRecord> persistent = readNodes(input, budget);
                List<NodeRecord> transientNodes = readNodes(input, budget);
                groups.put(name, new GroupRecord(name, persistent, transientNodes));
            }
            if (input.read() != -1) throw new IllegalArgumentException("LuckPerms snapshot has trailing data");

            Snapshot snapshot = new Snapshot(playerId, primaryGroup, userPersistent, userTransient, groups);
            validateGraph(snapshot);
            if (!java.util.Arrays.equals(payload, encode(snapshot))) {
                throw new IllegalArgumentException("LuckPerms snapshot is not canonically encoded");
            }
            return snapshot;
        } catch (EOFException exception) {
            throw new IllegalArgumentException("LuckPerms snapshot is truncated", exception);
        } catch (IOException | DateTimeException exception) {
            throw new IllegalArgumentException("LuckPerms snapshot is malformed", exception);
        }
    }

    private static void writeNodes(DataOutputStream output, List<NodeRecord> nodes) throws IOException {
        if (nodes.size() > MAX_NODES) throw new IllegalArgumentException("Too many LuckPerms nodes");
        output.writeInt(nodes.size());
        for (NodeRecord node : nodes) {
            writeString(output, node.key());
            output.writeByte(node.value() ? 1 : 0);
            output.writeByte(node.expiry() == null ? 0 : 1);
            if (node.expiry() != null) {
                output.writeLong(node.expiry().getEpochSecond());
                output.writeInt(node.expiry().getNano());
            }
            output.writeByte(node.inheritanceTarget() == null ? 0 : 1);
            if (node.inheritanceTarget() != null) writeString(output, node.inheritanceTarget());
            output.writeInt(node.contexts().size());
            for (ContextPair context : node.contexts()) {
                writeString(output, context.key());
                writeString(output, context.value());
            }
        }
    }

    private static List<NodeRecord> readNodes(DataInputStream input, NodeBudget budget) throws IOException {
        int count = readCount(input, MAX_NODES, "node count");
        budget.add(count);
        ArrayList<NodeRecord> nodes = new ArrayList<>(count);
        NodeRecord previous = null;
        for (int index = 0; index < count; index++) {
            String key = readRequiredString(input, "node key");
            boolean value = readStrictBoolean(input, "node value");
            boolean hasExpiry = readStrictBoolean(input, "expiry flag");
            Instant expiry = null;
            if (hasExpiry) {
                long epochSecond = input.readLong();
                int nano = input.readInt();
                if (nano < 0 || nano > 999_999_999) throw new IllegalArgumentException("Invalid node expiry nanos");
                expiry = Instant.ofEpochSecond(epochSecond, nano);
            }
            boolean hasInheritanceTarget = readStrictBoolean(input, "inheritance flag");
            String inheritanceTarget = hasInheritanceTarget
                    ? readRequiredString(input, "inherited group name") : null;
            int contextCount = readCount(input, MAX_CONTEXTS_PER_NODE, "context count");
            ArrayList<ContextPair> contexts = new ArrayList<>(contextCount);
            ContextPair previousContext = null;
            for (int contextIndex = 0; contextIndex < contextCount; contextIndex++) {
                ContextPair context = new ContextPair(readString(input, "context key"), readString(input, "context value"));
                if (previousContext != null && CONTEXT_ORDER.compare(previousContext, context) >= 0) {
                    throw new IllegalArgumentException("LuckPerms contexts are duplicated or not canonical");
                }
                contexts.add(context);
                previousContext = context;
            }
            NodeRecord node = new NodeRecord(key, value, List.copyOf(contexts), expiry, inheritanceTarget);
            if (previous != null && NODE_ORDER.compare(previous, node) >= 0) {
                throw new IllegalArgumentException("LuckPerms nodes are duplicated or not canonical");
            }
            nodes.add(node);
            previous = node;
        }
        rejectDuplicateOrConflictingNodes(nodes, "LuckPerms snapshot nodes");
        return List.copyOf(nodes);
    }

    private static void validateGraph(Snapshot snapshot) {
        if (!snapshot.groups().containsKey(snapshot.primaryGroup())) {
            throw new IllegalArgumentException("LuckPerms primary group is absent from the inherited group graph");
        }
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(snapshot.primaryGroup());
        addTargets(snapshot.userPersistent(), pending);
        addTargets(snapshot.userTransient(), pending);
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            GroupRecord group = snapshot.groups().get(name);
            if (group == null) throw new IllegalArgumentException("LuckPerms inherited group is missing: " + name);
            if (!visited.add(name)) continue;
            addTargets(group.persistent(), pending);
            addTargets(group.transientNodes(), pending);
        }
        if (!visited.equals(snapshot.groups().keySet())) {
            throw new IllegalArgumentException("LuckPerms snapshot contains unreachable groups");
        }
    }

    private static void addTargets(List<NodeRecord> nodes, ArrayDeque<String> pending) {
        for (NodeRecord node : nodes) {
            if (node.inheritanceTarget() != null) pending.addLast(node.inheritanceTarget());
        }
    }

    private static void rejectDuplicateContexts(List<ContextPair> contexts) {
        for (int index = 1; index < contexts.size(); index++) {
            if (CONTEXT_ORDER.compare(contexts.get(index - 1), contexts.get(index)) == 0) {
                throw new IllegalStateException("LuckPerms node has duplicate contexts");
            }
        }
    }

    private static void rejectDuplicateOrConflictingNodes(List<NodeRecord> nodes, String description) {
        Set<NodeRecord> exact = new HashSet<>();
        HashMap<NodeConflictKey, NodeRecord> byConflictKey = new HashMap<>();
        for (NodeRecord node : nodes) {
            if (!exact.add(node)) throw new IllegalArgumentException(description + " contain a duplicate node");
            NodeConflictKey key = new NodeConflictKey(node.key(), node.contexts(), node.expiry());
            NodeRecord prior = byConflictKey.putIfAbsent(key, node);
            if (prior != null && (prior.value() != node.value()
                    || !Objects.equals(prior.inheritanceTarget(), node.inheritanceTarget()))) {
                throw new IllegalArgumentException(description + " contain conflicting nodes");
            }
        }
    }

    private static int compareContexts(List<ContextPair> left, List<ContextPair> right) {
        int shared = Math.min(left.size(), right.size());
        for (int index = 0; index < shared; index++) {
            int compared = CONTEXT_ORDER.compare(left.get(index), right.get(index));
            if (compared != 0) return compared;
        }
        return Integer.compare(left.size(), right.size());
    }

    private static int compareNullableInstants(Instant left, Instant right) {
        if (left == right) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        return left.compareTo(right);
    }

    private static int compareNullableStrings(String left, String right) {
        if (left == right) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        return left.compareTo(right);
    }

    private static int readCount(DataInputStream input, int maximum, String description) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > maximum) throw new IllegalArgumentException("Invalid " + description);
        return count;
    }

    private static boolean readStrictBoolean(DataInputStream input, String description) throws IOException {
        int value = input.readUnsignedByte();
        if (value != 0 && value != 1) throw new IllegalArgumentException("Invalid " + description);
        return value == 1;
    }

    private static String readRequiredString(DataInputStream input, String description) throws IOException {
        String value = readString(input, description);
        if (value.isBlank()) throw new IllegalArgumentException("Empty " + description);
        return value;
    }

    private static String readString(DataInputStream input, String description) throws IOException {
        int length = readCount(input, MAX_STRING_BYTES, description + " length");
        byte[] encoded = input.readNBytes(length);
        if (encoded.length != length) throw new EOFException();
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Invalid UTF-8 in " + description, exception);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] encoded;
        try {
            ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            encoded = new byte[buffer.remaining()];
            buffer.get(encoded);
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("LuckPerms returned a string which is not valid Unicode", exception);
        }
        if (encoded.length > MAX_STRING_BYTES) throw new IllegalArgumentException("LuckPerms string exceeds the snapshot limit");
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String requireName(String value, String description) {
        if (value == null || value.isBlank()) throw new IllegalStateException("LuckPerms returned an empty " + description);
        return value;
    }

    private static String requireString(String value, String description) {
        if (value == null) throw new IllegalStateException("LuckPerms returned a null " + description);
        return value;
    }

    private record Snapshot(UUID playerId, String primaryGroup, List<NodeRecord> userPersistent,
            List<NodeRecord> userTransient, TreeMap<String, GroupRecord> groups) {}

    private record GroupRecord(String name, List<NodeRecord> persistent, List<NodeRecord> transientNodes) {}

    private record NodeRecord(String key, boolean value, List<ContextPair> contexts, Instant expiry,
            String inheritanceTarget) {}

    private record ContextPair(String key, String value) {}

    private record NodeConflictKey(String key, List<ContextPair> contexts, Instant expiry) {}

    private static final class NodeBudget {
        private int total;

        private void add(int count) {
            total += count;
            if (total > MAX_NODES) throw new IllegalArgumentException("LuckPerms snapshot has too many nodes");
        }
    }

    private static final class ReadBudget {
        private int totalNodes;
        private long encodedBytes = Integer.BYTES + 1L + 2L * Long.BYTES;

        private void addOne() {
            if (++totalNodes > MAX_NODES) {
                throw new IllegalStateException("LuckPerms state exceeds the node snapshot limit");
            }
        }

        private void addString(String value) {
            final int byteLength;
            try {
                byteLength = StandardCharsets.UTF_8.newEncoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .encode(java.nio.CharBuffer.wrap(value)).remaining();
            } catch (CharacterCodingException exception) {
                throw new IllegalStateException("LuckPerms returned a string which is not valid Unicode", exception);
            }
            if (byteLength > MAX_STRING_BYTES) {
                throw new IllegalStateException("LuckPerms string exceeds the snapshot limit");
            }
            reserve(Integer.BYTES + (long) byteLength);
        }

        private void reserve(long additionalBytes) {
            encodedBytes += additionalBytes;
            if (additionalBytes < 0 || encodedBytes > MAX_PAYLOAD_BYTES) {
                throw new IllegalStateException("LuckPerms state exceeds the 8 MiB snapshot limit");
            }
        }
    }

    private static final class LimitedByteArrayOutputStream extends ByteArrayOutputStream {
        private final int maximum;

        private LimitedByteArrayOutputStream(int maximum) {
            this.maximum = maximum;
        }

        @Override
        public synchronized void write(int value) {
            ensureCapacityFor(1);
            super.write(value);
        }

        @Override
        public synchronized void write(byte[] buffer, int offset, int length) {
            ensureCapacityFor(length);
            super.write(buffer, offset, length);
        }

        private void ensureCapacityFor(int additional) {
            if (additional < 0 || count > maximum - additional) {
                throw new IllegalArgumentException("LuckPerms snapshot exceeds 8 MiB");
            }
        }
    }
}
