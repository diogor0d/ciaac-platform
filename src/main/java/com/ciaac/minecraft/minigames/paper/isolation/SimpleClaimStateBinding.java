package com.ciaac.minecraft.minigames.paper.isolation;

import java.lang.reflect.Method;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/** Exact loaded SCS model and read-only index-completeness check; no singleton initialization. */
public final class SimpleClaimStateBinding {
    private static final String VERSION = "1.13.1";
    private static final int MAX_GLOBAL_CLAIMS = 65_536;
    private static final int MAX_COLLECTION = 100_000;

    private SimpleClaimStateBinding() {}

    public static ExternalStateAuthority create(Server server, Plugin plugin) {
        return new SimpleClaimStateAuthority(new Access(server, plugin));
    }

    private record ClaimKey(UUID owner, int id) {}

    private static final class Access implements SimpleClaimStateAuthority.Access {
        private final Server server;
        private final Plugin plugin;
        private final Getter mainGetter, settingsGetter, playersGetter, sourceGetter, folia;
        private final Method allClaims, groups, groupSettings, playerConfig;
        private final Object main, settings, players;
        private final DataSource source;
        private final Map<String, Method> claimGetters = new LinkedHashMap<>();

        private Access(Server server, Plugin plugin) {
            this.server = Objects.requireNonNull(server, "server");
            this.plugin = Objects.requireNonNull(plugin, "plugin");
            if (!plugin.getClass().getName().equals("fr.xyness.SCS.SimpleClaimSystem")
                    || !VERSION.equals(plugin.getDescription().getVersion())) {
                throw new IllegalArgumentException("Unsupported SimpleClaimSystem model version");
            }
            try {
                var type = plugin.getClass();
                ClassLoader loader = type.getClassLoader();
                // Class.getMethod resolves every declared signature, including absent optional map integrations.
                // Exact MethodHandles lookup resolves only this getter and its pinned return type.
                mainGetter = getter(type, "getMain", Class.forName("fr.xyness.SCS.ClaimMain", false, loader));
                settingsGetter = getter(type, "getSettings", Class.forName("fr.xyness.SCS.Config.ClaimSettings", false, loader));
                playersGetter = getter(type, "getPlayerMain", Class.forName("fr.xyness.SCS.CPlayerMain", false, loader));
                sourceGetter = getter(type, "getDataSource", Class.forName("fr.xyness.libs.hikari.HikariDataSource", false, loader));
                folia = getter(type, "isFolia", boolean.class);
                main = Objects.requireNonNull(mainGetter.invoke(plugin), "loaded ClaimMain");
                settings = Objects.requireNonNull(settingsGetter.invoke(plugin), "loaded ClaimSettings");
                players = Objects.requireNonNull(playersGetter.invoke(plugin), "loaded CPlayerMain");
                source = (DataSource)Objects.requireNonNull(sourceGetter.invoke(plugin), "loaded claims datasource");
                allClaims = main.getClass().getMethod("getAllClaims");
                groups = settings.getClass().getMethod("getGroupsValues");
                groupSettings = settings.getClass().getMethod("getGroupsSettings");
                playerConfig = players.getClass().getMethod("getPlayerConfig", UUID.class);
                Class<?> claim = Class.forName("fr.xyness.SCS.Types.Claim", false, type.getClassLoader());
                for (String name : List.of("getUUID", "getId", "getOwner", "getName", "getDescription", "getLocation",
                        "getChunks", "getMembers", "getPermissions", "getSale", "getPrice", "getBans")) {
                    claimGetters.put(name, claim.getMethod(name));
                }
            } catch (ReflectiveOperationException | ClassCastException | LinkageError incompatible) {
                throw new IllegalArgumentException("Pinned SimpleClaimSystem model is unavailable", incompatible);
            }
        }

        @Override public boolean available() {
            if (!plugin.isEnabled() || server.getPluginManager().getPlugin("SimpleClaimSystem") != plugin
                    || !VERSION.equals(plugin.getDescription().getVersion())) return false;
            try {
                return Boolean.FALSE.equals(folia.invoke(plugin)) && mainGetter.invoke(plugin) == main
                        && settingsGetter.invoke(plugin) == settings && playersGetter.invoke(plugin) == players
                        && sourceGetter.invoke(plugin) == source;
            } catch (RuntimeException | LinkageError unavailable) { return false; }
        }

        @Override public SimpleClaimStateAuthority.State readLoaded(UUID playerId) {
            try {
                Object value = allClaims.invoke(main);
                if (!(value instanceof Collection<?> collection) || collection.size() > MAX_GLOBAL_CLAIMS) {
                    throw new IllegalStateException("Invalid global claim index");
                }
                List<?> loaded = new ArrayList<>(collection);
                Set<ClaimKey> indexed = new HashSet<>();
                List<SimpleClaimStateAuthority.Claim> relevant = new ArrayList<>();
                for (Object claim : loaded) {
                    UUID owner = (UUID)get(claim, "getUUID");
                    int id = (Integer)get(claim, "getId");
                    if (!indexed.add(new ClaimKey(Objects.requireNonNull(owner), id))) {
                        throw new IllegalStateException("Duplicate owner-scoped claim identity in loaded index");
                    }
                    Set<UUID> members = uuidSet(get(claim, "getMembers")), bans = uuidSet(get(claim, "getBans"));
                    if (!playerId.equals(owner) && !members.contains(playerId) && !bans.contains(playerId)) continue;
                    if (relevant.size() >= SimpleClaimStateAuthority.MAX_CLAIMS) throw new IllegalStateException("Too many player claims");
                    Location location = ((Location)Objects.requireNonNull(get(claim, "getLocation"), "claim location")).clone();
                    World world = requireLoadedWorld(location.getWorld());
                    Set<SimpleClaimStateAuthority.Chunk> chunks = new HashSet<>();
                    for (Object item : collection(get(claim, "getChunks"))) {
                        Chunk chunk = (Chunk)item;
                        World chunkWorld = requireLoadedWorld(chunk.getWorld());
                        if (!chunks.add(new SimpleClaimStateAuthority.Chunk(chunkWorld.getUID(), chunkWorld.getName(), chunk.getX(), chunk.getZ()))) {
                            throw new IllegalStateException("Duplicate claim chunk identity");
                        }
                    }
                    Map<String, Map<String, Boolean>> permissions = booleanMap(get(claim, "getPermissions"));
                    relevant.add(new SimpleClaimStateAuthority.Claim(owner, id, (String)get(claim, "getOwner"),
                            (String)get(claim, "getName"), (String)get(claim, "getDescription"), world.getUID(), world.getName(),
                            new SimpleClaimStateAuthority.Position(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch()),
                            chunks, members, permissions, (Boolean)get(claim, "getSale"), (Long)get(claim, "getPrice"), bans));
                    if (!owner.equals(get(claim, "getUUID")) || id != (Integer)get(claim, "getId")) {
                        throw new IllegalStateException("Claim identity changed during capture");
                    }
                }
                requireCompleteIndex(indexed);
                List<SimpleClaimStateAuthority.GroupRule> rules = new ArrayList<>();
                for (var entry : map(groups.invoke(settings)).entrySet()) {
                    rules.add(new SimpleClaimStateAuthority.GroupRule((String)entry.getKey(), (String)entry.getValue()));
                }
                Map<String, Map<String, Double>> configuredGroups = nestedDoubles(groupSettings.invoke(settings));
                Map<String, Double> defaults = configuredGroups.get("default");
                if (defaults == null || defaults.isEmpty()) throw new IllegalStateException("SCS default quota policy is absent");
                Map<String, String> encodedDefaults = new TreeMap<>();
                defaults.forEach((key, number) -> encodedDefaults.put(key, Double.toHexString(number)));
                Object configuredPlayer = playerConfig.invoke(players, playerId);
                Map<String, Double> playerSettings = configuredPlayer == null ? Map.of() : doubles(configuredPlayer);
                return new SimpleClaimStateAuthority.State(playerId, relevant,
                        new SimpleClaimStateAuthority.Policy(encodedDefaults, rules, configuredGroups, playerSettings));
            } catch (ReflectiveOperationException | ClassCastException incompatible) {
                throw new IllegalStateException("Could not read the pinned loaded claim model", incompatible);
            }
        }

        private Object get(Object claim, String name) throws ReflectiveOperationException {
            return claimGetters.get(name).invoke(claim);
        }

        private World requireLoadedWorld(World world) {
            if (world == null || server.getWorld(world.getUID()) != world) throw new IllegalStateException("Claim world is not currently loaded");
            return world;
        }

        private void requireCompleteIndex(Set<ClaimKey> indexed) {
            Set<ClaimKey> stored = new HashSet<>();
            // Literal SELECT on the existing datasource: no account/config writes or credential reads.
            try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                statement.setQueryTimeout(2); statement.setMaxRows(MAX_GLOBAL_CLAIMS + 1);
                try (var rows = statement.executeQuery("SELECT id_claim, owner_uuid FROM scs_claims_1")) {
                    int count = 0;
                    while (rows.next()) {
                        if (++count > MAX_GLOBAL_CLAIMS) throw new IllegalStateException("Persistent claim index exceeds capture limit");
                        int id = rows.getInt(1);
                        if (rows.wasNull()) throw new IllegalStateException("Persistent claim ID is null");
                        UUID owner = UUID.fromString(rows.getString(2));
                        if (!stored.add(new ClaimKey(owner, id))) throw new IllegalStateException("Duplicate persistent claim identity");
                    }
                }
            } catch (SQLException unavailable) { throw new IllegalStateException("Cannot confirm the persistent claim index", unavailable); }
            if (!stored.equals(indexed)) throw new IllegalStateException("Loaded claim index differs from persistent claims; recovery requires reconciliation");
        }
    }

    private static Getter getter(Class<?> type, String name, Class<?> result) throws ReflectiveOperationException {
        return new Getter(MethodHandles.publicLookup().findVirtual(type, name, MethodType.methodType(result)));
    }

    private record Getter(MethodHandle handle) {
        Object invoke(Object receiver) {
            try { return handle.invoke(receiver); }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new IllegalStateException("Pinned claim getter failed", failure); }
        }
    }

    private static Collection<?> collection(Object value) {
        if (!(value instanceof Collection<?> items) || items.size() > MAX_COLLECTION) throw new IllegalStateException("Invalid claim collection");
        return items;
    }

    private static Map<?, ?> map(Object value) {
        if (!(value instanceof Map<?, ?> entries) || entries.size() > MAX_COLLECTION) throw new IllegalStateException("Invalid claim map");
        return entries;
    }

    private static Set<UUID> uuidSet(Object value) {
        Set<UUID> result = new HashSet<>();
        for (Object item : collection(value)) {
            if (result.size() >= MAX_COLLECTION || !result.add((UUID)Objects.requireNonNull(item))) throw new IllegalStateException("Invalid claim UUID set");
        }
        return Set.copyOf(result);
    }

    private static Map<String, Map<String, Boolean>> booleanMap(Object value) {
        Map<String, Map<String, Boolean>> result = new TreeMap<>();
        for (var role : map(value).entrySet()) {
            Map<String, Boolean> permissions = new TreeMap<>();
            for (var permission : map(role.getValue()).entrySet()) {
                permissions.put((String)permission.getKey(), (Boolean)Objects.requireNonNull(permission.getValue()));
            }
            result.put((String)role.getKey(), Map.copyOf(permissions));
        }
        return Map.copyOf(result);
    }

    private static Map<String, Double> doubles(Object value) {
        Map<String, Double> result = new TreeMap<>();
        for (var entry : map(value).entrySet()) {
            result.put((String)entry.getKey(), (Double)Objects.requireNonNull(entry.getValue()));
        }
        return Map.copyOf(result);
    }

    private static Map<String, Map<String, Double>> nestedDoubles(Object value) {
        Map<String, Map<String, Double>> result = new TreeMap<>();
        for (var entry : map(value).entrySet()) result.put((String)entry.getKey(), doubles(entry.getValue()));
        return Map.copyOf(result);
    }
}
