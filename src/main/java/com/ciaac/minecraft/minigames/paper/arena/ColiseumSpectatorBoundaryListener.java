package com.ciaac.minecraft.minigames.paper.arena;

import com.ciaac.minecraft.minigames.arena.ArenaMatch;
import com.ciaac.minecraft.minigames.arena.ArenaPhase;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.Cancellable;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;

/** Keeps active participants on the combat floor while leaving spectator benches public. */
public final class ColiseumSpectatorBoundaryListener implements Listener {
    private final ColiseumSettings settings;
    private final ProtectedRegionRegistry regions;
    private final ColiseumController controller;
    private final Plugin plugin;
    private final Set<UUID> pendingForfeits = ConcurrentHashMap.newKeySet();

    public ColiseumSpectatorBoundaryListener(
            ColiseumSettings settings,
            ProtectedRegionRegistry regions,
            ColiseumController controller,
            Plugin plugin) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.regions = Objects.requireNonNull(regions, "regions");
        this.controller = Objects.requireNonNull(controller, "controller");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onMove(PlayerMoveEvent event) {
        if (event.isCancelled()) return;
        Location destination = event.getTo();
        if (destination == null || sameBlock(event.getFrom(), destination)) return;
        enforceBoundary(event, event.getPlayer(), destination);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        Location destination = event.getTo();
        if (destination == null) return;
        enforceBoundary(event, event.getPlayer(), destination);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onPortal(PlayerPortalEvent event) {
        if (event.isCancelled()) return;
        Location destination = event.getTo();
        if (destination == null) return;
        enforceBoundary(event, event.getPlayer(), destination);
    }

    private void enforceBoundary(Cancellable event, org.bukkit.entity.Player player, Location destination) {
        Optional<ArenaMatch> current = controller.currentMatch()
                .filter(match -> match.phase() == ArenaPhase.ACTIVE)
                .filter(match -> match.participants().contains(player.getUniqueId()));
        if (current.isEmpty()) return;
        ArenaMatch match = current.orElseThrow();
        boolean fighter = match.activePlayers().contains(player.getUniqueId());
        String permittedRegionId = settings.locations().filter(value -> settings.enabled())
                .map(value -> fighter ? value.combatFloorRegion() : value.spectatorRegion())
                .orElse("");
        Optional<ProtectedRegion> destinationRegion = regions.at(destination);
        boolean permitted = destinationRegion.map(ProtectedRegion::id)
                .filter(permittedRegionId::equals).isPresent();
        if (!permitted) {
            event.setCancelled(true);
            if (fighter) {
                scheduleForfeit(player, match.id());
            } else {
                player.sendMessage("§cEnquanto assistes, permanece nas bancadas do Coliseu.");
            }
        }
    }

    private void scheduleForfeit(org.bukkit.entity.Player player, UUID matchId) {
        UUID playerId = player.getUniqueId();
        if (!pendingForfeits.add(playerId)) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                boolean stillFighting = controller.currentMatch()
                        .filter(match -> match.id().equals(matchId) && match.phase() == ArenaPhase.ACTIVE)
                        .map(ArenaMatch::activePlayers)
                        .filter(players -> players.contains(playerId))
                        .isPresent();
                if (stillFighting && player.isOnline()) {
                    controller.forfeit(player, "ESCAPE_ATTEMPT").sendTo(player);
                }
            } finally {
                pendingForfeits.remove(playerId);
            }
        });
    }

    private static boolean sameBlock(Location left, Location right) {
        return left.getWorld() == right.getWorld()
                && left.getBlockX() == right.getBlockX()
                && left.getBlockY() == right.getBlockY()
                && left.getBlockZ() == right.getBlockZ();
    }
}
