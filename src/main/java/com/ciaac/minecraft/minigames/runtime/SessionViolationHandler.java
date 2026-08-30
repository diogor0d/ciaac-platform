package com.ciaac.minecraft.minigames.runtime;

import org.bukkit.entity.Player;

@FunctionalInterface
public interface SessionViolationHandler {
    void onViolation(Player player, PlayerSession session, SessionViolation violation);
}
