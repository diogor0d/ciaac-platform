package com.ciaac.minecraft.minigames.minecart;

import org.bukkit.command.CommandSender;

/** Destino local das provas de auditoria dos testes temporários. */
@FunctionalInterface
public interface MinecartSpeedAuditSink {
    void emit(
            MinecartSpeedAuditLogger.Action action,
            CommandSender sender,
            MinecartSpeedControl.ChangeResult result);
}
