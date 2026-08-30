package com.ciaac.minecraft.platform.command;

import static org.junit.jupiter.api.Assertions.*;
import com.ciaac.minecraft.minigames.minecart.*;
import com.ciaac.minecraft.platform.securityevents.SecurityEvent;
import com.ciaac.minecraft.platform.securityevents.SecurityEventLogger;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class CiaacPlatformCommandTest {
    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    @Test void playerMayOmitWorldButConsoleMustSpecifyItAndResetAllIsExplicit() {
        FakeControl control = new FakeControl();
        CommandSender player = sender(true, true, "spawn", ID);
        CommandSender console = sender(false, true, null, null);
        CiaacPlatformCommand command = new CiaacPlatformCommand(control, (a, s, r) -> {});
        command.onCommand(player, null, "ciaac", new String[]{"carrinhos", "definir", "24"});
        assertEquals("spawn", control.lastWorld.name());
        assertEquals(1, control.worldSetCalls);
        command.onCommand(console, null, "ciaac", new String[]{"carrinhos", "definir", "24"});
        assertEquals(1, control.worldSetCalls);
        command.onCommand(console, null, "ciaac", new String[]{"carrinhos", "definir", "24", "spawn"});
        assertEquals(2, control.worldSetCalls);
        command.onCommand(player, null, "ciaac", new String[]{"carrinhos", "repor"});
        assertEquals(1, control.worldClearCalls);
        command.onCommand(console, null, "ciaac", new String[]{"carrinhos", "repor"});
        assertEquals(1, control.worldClearCalls);
        command.onCommand(console, null, "ciaac", new String[]{"carrinhos", "repor-tudo"});
        assertTrue(control.clearedAll);
    }

    @Test void permissionDenialAndInvalidSpeedDoNotCallControl() {
        FakeControl control = new FakeControl();
        CommandSender denied = sender(false, false, null, null);
        CiaacPlatformCommand command = new CiaacPlatformCommand(control, (a, s, r) -> {});
        command.onCommand(denied, null, "ciaac", new String[]{"carrinhos", "predefinir", "24"});
        command.onCommand(sender(false, true, null, null), null, "ciaac", new String[]{"carrinhos", "predefinir", "64.1"});
        assertEquals(1, control.defaultCalls);
        assertEquals(64.1, control.lastSpeed);
    }

    @Test void tabCompletionFiltersTestPermissions() {
        FakeControl control = new FakeControl();
        CiaacPlatformCommand command = new CiaacPlatformCommand(control, (a, s, r) -> {});
        assertEquals(List.of("estado"), command.onTabComplete(sender(false, false, null, null), null, "ciaac", new String[]{"carrinhos", "e"}));
        assertTrue(command.onTabComplete(sender(false, true, null, null), null, "ciaac", new String[]{"carrinhos", "r"}).contains("repor-tudo"));
    }

    @Test void actualTemporaryCommandAlsoEmitsConsumerCompatibleSecurityEvent() {
        FakeControl control = new FakeControl();
        CommandSender player = sender(true, true, "spawn", ID);
        Logger logger = Logger.getLogger("minecart-contract-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.ALL);
        List<String> records = new ArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        };
        logger.addHandler(capture);
        try {
            SecurityEventLogger security = new SecurityEventLogger(logger, "ciaac-platform");
            MinecartSpeedAuditLogger audit = new MinecartSpeedAuditLogger(
                    logger, security::emit, ignored -> Optional.of(new SecurityEvent.Actor(
                            SecurityEvent.IdentityKind.AUTHENTICATED_UUID, ID.toString(), "tester", true)));
            CiaacPlatformCommand command = new CiaacPlatformCommand(control, audit);

            command.onCommand(player, null, "ciaac", new String[]{"carrinhos", "definir", "24"});

            String marker = records.stream().filter(value -> value.startsWith(SecurityEventLogger.PREFIX))
                    .findFirst().orElseThrow();
            String json = new String(Base64.getUrlDecoder().decode(
                    marker.substring(SecurityEventLogger.PREFIX.length())), StandardCharsets.UTF_8);
            assertTrue(json.contains("\"type\":\"MINECART_TEST_OVERRIDE\""));
            assertTrue(json.contains("\"identityProvenance\":\"POST_AUTHENTICATED\""));
            assertTrue(json.contains("\"action_code\":\"MINECART_SPEED_TEST\""));
        } finally {
            logger.removeHandler(capture);
            capture.close();
        }
    }

    private static CommandSender sender(boolean player, boolean testPermission, String worldName, UUID worldId) {
        List<String> messages = new ArrayList<>();
        Class<?>[] types = player ? new Class[]{Player.class} : new Class[]{CommandSender.class};
        World world = worldName == null ? null : (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class[]{World.class}, (p,m,a) -> m.getName().equals("getName") ? worldName : m.getName().equals("getUID") ? worldId : value(m.getReturnType()));
        return (CommandSender) Proxy.newProxyInstance(CiaacPlatformCommandTest.class.getClassLoader(), types, (p,m,a) -> {
            if (m.getName().equals("hasPermission")) return "ciaac.minecarts.test".equals(a[0]) ? testPermission : true;
            if (m.getName().equals("sendMessage")) { messages.add((String)a[0]); return null; }
            if (m.getName().equals("getName")) return player ? "tester" : "console";
            if (m.getName().equals("getWorld")) return world;
            if (m.getName().equals("getUniqueId")) return ID;
            return value(m.getReturnType());
        });
    }
    private static Object value(Class<?> t) { return t == boolean.class ? false : t.isPrimitive() ? 0 : null; }

    private static final class FakeControl implements MinecartSpeedControl {
        WorldIdentity lastWorld; boolean clearedAll; int defaultCalls; int worldSetCalls; int worldClearCalls;
        double lastSpeed;
        public String statusPtPt(){return "estado";} public ReloadResult reload(){return ReloadResult.success("ok");}
        public Optional<WorldIdentity> loadedWorldExact(String n){return Optional.of(new WorldIdentity(n, ID));}
        public List<String> loadedWorldNames(){return List.of("spawn", "survival");}
        public ChangeResult setWorldOverride(WorldIdentity w,double s){lastWorld=w; worldSetCalls++; return changed();}
        public ChangeResult setDefaultOverride(double s){defaultCalls++; lastSpeed=s; return s > 64 ? new ChangeResult(false,false,UUID.randomUUID(),"inválida",List.of()) : changed();}
        public ChangeResult clearWorldOverride(WorldIdentity w){lastWorld=w; worldClearCalls++; return changed();}
        public ChangeResult clearAllOverrides(){clearedAll=true; return changed();}
        private ChangeResult changed(){return new ChangeResult(true,true,UUID.randomUUID(),"ok",List.of());}
    }
}
