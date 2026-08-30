package com.ciaac.minecraft.minigames.minecart;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.lang.reflect.Proxy;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

class MinecartSpeedAuditLoggerTest {
    @Test void emitsBoundedEnvelopeWithRequiredOperatorAndChangeFields() {
        Logger logger = Logger.getLogger("minecart-audit-test");
        var records = new java.util.ArrayList<String>();
        Handler handler = new Handler() { public void publish(LogRecord r) { records.add(r.getMessage()); } public void flush() {} public void close() {} };
        logger.addHandler(handler); logger.setUseParentHandlers(false); logger.setLevel(Level.ALL);
        CommandSender sender = (CommandSender) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{CommandSender.class},
                (p, m, a) -> m.getName().equals("getName") ? "console" : defaultValue(m.getReturnType()));
        UUID operation = UUID.fromString("22222222-2222-2222-2222-222222222222");
        var world = new MinecartSpeedControl.WorldIdentity("spawn", UUID.fromString("33333333-3333-3333-3333-333333333333"));
        var result = new MinecartSpeedControl.ChangeResult(true, true, operation, "feito", List.of(
                new MinecartSpeedControl.WorldChange(world, Optional.of(16.0), Optional.of(24.0))));
        new MinecartSpeedAuditLogger(logger).emit(MinecartSpeedAuditLogger.Action.SET_WORLD, sender, result);
        assertEquals(1, records.size());
        assertTrue(records.get(0).startsWith(MinecartSpeedAuditLogger.PREFIX));
        assertTrue(records.get(0).length() < 2048);
        String encoded = records.get(0).substring(MinecartSpeedAuditLogger.PREFIX.length());
        String json = new String(java.util.Base64.getUrlDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(json.contains(operation.toString()) && json.contains("spawn") && json.contains(world.worldId().toString()));
        assertTrue(json.contains("16.000") && json.contains("24.000") && json.contains("console"));
    }
    private static Object defaultValue(Class<?> type) { return type == boolean.class ? false : type.isPrimitive() ? 0 : null; }
}
