package com.ciaac.minecraft.minigames.paper.isolation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.Context;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.data.DataType;
import net.luckperms.api.model.data.NodeMap;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.group.GroupManager;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import org.junit.jupiter.api.Test;

class LuckPermsStateAuthorityTest {
    private static final UUID PLAYER = UUID.fromString("c33d1203-6876-4f3a-a899-6d76df1cf659");
    private static final UUID OTHER_PLAYER = UUID.fromString("7f1102f7-1fc8-4da2-8733-5c2a7927a961");

    @Test
    void encodingIsOrderIndependentAndKeepsNodeFieldsAndDataBucketsSeparate() throws Exception {
        Instant expiry = Instant.parse("2026-11-01T12:13:14.123456789Z");
        Node deny = node("minecraft.command.kill", false,
                List.of(context("world", "nether"), context("server", "survival")), expiry, null, false);
        Node allow = node("minecraft.command.say", true, List.of(), null, null, false);
        Node temporary = node("ciaac.session.boost", true, List.of(context("server", "survival")), null, null, false);
        Group defaultGroup = group("default", List.of(node("group.permission", true, List.of(), null, null, false)), List.of());

        LuckPermsStateAuthority firstReader = authority(List.of(deny, allow), List.of(temporary),
                Map.of("default", defaultGroup), true, PLAYER);
        byte[] first = firstReader.read(PLAYER);
        firstReader.validate(PLAYER, first);
        byte[] shuffled = authority(List.of(allow, deny), List.of(temporary), Map.of("default", defaultGroup), true, PLAYER)
                .read(PLAYER);

        assertArrayEquals(first, shuffled);
        SnapshotView view = decode(first);
        NodeView encodedDeny = view.userPersistent().stream()
                .filter(node -> node.key().equals("minecraft.command.kill")).findFirst().orElseThrow();
        assertFalse(encodedDeny.value());
        assertEquals(List.of(new Pair("server", "survival"), new Pair("world", "nether")), encodedDeny.contexts());
        assertEquals(expiry, encodedDeny.expiry());
        assertTrue(view.userPersistent().stream().noneMatch(node -> node.key().equals("ciaac.session.boost")));
        assertEquals("ciaac.session.boost", view.userTransient().getFirst().key());
        assertEquals("default", view.primaryGroup());
    }

    @Test
    void capturesRecursiveInheritedGroupsAndChangesWhenAnInheritedGroupChanges() throws Exception {
        Node userParent = inheritance("staff", true, false);
        Group defaultGroup = group("default", List.of(), List.of());
        Group staffGroup = group("staff", List.of(inheritance("base", true, false)), List.of());
        Group baseGroup = group("base", List.of(node("ciaac.build", true, List.of(), null, null, false)), List.of());
        Map<String, Group> groups = Map.of("default", defaultGroup, "staff", staffGroup, "base", baseGroup);

        LuckPermsStateAuthority originalReader = authority(List.of(userParent), List.of(), groups, true, PLAYER);
        byte[] original = originalReader.read(PLAYER);
        originalReader.validate(PLAYER, original);
        SnapshotView view = decode(original);
        assertEquals(List.of("base", "default", "staff"), view.groups().stream().map(GroupView::name).toList());
        assertEquals("base", view.groups().get(2).persistent().getFirst().inheritanceTarget());

        Group changedBase = group("base", List.of(node("ciaac.build", false, List.of(), null, null, false)), List.of());
        byte[] changed = authority(List.of(userParent), List.of(),
                Map.of("default", defaultGroup, "staff", staffGroup, "base", changedBase), true, PLAYER).read(PLAYER);
        assertFalse(java.util.Arrays.equals(original, changed));
    }

    @Test
    void traversesCyclicGroupsWithoutRepeatingOrLoadingThem() throws Exception {
        Group defaultGroup = group("default", List.of(), List.of());
        Group alpha = group("alpha", List.of(inheritance("beta", true, false)), List.of());
        Group beta = group("beta", List.of(inheritance("alpha", true, false)), List.of());

        byte[] payload = authority(List.of(inheritance("alpha", true, false)), List.of(),
                Map.of("default", defaultGroup, "alpha", alpha, "beta", beta), true, PLAYER).read(PLAYER);
        assertEquals(List.of("alpha", "beta", "default"), decode(payload).groups().stream().map(GroupView::name).toList());
    }

    @Test
    void rejectsMissingUsersGroupsWrongUuidsAndMalformedPayloads() {
        Group defaultGroup = group("default", List.of(), List.of());
        byte[] payload = authority(List.of(), List.of(), Map.of("default", defaultGroup), true, PLAYER).read(PLAYER);
        LuckPermsStateAuthority same = authority(List.of(), List.of(), Map.of("default", defaultGroup), true, PLAYER);
        same.validate(PLAYER, payload);

        assertThrows(IllegalArgumentException.class, () -> same.validate(OTHER_PLAYER, payload));
        assertThrows(IllegalArgumentException.class, () -> same.validate(PLAYER,
                java.util.Arrays.copyOf(payload, payload.length - 1)));
        byte[] withTrailingByte = java.util.Arrays.copyOf(payload, payload.length + 1);
        assertThrows(IllegalArgumentException.class, () -> same.validate(PLAYER, withTrailingByte));
        assertThrows(IllegalStateException.class,
                () -> authority(List.of(), List.of(), Map.of("default", defaultGroup), true, OTHER_PLAYER).read(PLAYER));
        assertThrows(IllegalStateException.class,
                () -> authority(List.of(), List.of(), Map.of("default", defaultGroup), true, null).read(PLAYER));
        assertThrows(IllegalStateException.class,
                () -> authority(List.of(inheritance("missing", true, false)), List.of(), Map.of("default", defaultGroup), true, PLAYER).read(PLAYER));
        assertThrows(IllegalStateException.class,
                () -> authority(List.of(), List.of(), Map.of(), true, PLAYER).read(PLAYER));
        assertThrows(IllegalStateException.class,
                () -> authority(List.of(), List.of(), Map.of("default", group("other", List.of(), List.of())), true, PLAYER)
                        .read(PLAYER));
        assertDoesNotThrow(() -> authority(List.of(), List.of(), Map.of("default", group("DEFAULT", List.of(), List.of())),
                true, PLAYER).read(PLAYER));
        assertThrows(IllegalStateException.class,
                () -> authority(List.of(), List.of(), Map.of("default", defaultGroup), false, PLAYER).read(PLAYER));
    }

    @Test
    void omitsAlreadyExpiredNodesAndDoesNotFollowTheirGroupEdges() throws Exception {
        Group defaultGroup = group("default", List.of(), List.of());
        Node expired = node("group.gone", true, List.of(), Instant.parse("2020-01-01T00:00:00Z"), "gone", true);
        byte[] payload = authority(List.of(expired), List.of(), Map.of("default", defaultGroup), true, PLAYER).read(PLAYER);
        SnapshotView view = decode(payload);
        assertTrue(view.userPersistent().isEmpty());
        assertEquals(List.of("default"), view.groups().stream().map(GroupView::name).toList());
    }

    @Test
    void omitsNodesAfterExpiryCrossesInsteadOfReconstructingThem() throws Exception {
        AtomicBoolean expired = new AtomicBoolean();
        Node temporary = node("ciaac.temporary", true, List.of(), Instant.parse("2030-01-01T00:00:00Z"), null, expired::get);
        Group defaultGroup = group("default", List.of(), List.of());
        LuckPermsStateAuthority reader = authority(List.of(temporary), List.of(), Map.of("default", defaultGroup), true, PLAYER);

        byte[] beforeExpiry = reader.read(PLAYER);
        reader.validate(PLAYER, beforeExpiry);
        assertEquals("ciaac.temporary", decode(beforeExpiry).userPersistent().getFirst().key());

        expired.set(true);
        byte[] afterExpiry = reader.read(PLAYER);
        reader.validate(PLAYER, afterExpiry);
        assertTrue(decode(afterExpiry).userPersistent().isEmpty());
        assertFalse(java.util.Arrays.equals(beforeExpiry, afterExpiry));
    }

    private static LuckPermsStateAuthority authority(List<Node> persistent, List<Node> transientNodes,
            Map<String, Group> groups, boolean healthy, UUID loadedUserId) {
        User user = user(loadedUserId, "default", persistent, transientNodes);
        UserManager users = proxy(UserManager.class, (instance, method, args) -> {
            return switch (method.getName()) {
                case "getUser" -> loadedUserId != null && PLAYER.equals(args[0]) ? user : null;
                case "loadUser", "modifyUser", "saveUser", "cleanupUser" -> throw new AssertionError("reader must not load or mutate users");
                default -> defaultValue(method.getReturnType());
            };
        });
        GroupManager groupManager = proxy(GroupManager.class, (instance, method, args) -> {
            return switch (method.getName()) {
                case "getGroup" -> groups.get(args[0]);
                case "loadGroup", "createAndLoadGroup", "modifyGroup", "saveGroup", "deleteGroup" ->
                        throw new AssertionError("reader must not load or mutate groups");
                default -> defaultValue(method.getReturnType());
            };
        });
        LuckPerms luckPerms = proxy(LuckPerms.class, (instance, method, args) -> switch (method.getName()) {
            case "getUserManager" -> users;
            case "getGroupManager" -> groupManager;
            default -> defaultValue(method.getReturnType());
        });
        BooleanSupplier health = () -> healthy;
        return new LuckPermsStateAuthority(luckPerms, health);
    }

    private static User user(UUID uniqueId, String primaryGroup, List<Node> persistent, List<Node> transientNodes) {
        return proxy(User.class, (instance, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> uniqueId;
            case "getPrimaryGroup" -> primaryGroup;
            case "getData" -> nodeMap(args[0] == DataType.NORMAL ? persistent : transientNodes);
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Group group(String name, List<Node> persistent, List<Node> transientNodes) {
        return proxy(Group.class, (instance, method, args) -> switch (method.getName()) {
            case "getName" -> name;
            case "getData" -> nodeMap(args[0] == DataType.NORMAL ? persistent : transientNodes);
            default -> defaultValue(method.getReturnType());
        });
    }

    private static NodeMap nodeMap(Collection<Node> nodes) {
        return proxy(NodeMap.class, (instance, method, args) ->
                method.getName().equals("toCollection") ? List.copyOf(nodes) : defaultValue(method.getReturnType()));
    }

    private static Node inheritance(String target, boolean value, boolean expired) {
        return node("group." + target, value, List.of(), null, target, expired);
    }

    private static Node node(String key, boolean value, List<Context> contexts, Instant expiry,
            String inheritanceTarget, boolean expired) {
        return node(key, value, contexts, expiry, inheritanceTarget, () -> expired);
    }

    private static Node node(String key, boolean value, List<Context> contexts, Instant expiry,
            String inheritanceTarget, BooleanSupplier expired) {
        Class<?>[] interfaces = inheritanceTarget == null
                ? new Class<?>[] {Node.class} : new Class<?>[] {InheritanceNode.class};
        return proxy((Class<Node>) Node.class, interfaces, (instance, method, args) -> switch (method.getName()) {
            case "getKey" -> key;
            case "getValue" -> value;
            case "getContexts" -> contextSet(contexts);
            case "hasExpiry" -> expiry != null;
            case "getExpiry" -> expiry;
            case "hasExpired" -> expired.getAsBoolean();
            case "getGroupName" -> inheritanceTarget;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Context context(String key, String value) {
        return proxy(Context.class, (instance, method, args) -> switch (method.getName()) {
            case "getKey" -> key;
            case "getValue" -> value;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static ImmutableContextSet contextSet(List<Context> contexts) {
        return proxy(ImmutableContextSet.class, (instance, method, args) -> switch (method.getName()) {
            case "iterator" -> contexts.iterator();
            case "size" -> contexts.size();
            case "isEmpty" -> contexts.isEmpty();
            default -> defaultValue(method.getReturnType());
        });
    }

    private static <T> T proxy(Class<T> primary, InvocationHandler handler) {
        return proxy(primary, new Class<?>[] {primary}, handler);
    }

    private static <T> T proxy(Class<T> primary, Class<?>[] interfaces, InvocationHandler handler) {
        Object value = Proxy.newProxyInstance(primary.getClassLoader(), interfaces, (instance, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> instance == args[0];
                    case "hashCode" -> System.identityHashCode(instance);
                    case "toString" -> "LuckPerms test proxy for " + primary.getSimpleName();
                    default -> null;
                };
            }
            return handler.invoke(instance, method, args == null ? new Object[0] : args);
        });
        return primary.cast(value);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return '\0';
        return null;
    }

    private static SnapshotView decode(byte[] payload) throws Exception {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            assertEquals(0x4c505331, input.readInt());
            assertEquals(1, input.readUnsignedByte());
            UUID player = new UUID(input.readLong(), input.readLong());
            String primary = readString(input);
            List<NodeView> persistent = readNodes(input);
            List<NodeView> transientNodes = readNodes(input);
            int groupCount = input.readInt();
            ArrayList<GroupView> groups = new ArrayList<>();
            for (int index = 0; index < groupCount; index++) {
                groups.add(new GroupView(readString(input), readNodes(input), readNodes(input)));
            }
            assertEquals(-1, input.read());
            return new SnapshotView(player, primary, persistent, transientNodes, groups);
        }
    }

    private static List<NodeView> readNodes(DataInputStream input) throws Exception {
        int count = input.readInt();
        ArrayList<NodeView> nodes = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String key = readString(input);
            boolean value = input.readUnsignedByte() == 1;
            boolean hasExpiry = input.readUnsignedByte() == 1;
            Instant expiry = hasExpiry ? Instant.ofEpochSecond(input.readLong(), input.readInt()) : null;
            boolean hasTarget = input.readUnsignedByte() == 1;
            String target = hasTarget ? readString(input) : null;
            int contextCount = input.readInt();
            ArrayList<Pair> contexts = new ArrayList<>();
            for (int contextIndex = 0; contextIndex < contextCount; contextIndex++) {
                contexts.add(new Pair(readString(input), readString(input)));
            }
            nodes.add(new NodeView(key, value, expiry, target, contexts));
        }
        return nodes;
    }

    private static String readString(DataInputStream input) throws Exception {
        int length = input.readInt();
        return new String(input.readNBytes(length), java.nio.charset.StandardCharsets.UTF_8);
    }

    private record SnapshotView(UUID player, String primaryGroup, List<NodeView> userPersistent,
            List<NodeView> userTransient, List<GroupView> groups) {}

    private record GroupView(String name, List<NodeView> persistent, List<NodeView> transientNodes) {}

    private record NodeView(String key, boolean value, Instant expiry, String inheritanceTarget, List<Pair> contexts) {}

    private record Pair(String key, String value) {}
}
