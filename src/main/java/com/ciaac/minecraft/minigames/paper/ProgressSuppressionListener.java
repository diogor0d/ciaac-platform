package com.ciaac.minecraft.minigames.paper;

import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import com.destroystokyo.paper.event.player.PlayerAdvancementCriterionGrantEvent;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerStatisticIncrementEvent;

/** Prevents minigame actions from incrementing survival advancements/statistics. */
public final class ProgressSuppressionListener implements Listener {
    private final SessionRegistry sessions;

    public ProgressSuppressionListener(SessionRegistry sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onStatistic(PlayerStatisticIncrementEvent event) {
        if (isolated(event.getPlayer()).isPresent()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onAdvancement(PlayerAdvancementCriterionGrantEvent event) {
        if (isolated(event.getPlayer()).isPresent()) event.setCancelled(true);
    }

    private Optional<PlayerSession> isolated(Player player) {
        return sessions.findByPlayer(player.getUniqueId())
                .filter(session -> session.phase().isolationActive());
    }
}
