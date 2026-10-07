package com.ciaac.minecraft.minigames.retention;

import static org.junit.jupiter.api.Assertions.*;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.runtime.*;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PassportPaperRuntimeTest {
    @TempDir Path directory;

    @Test void creditsOnlyAfterTenAuthenticatedMinutesAndOncePerDay() throws Exception {
        try (Harness h = new Harness()) {
            h.clock.now = h.start.plusSeconds(599); h.sample.run();
            assertEquals(0, h.points());
            h.clock.now = h.start.plusSeconds(600); h.sample.run(); h.sample.run();
            assertEquals(1, h.points());
        }
    }

    @Test void continuousConnectionQualifiesAgainAcrossLisbonMidnight() throws Exception {
        try (Harness h = new Harness()) {
            h.clock.now = h.start.plusSeconds(600); h.sample.run();
            h.clock.now = Instant.parse("2026-09-15T23:01:00Z"); h.sample.run(); h.sample.run();
            assertEquals(2, h.points());
            assertEquals(2, h.service.passport(h.id, h.clock.now).currentJoinStreak());
        }
    }

    @ParameterizedTest
    @EnumSource(value = SessionPhase.class, names = "CLOSED", mode = EnumSource.Mode.EXCLUDE)
    void everyPendingSessionExcludesJoinActivityAndClaims(SessionPhase phase) throws Exception {
        try (Harness h = new Harness()) {
            h.sessions.register(h.session(phase));
            h.clock.now = h.start.plusSeconds(600);
            for (int minute = 0; minute < 15; minute++) {
                h.move(); h.sample.run(); h.clock.now = h.clock.now.plusSeconds(60);
            }
            assertEquals(0, h.points());
            h.messages.clear();
            h.runtime.onCommand(h.player, null, "passaporte", new String[] {"reclamar", "unknown"});
            assertTrue(h.messages.stream().anyMatch(message -> message.contains("Esta sessão não pode")));
        }
    }

    @Test void activityIsRecheckedAfterEnteringQuarantine() throws Exception {
        try (Harness h = new Harness()) {
            h.move(); h.sessions.register(h.session(SessionPhase.QUARANTINED));
            h.clock.now = h.start.plusSeconds(60); h.sample.run();
            assertEquals(0, h.repository.allLedgers().stream().mapToInt(ledger ->
                    ledger.dayView(h.service.calendar().localDate(h.clock.now)).sampledMinutes()).sum());
        }
    }

    @Test void reconnectCannotReuseActivityFromPreviousConnection() throws Exception {
        try (Harness h = new Harness()) {
            h.move(); h.authenticate(); h.clock.now = h.start.plusSeconds(60); h.sample.run();
            assertEquals(0, h.repository.allLedgers().stream().mapToInt(ledger ->
                    ledger.dayView(h.service.calendar().localDate(h.clock.now)).sampledMinutes()).sum());
        }
    }

    @Test void unauthenticatedAndExpiredConnectionsNeverQualify() throws Exception {
        try (Harness h = new Harness()) {
            h.auth.invalidatePlayer(h.id); h.clock.now = h.start.plusSeconds(600); h.sample.run();
            assertEquals(0, h.points());
            h.authenticate(); h.clock.now = h.clock.now.plus(Duration.ofDays(1)); h.move(); h.sample.run();
            assertEquals(0, h.points());
        }
    }

    @Test void adminCommandRequiresExactViewPermissionAndDoesNotWriteRetentionState() throws Exception {
        try (Harness h = new Harness()) {
            h.runtime.onCommand(h.player, null, "passaporte", new String[] {"admin"});

            assertTrue(h.messages.stream().anyMatch(message -> message.contains("Não tens permissão")));
            assertTrue(h.repository.allLedgers().isEmpty());

            h.messages.clear();
            h.adminViewPermission = true;
            h.runtime.onCommand(h.player, null, "passaporte", new String[] {"admin"});
            assertTrue(h.messages.stream().anyMatch(message -> message.contains("Passaporte: ativo")));
            assertTrue(h.repository.allLedgers().isEmpty());
        }
    }

    @Test void tabCompletionHidesAdminUnlessExactViewPermissionIsGranted() throws Exception {
        try (Harness h = new Harness()) {
            assertEquals(List.of("recompensas", "classificacao", "reclamar", "personalizar"),
                    h.runtime.onTabComplete(h.player, null, "passaporte", new String[] {""}));

            h.adminViewPermission = true;
            assertEquals(List.of("recompensas", "classificacao", "reclamar", "personalizar", "admin"),
                    h.runtime.onTabComplete(h.player, null, "passaporte", new String[] {""}));
        }
    }

    @Test void opAndCreativePlayersCannotQualify() throws Exception {
        try (Harness h = new Harness()) {
            h.clock.now = h.start.plusSeconds(600); h.op = true; h.sample.run();
            h.op = false; h.mode = GameMode.CREATIVE; h.move(); h.sample.run();
            assertEquals(0, h.points());
            h.mode = GameMode.SURVIVAL; h.sample.run(); assertEquals(1, h.points());
        }
    }

    private final class Harness implements AutoCloseable {
        final Instant start = Instant.parse("2026-09-15T22:40:00Z");
        final UUID id = UUID.randomUUID();
        final MutableClock clock = new MutableClock(start);
        final AuthenticationRegistry auth = new AuthenticationRegistry();
        final ConnectionRegistry connections = new ConnectionRegistry();
        final SessionRegistry sessions = new SessionRegistry();
        final InMemoryRetentionRepository repository = new InMemoryRetentionRepository();
        final PassportService service = new PassportService(new RetentionConfiguration(true,
                LisbonSeasonCalendar.LISBON, LisbonSeasonCalendar.ANCHOR, 3, 14,
                Duration.ofMinutes(10), 15, 3, 12, false, false, false, List.of()), repository, List.of(), List.of());
        final List<String> messages = new ArrayList<>();
        final Player player;
        final PassportPaperRuntime runtime;
        Runnable sample;
        boolean op;
        boolean adminViewPermission;
        GameMode mode = GameMode.SURVIVAL;

        Harness() throws Exception {
            player = proxy(Player.class, (method, args) -> switch (method) {
                case "getUniqueId" -> id; case "getGameMode" -> mode; case "isOp" -> op;
                case "isOnline", "isValid" -> true;
                case "hasPermission" -> "ciaac.retention.claim".equals(args[0])
                        || adminViewPermission && "ciaac.retention.admin.view".equals(args[0]);
                case "sendMessage" -> { if (args[0] instanceof String text) messages.add(text); yield null; }
                default -> null;
            });
            BukkitTask task = proxy(BukkitTask.class, (method, args) -> null);
            BukkitScheduler scheduler = proxy(BukkitScheduler.class, (method, args) -> {
                if (method.equals("runTaskTimer")) { sample = (Runnable) args[1]; return task; }
                return null;
            });
            PluginManager manager = proxy(PluginManager.class, (method, args) -> null);
            PluginCommand[] command = new PluginCommand[1];
            Server server = proxy(Server.class, (method, args) -> switch (method) {
                case "getScheduler" -> scheduler; case "getPluginManager" -> manager;
                case "getPluginCommand" -> command[0]; case "getOnlinePlayers" -> List.of(player);
                case "getPlayer" -> player; default -> null;
            });
            Files.writeString(directory.resolve("retention.yml"), "presentation: {}\n");
            Plugin plugin = proxy(Plugin.class, (method, args) -> switch (method) {
                case "getServer" -> server; case "getDataFolder" -> directory.toFile();
                case "getLogger" -> Logger.getLogger("PassportTest"); default -> null;
            });
            var constructor = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
            constructor.setAccessible(true); command[0] = constructor.newInstance("passaporte", plugin);
            authenticate(); runtime = new PassportPaperRuntime(plugin, service, auth, connections, sessions, clock);
            runtime.onAuthenticated(player);
        }
        void authenticate() {
            var connection = connections.begin(player, clock.now);
            auth.authenticated(new AuthenticatedSession(UUID.randomUUID(), id, connection.id(),
                    clock.now, clock.now.plus(Duration.ofDays(1))));
        }
        void move() { runtime.onMove(new PlayerMoveEvent(player, new Location(null, 0, 64, 0), new Location(null, 1, 64, 0))); }
        int points() { return service.passport(id, clock.now).points(); }
        PlayerSession session(SessionPhase target) {
            PlayerSession session = new PlayerSession(UUID.randomUUID(), UUID.randomUUID(), id, GameKey.ARENA, start);
            if (target == SessionPhase.RECOVERING || target == SessionPhase.QUARANTINED) {
                session.transition(UUID.randomUUID(), SessionPhase.REQUESTED, SessionPhase.RECOVERING, start, "TEST");
                if (target == SessionPhase.QUARANTINED) session.transition(UUID.randomUUID(), SessionPhase.RECOVERING, target, start, "TEST");
            } else {
                for (SessionPhase phase : List.of(SessionPhase.SNAPSHOTTING, SessionPhase.SNAPSHOT_COMMITTED,
                        SessionPhase.PREPARING, SessionPhase.ACTIVE, SessionPhase.FINISHING, SessionPhase.RESTORING)) {
                    if (session.phase() == target) break;
                    session.transition(UUID.randomUUID(), session.phase(), phase, start, "TEST");
                }
            }
            return session;
        }
        public void close() { runtime.close(); }
    }

    private interface Call { Object invoke(String name, Object[] args); }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (method.getName().equals("equals")) return self == args[0];
            if (method.getName().equals("hashCode")) return System.identityHashCode(self);
            Object result = call.invoke(method.getName(), args);
            if (result != null || !method.getReturnType().isPrimitive() || method.getReturnType() == void.class) return result;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            if (method.getReturnType() == long.class) return 0L;
            throw new AssertionError("Unsupported proxy method " + method);
        }));
    }
    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
