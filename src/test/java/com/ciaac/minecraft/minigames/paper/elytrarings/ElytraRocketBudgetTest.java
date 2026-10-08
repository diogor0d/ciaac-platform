package com.ciaac.minecraft.minigames.paper.elytrarings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.elytrarings.ElytraCourseRevision;
import com.ciaac.minecraft.minigames.elytrarings.ElytraRingsConfig;
import com.ciaac.minecraft.minigames.elytrarings.ElytraRingsGame;
import com.ciaac.minecraft.minigames.elytrarings.ElytraRingsPhase;
import com.ciaac.minecraft.minigames.elytrarings.RingCheckpoint;
import com.ciaac.minecraft.minigames.isolation.InMemorySnapshotRepository;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.PlayerStateGateway;
import com.ciaac.minecraft.minigames.isolation.PlayerStateSnapshot;
import com.ciaac.minecraft.minigames.isolation.SnapshotEnvelopeCodec;
import com.ciaac.minecraft.minigames.persistence.SessionRepository;
import com.ciaac.minecraft.minigames.paper.TemporaryItemTagger;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import com.ciaac.minecraft.minigames.region.RegionAdmissionRegistry;
import com.ciaac.minecraft.minigames.region.RegionAdmissionToken;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequest;
import com.ciaac.minecraft.minigames.runtime.AdmissionResult;
import com.ciaac.minecraft.minigames.runtime.AdmissionStatus;
import com.ciaac.minecraft.minigames.runtime.AuthenticatedSession;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.OperationIds;
import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import com.ciaac.minecraft.minigames.runtime.SessionCoordinator;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import com.ciaac.minecraft.minigames.statistics.recording.StatisticsResultSink;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.entity.Firework;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ElytraRocketBudgetTest {
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private Field bukkitServerField;
    private Object previousBukkitServer;

    @BeforeEach void captureBukkitServer() throws ReflectiveOperationException {
        bukkitServerField = Bukkit.class.getDeclaredField("server");
        bukkitServerField.setAccessible(true);
        previousBukkitServer = bukkitServerField.get(null);
    }

    @AfterEach void restoreBukkitServer() throws IllegalAccessException {
        bukkitServerField.set(null, previousBukkitServer);
    }

    @Test
    void repeatedGroundUsesAndCancelledBoostsNeverConsumeTheBudget() {
        Harness h = new Harness(2);
        ItemStack rocket = taggedItem(h.plugin, h.session.sessionId());
        h.gliding[0] = false;
        for (int i = 0; i < 5; i++) {
            PlayerInteractEvent groundUse = interact(h.player, rocket);
            assertTrue(h.controller.onRocketUse(groundUse));
            assertTrue(groundUse.isCancelled());
            assertFalse(h.controller.allowsRocketBoost(boost(h.player, rocket, true)));
        }
        h.gliding[0] = true;
        var cancelledBoost = boost(h.player, rocket, false);
        cancelledBoost.setCancelled(true);
        assertFalse(h.controller.allowsRocketBoost(cancelledBoost));

        for (int i = 0; i < 5; i++) {
            PlayerInteractEvent use = interact(h.player, rocket);
            boolean originallyCancelled = use.isCancelled();
            assertTrue(h.controller.onRocketUse(use));
            assertEquals(originallyCancelled, use.isCancelled(), "preflight must not override Paper cancellation");
            // A permitted interaction is still only a preflight check; Paper may cancel the native boost.
        }
        assertTrue(h.controller.allowsRocketBoost(boost(h.player, rocket, false)));
    }

    @Test
    void onlyConfirmedNativeInsertionConsumesOneBudgetUnitAndDuplicateUuidIsIdempotent() {
        Harness h = new Harness(2);
        ItemStack rocket = taggedItem(h.plugin, h.session.sessionId());
        var boost = boost(h.player, rocket, false);
        assertTrue(h.controller.allowsRocketBoost(boost));

        UUID inserted = UUID.randomUUID();
        h.controller.rocketInserted(inserted); // Called after exact native insertion confirmation.
        assertTrue(h.controller.allowsRocketBoost(boost));
        h.controller.rocketInserted(inserted);
        assertTrue(h.controller.allowsRocketBoost(boost), "duplicate insertion notification must not consume another unit");

        h.controller.rocketInserted(UUID.randomUUID());
        assertFalse(h.controller.allowsRocketBoost(boost));
    }

    @Test
    void untaggedOutOfSessionAndGroundBoostsAreDenied() {
        Harness h = new Harness(2);
        ItemStack untagged = new TestItemStack();
        assertFalse(h.controller.allowsRocketBoost(boost(h.player, untagged, false)));
        var deniedInteraction = interact(h.player, untagged);
        assertTrue(h.controller.onRocketUse(deniedInteraction));
        assertTrue(deniedInteraction.isCancelled());

        Player outsider = player(uuid(998), new Location[] {h.start.clone()}, new boolean[] {true});
        ItemStack sessionRocket = taggedItem(h.plugin, h.session.sessionId());
        assertFalse(h.controller.allowsRocketBoost(boost(outsider, sessionRocket, false)));

        h.gliding[0] = false;
        assertFalse(h.controller.allowsRocketBoost(boost(h.player, sessionRocket, false)));
    }

    @Test
    void exhaustedBudgetDeniesBoostBeforeAnyFurtherInsertion() {
        Harness h = new Harness(1);
        ItemStack rocket = taggedItem(h.plugin, h.session.sessionId());
        var boost = boost(h.player, rocket, false);
        assertTrue(h.controller.allowsRocketBoost(boost));

        h.controller.rocketInserted(UUID.randomUUID());

        assertFalse(h.controller.allowsRocketBoost(boost));
    }

    private static PlayerInteractEvent interact(Player player, ItemStack stack) {
        return new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, stack, null, BlockFace.SELF,
                EquipmentSlot.HAND);
    }

    private static com.destroystokyo.paper.event.player.PlayerElytraBoostEvent boost(
        Player player, ItemStack stack, boolean initiallyCancelled) {
        var event = new com.destroystokyo.paper.event.player.PlayerElytraBoostEvent(
                player, stack, proxy(Firework.class, (instance, method, args) -> defaultValue(method.getReturnType())),
                EquipmentSlot.HAND);
        event.setCancelled(initiallyCancelled);
        return event;
    }

    private static ItemStack taggedItem(Plugin plugin, UUID sessionId) {
        TestItemStack item = new TestItemStack();
        item.data.values.put(new NamespacedKey(plugin, "temporary-session"), sessionId.toString());
        return item;
    }

    private static UUID uuid(long value) { return new UUID(0, value); }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        throw new IllegalArgumentException(type.toString());
    }

    private static final class TestItemStack extends ItemStack {
        private final ItemData data = new ItemData();
        private final ItemMeta meta = proxy(ItemMeta.class, (instance, method, args) -> switch (method.getName()) {
            case "getPersistentDataContainer" -> data.container;
            default -> defaultValue(method.getReturnType());
        });
        private TestItemStack() { super(); }
        @Override public Material getType() { return Material.FIREWORK_ROCKET; }
        @Override public boolean isEmpty() { return false; }
        @Override public ItemMeta getItemMeta() { return meta; }
        @Override public PersistentDataContainer getPersistentDataContainer() { return data.container; }
    }

    private static final class ItemData {
        private final Map<NamespacedKey, Object> values = new HashMap<>();
        private final PersistentDataContainer container = proxy(PersistentDataContainer.class,
                (instance, method, args) -> switch (method.getName()) {
                    case "set" -> { values.put((NamespacedKey) args[0], args[2]); yield null; }
                    case "get" -> values.get(args[0]);
                    case "has" -> values.containsKey(args[0]);
                    case "remove" -> { values.remove(args[0]); yield null; }
                    case "getKeys" -> Set.copyOf(values.keySet());
                    case "isEmpty" -> values.isEmpty();
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static final class MemorySessionRepository implements SessionRepository {
        private final Map<UUID, PlayerSession> sessions = new HashMap<>();
        @Override public boolean save(PlayerSession session) { sessions.put(session.sessionId(), session); return true; }
        @Override public Optional<PlayerSession> find(UUID sessionId) { return Optional.ofNullable(sessions.get(sessionId)); }
        @Override public List<PlayerSession> nonTerminal() {
            return sessions.values().stream().filter(session -> !session.phase().terminal()).toList();
        }
    }

    private static final class Gateway implements PlayerStateGateway {
        @Override public Set<PlayerStateFacet> supportedFacets() { return EnumSet.allOf(PlayerStateFacet.class); }
        @Override public PlayerStateSnapshot capture(UUID snapshotId, UUID operationId, UUID sessionId,
                UUID matchId, UUID playerId, UUID connectionId, GameKey game, Instant capturedAt) {
            return new PlayerStateSnapshot(2, snapshotId, operationId, sessionId, matchId,
                    playerId, connectionId, game, capturedAt, Map.of());
        }
        @Override public void enterTemporaryState(UUID operationId, PlayerStateSnapshot snapshot) {}
        @Override public void purgeTemporaryState(UUID operationId, PlayerStateSnapshot snapshot) {}
        @Override public void restore(UUID operationId, PlayerStateSnapshot snapshot) {}
    }

    private static final class MutableClock extends Clock {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return NOW; }
    }

    private static final class Harness {
        private final UUID matchId = UUID.randomUUID();
        private final UUID playerId = uuid(42);
        private final UUID worldId = UUID.randomUUID();
        private final boolean[] gliding = {true};
        private final World world = proxy(World.class, (instance, method, args) -> switch (method.getName()) {
            case "getUID" -> worldId;
            case "getName" -> "elytra-budget-world";
            case "isChunkLoaded" -> true;
            case "getPlayers" -> List.of();
            case "addPluginChunkTicket", "removePluginChunkTicket" -> true;
            default -> defaultValue(method.getReturnType());
        });
        private final Location start = new Location(world, .5, 65, .5);
        private final Player player = player(playerId, new Location[] {start.clone()}, gliding);
        private final SessionRegistry sessions = new SessionRegistry();
        private final AuthenticationRegistry authentication = new AuthenticationRegistry();
        private final MemorySessionRepository repository = new MemorySessionRepository();
        private final RegionAdmissionRegistry admissions = new RegionAdmissionRegistry();
        private final SessionCoordinator coordinator;
        private final ElytraRingsGame game;
        private final ElytraRingsController controller;
        private final PlayerSession session;
        private final Plugin plugin;

        private Harness(int rocketCount) {
            Server server = proxy(Server.class, (instance, method, args) -> switch (method.getName()) {
                case "isPrimaryThread" -> true;
                default -> defaultValue(method.getReturnType());
            });
            installBukkitServer(server);
            plugin = proxy(Plugin.class, (instance, method, args) -> switch (method.getName()) {
                case "namespace" -> "ciaacplatform";
                case "getName" -> "test-plugin";
                case "getServer" -> server;
                default -> defaultValue(method.getReturnType());
            });
            var course = new ElytraCourseRevision("v1", worldId.toString(), List.of(
                    new RingCheckpoint(1, 1.5, 65, .5), new RingCheckpoint(2, 4.5, 65, .5)));
            var config = ElytraRingsConfig.dedicatedWorld(Duration.ofSeconds(10), course);
            var settings = new ElytraRingsPaperSettings(true, config, world, "elytra-boundary", start,
                    3.0, rocketCount, 0, List.of());
            ProtectedRegionRegistry regions = new ProtectedRegionRegistry();
            regions.register(new ProtectedRegion("elytra-boundary", GameKey.ELYTRA_RINGS,
                    new CuboidRegion(worldId, -10, 0, -10, 10, 200, 10),
                    ProtectedRegionRole.PARTICIPANT_ONLY, true));
            MutableClock clock = new MutableClock();
            coordinator = new SessionCoordinator(authentication, sessions, repository,
                    new InMemorySnapshotRepository(), new Gateway(), IsolationPolicy.strictNoProgress(),
                    new SnapshotEnvelopeCodec(), clock);
            game = new ElytraRingsGame(matchId, playerId, config);
            TemporaryItemTagger tagger = new TemporaryItemTagger(plugin);
            ElytraChunkPreparation chunks = new ElytraChunkPreparation(settings, plugin);
            controller = new ElytraRingsController(matchId, game, settings, coordinator, sessions,
                    admissions, regions, tagger, clock, StatisticsResultSink.unavailable(), chunks, ignored -> true);
            chunks.tick();
            if (!chunks.admissionReady()) throw new AssertionError("Elytra test world should be admission-ready");
            UUID connectionId = uuid(43), requestId = uuid(44), sessionId = uuid(45), snapshotId = uuid(46);
            authentication.authenticated(new AuthenticatedSession(uuid(47), playerId, connectionId,
                    NOW.minusSeconds(1), NOW.plusSeconds(600)));
            AdmissionRequest request = new AdmissionRequest(requestId, sessionId, snapshotId,
                    matchId, playerId, connectionId, GameKey.ELYTRA_RINGS, NOW);
            AdmissionResult prepared = coordinator.prepare(request);
            assertEquals(AdmissionStatus.PREPARED, prepared.status());
            assertTrue(coordinator.activate(sessionId, OperationIds.derive(requestId, "GAME_ACTIVE"), NOW));
            session = prepared.session().orElseThrow();
            admissions.issue(new RegionAdmissionToken(uuid(48), sessionId, playerId,
                    "elytra-boundary", NOW, NOW.plusSeconds(60)));
            game.open(new com.ciaac.minecraft.minigames.hotpotato.OperationId(matchId, 1));
            game.start(NOW, new com.ciaac.minecraft.minigames.hotpotato.OperationId(matchId, 2));
            setField(controller, "operationSequence", 2L);
            setField(controller, "startedAt", NOW);
        }

    }

    private static Player player(UUID id, Location[] location, boolean[] gliding) {
        return proxy(Player.class, (instance, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> id;
            case "isOnline", "isValid" -> true;
            case "isGliding" -> gliding[0];
            case "getLocation" -> location[0].clone();
            case "teleport" -> true;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    private static void installBukkitServer(Server server) {
        try {
            Field field = Bukkit.class.getDeclaredField("server");
            field.setAccessible(true);
            field.set(null, server);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not install scoped Bukkit server proxy", failure);
        }
    }
}
