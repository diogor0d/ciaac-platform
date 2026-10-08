package com.ciaac.minecraft.minigames.paper.module;

import static org.junit.jupiter.api.Assertions.*;

import com.ciaac.minecraft.minigames.arena.ArenaEquipmentContract;
import com.ciaac.minecraft.minigames.arena.ArenaFormatPolicy;
import com.ciaac.minecraft.minigames.arena.ArenaLocationPolicy;
import com.ciaac.minecraft.minigames.arena.ArenaPartyRegistry;
import com.ciaac.minecraft.minigames.arena.ArenaPhase;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.isolation.PlayerStateGateway;
import com.ciaac.minecraft.minigames.isolation.SnapshotEnvelopeCodec;
import com.ciaac.minecraft.minigames.isolation.SnapshotRepository;
import com.ciaac.minecraft.minigames.paper.TemporaryItemTagger;
import com.ciaac.minecraft.minigames.paper.arena.ColiseumController;
import com.ciaac.minecraft.minigames.paper.arena.ColiseumSettings;
import com.ciaac.minecraft.minigames.persistence.SessionRepository;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.RegionAdmissionRegistry;
import com.ciaac.minecraft.minigames.runtime.*;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

final class ColiseumAuthenticationTest {
    private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void unauthenticatedChallengerCannotInspectEquipmentOrCreateChallenge() {
        Fixture f = new Fixture();
        f.authenticate(f.target);
        assertEquals("AUTHENTICATION_REQUIRED", f.challenge().code());
        assertEquals(0, f.equipmentReads.get());
        assertTrue(f.controller.currentMatch().isEmpty());
    }

    @Test
    void unauthenticatedTargetCannotBeChallenged() {
        Fixture f = new Fixture();
        f.authenticate(f.challenger);
        assertEquals("AUTHENTICATION_REQUIRED", f.challenge().code());
        assertEquals(0, f.equipmentReads.get());
    }

    @Test
    void validAuthenticationAllowsNormalChallengeAndReservation() {
        Fixture f = new Fixture();
        System.gc();
        f.authenticate(f.challenger);
        f.authenticate(f.target);
        assertEquals("CHALLENGE_CREATED", f.challenge().code());
        assertEquals("CHALLENGE_ACCEPTED", f.accept().code());
        assertEquals(ArenaPhase.RESERVED_READY, f.controller.currentMatch().orElseThrow().phase());
    }

    @Test
    void invalidatedTargetCannotReservePendingChallenge() {
        Fixture f = new Fixture();
        f.authenticate(f.challenger);
        f.authenticate(f.target);
        assertEquals("CHALLENGE_CREATED", f.challenge().code());
        f.authentication.invalidatePlayer(f.target.getUniqueId());
        assertEquals("AUTHENTICATION_REQUIRED", f.accept().code());
        assertTrue(f.controller.currentMatch().isEmpty());
    }

    @Test
    void frozenRosterMemberMustRemainAuthenticatedEvenAfterLeavingParty() {
        Fixture f = new Fixture();
        Player teammate = f.add("Teammate");
        Player opponent = f.add("Opponent");
        for (Player p : List.of(f.challenger, f.target, teammate, opponent)) f.authenticate(p);
        f.party(f.challenger, teammate);
        f.party(f.target, opponent);
        assertEquals("CHALLENGE_CREATED", f.module.action(f.challenger, "desafiar",
                List.of("Target", "2v2", "kit")).code());
        f.parties.leave(teammate.getUniqueId());
        f.authentication.invalidatePlayer(teammate.getUniqueId());
        assertEquals("AUTHENTICATION_REQUIRED", f.accept().code());
        assertTrue(f.controller.currentMatch().isEmpty());
    }

    @Test
    void stalePlayerObjectCannotUseNewConnectionsAuthentication() {
        Fixture f = new Fixture();
        Player stale = f.challenger;
        Player replacement = f.replace("Challenger", stale.getUniqueId());
        f.authenticate(replacement);
        f.authenticate(f.target);
        assertEquals("AUTHENTICATION_REQUIRED", f.module.action(stale, "desafiar",
                List.of("Target", "1v1", "kit")).code());
        assertEquals(0, f.equipmentReads.get());
    }

    @Test
    void unauthenticatedPlayerCannotCreateParty() {
        Fixture f = new Fixture();
        assertEquals("AUTHENTICATION_REQUIRED", f.module.action(f.challenger, "grupo", List.of("criar")).code());
        assertTrue(f.parties.findByMember(f.challenger.getUniqueId()).isEmpty());
    }

    private static final class Fixture {
        // Bukkit Location keeps only a weak reference; the server fixture must own its loaded world.
        private final World world;
        private final Map<UUID, Player> players = new LinkedHashMap<>();
        private final Map<String, Player> names = new LinkedHashMap<>();
        private final AuthenticationRegistry authentication = new AuthenticationRegistry();
        private final ConnectionRegistry connections = new ConnectionRegistry();
        private final ArenaPartyRegistry parties = new ArenaPartyRegistry(Duration.ofMinutes(2));
        private final AtomicInteger equipmentReads = new AtomicInteger();
        private final Server server = proxy(Server.class, (method, args) -> switch (method) {
            case "getPlayer" -> players.get(args[0]);
            case "getPlayerExact" -> names.get(args[0]);
            case "isPrimaryThread" -> true;
            default -> throw new AssertionError("Unexpected server method: " + method);
        });
        private final Player challenger = add("Challenger");
        private final Player target = add("Target");
        private final ColiseumController controller;
        private final ColiseumModule module;

        private Fixture() {
            UUID worldId = UUID.randomUUID();
            world = proxy(World.class, (method, args) -> {
                if (method.equals("getUID")) return worldId;
                throw new AssertionError("Unexpected world method: " + method);
            });
            ColiseumSettings settings = new ColiseumSettings(true, Optional.of(worldId),
                    Optional.of(new ArenaLocationPolicy("fixture", "floor", "benches", "a", "b", "exit")),
                    Optional.of(new Location(world, 5, 80, 10)), Optional.of(new Location(world, 35, 80, 10)),
                    Optional.of(new Location(world, -5, 80, 10)), ArenaFormatPolicy.defaultPolicy(),
                    Duration.ofSeconds(60), Duration.ofSeconds(30), false, Set.of());
            SessionCoordinator coordinator = new SessionCoordinator(authentication, new SessionRegistry(),
                    unused(SessionRepository.class), unused(SnapshotRepository.class), unused(PlayerStateGateway.class),
                    IsolationPolicy.strictNoProgress(), new SnapshotEnvelopeCodec(), CLOCK);
            Plugin plugin = proxy(Plugin.class, (method, args) -> {
                if (method.equals("getName")) return "Fixture";
                if (method.equals("namespace")) return "fixture";
                throw new AssertionError("Unexpected plugin method: " + method);
            });
            ProtectedRegionRegistry regions = new ProtectedRegionRegistry();
            regions.register(new ProtectedRegion("floor", GameKey.ARENA,
                    new CuboidRegion(worldId, 0, -64, 0, 40, 319, 20),
                    ProtectedRegionRole.PARTICIPANT_ONLY, true));
            regions.register(new ProtectedRegion("benches", GameKey.ARENA,
                    new CuboidRegion(worldId, -10, 80, 0, -2, 100, 20),
                    ProtectedRegionRole.SPECTATOR_PUBLIC, true));
            controller = new ColiseumController(settings, server, coordinator,
                    player -> connections.current(player.getUniqueId()).map(ConnectionRegistry.Connection::id),
                    id -> Optional.of(List.of()), new TemporaryItemTagger(plugin), regions,
                    new RegionAdmissionRegistry(), new CombatPolicyRegistry(), Optional.empty(), CLOCK);
            ModuleIdentity identity = new ModuleIdentity(connections,
                    new AdmissionRequestFactory(connections, authentication, CLOCK));
            module = new ColiseumModule(controller, identity, (player, mode) -> {
                equipmentReads.incrementAndGet();
                return ArenaEquipmentContract.fixed("fixture");
            }, () -> {}, CLOCK, parties, Duration.ofMinutes(2));
        }

        private Player add(String name) { return replace(name, UUID.randomUUID()); }

        private Player replace(String name, UUID id) {
            Player player = proxy(Player.class, (method, args) -> switch (method) {
                case "getUniqueId" -> id;
                case "getName" -> name;
                case "getServer" -> server;
                case "isOnline", "isValid" -> true;
                case "sendMessage" -> null;
                default -> throw new AssertionError("Unexpected player method: " + method);
            });
            players.put(id, player);
            names.put(name, player);
            connections.begin(player, NOW.minusSeconds(2));
            return player;
        }

        private void authenticate(Player player) {
            authentication.authenticated(new AuthenticatedSession(UUID.randomUUID(), player.getUniqueId(),
                    connections.current(player.getUniqueId()).orElseThrow().id(), NOW.minusSeconds(1), NOW.plusSeconds(60)));
        }

        private void party(Player leader, Player member) {
            parties.create(leader.getUniqueId(), NOW);
            parties.invite(leader.getUniqueId(), member.getUniqueId(), NOW);
            parties.accept(member.getUniqueId(), leader.getUniqueId(), NOW);
        }

        private com.ciaac.minecraft.minigames.module.ModuleActionResult challenge() {
            return module.action(challenger, "desafiar", List.of("Target", "1v1", "kit"));
        }

        private com.ciaac.minecraft.minigames.module.ModuleActionResult accept() {
            return module.action(target, "aceitar", List.of("Challenger"));
        }
    }

    private interface Invocation { Object invoke(String method, Object[] args); }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (self, method, args) -> invocation.invoke(method.getName(), args)));
    }

    private static <T> T unused(Class<T> type) {
        return proxy(type, (method, args) -> { throw new AssertionError("Unexpected state mutation: " + method); });
    }
}
