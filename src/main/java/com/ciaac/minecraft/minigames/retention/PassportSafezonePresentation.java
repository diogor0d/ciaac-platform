package com.ciaac.minecraft.minigames.retention;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** CIAAC-owned safezone displays and opt-in, bounded particle auras. */
final class PassportSafezonePresentation implements AutoCloseable {
    private final Plugin plugin;
    private final PassportService passport;
    private final SafezonePresentationConfiguration config;
    private final Clock clock;
    private final NamespacedKey ownerKey;
    private final World world;
    private final List<Entity> owned = new ArrayList<>();
    private final BukkitTask task;
    private TextDisplay current;
    private TextDisplay hall;
    private long ticks;

    PassportSafezonePresentation(Plugin plugin, PassportService passport,
                                 SafezonePresentationConfiguration config, Clock clock) {
        this.plugin = plugin; this.passport = passport; this.config = config; this.clock = clock;
        ownerKey = new NamespacedKey(plugin, "passport-safezone-display");
        World candidate = plugin.getServer().getWorld(config.worldName());
        if (candidate == null || !candidate.getUID().equals(config.worldId())) throw new IllegalStateException(
                "O mundo seguro carregado não corresponde ao nome e UUID configurados.");
        world = candidate;
        removePreviouslyOwnedLoadedEntities();
        if (passport.configuration().safezoneDisplaysEnabled()) createDisplays();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void createDisplays() {
        current = display(config.currentRanking()); hall = display(config.hallOfFame());
        interaction(config.currentRanking()); interaction(config.hallOfFame());
        refresh();
    }

    private TextDisplay display(SafezonePresentationConfiguration.Point point) {
        Location location = location(point);
        requireLoaded(location);
        TextDisplay entity = world.spawn(location, TextDisplay.class, value -> {
            tag(value); value.setPersistent(true); value.setSeeThrough(true); value.setShadowed(true);
        });
        owned.add(entity); return entity;
    }

    private void interaction(SafezonePresentationConfiguration.Point point) {
        Location location = location(point); requireLoaded(location);
        Interaction entity = world.spawn(location, Interaction.class, value -> {
            tag(value); value.setPersistent(true); value.setInteractionWidth(3F); value.setInteractionHeight(3F);
        });
        owned.add(entity);
    }

    private void tick() {
        ticks++;
        if (ticks % 60 == 0 && current != null) refresh();
        if (!passport.configuration().particlesEnabled()) return;
        Location center = location(config.center()); double maximum = config.radius() * config.radius();
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(center) > maximum || !player.hasPermission("ciaac.retention.particles")) continue;
            if (!passport.passport(player.getUniqueId(), clock.instant()).selections().containsKey("particulas")) continue;
            world.spawnParticle(Particle.END_ROD, player.getLocation().add(0, 1, 0), 2, .25, .4, .25, 0);
        }
    }

    private void refresh() {
        current.text(Component.text(ranking("Passaporte CIAAC — Top 10",
                passport.leaderboard(PassportService.LeaderboardMetric.PASSPORT_POINTS, clock.instant(), 10), false)));
        var active = passport.calendar().seasonAt(clock.instant());
        var previous = passport.calendar().seasonContaining(active.startsOn().minusDays(1));
        boolean anonymized = LocalDate.now(passport.calendar().zone()).isAfter(previous.graceEndsOnInclusive().plusMonths(12));
        hall.text(Component.text(ranking("Hall da Fama — " + previous.id(),
                passport.leaderboard(PassportService.LeaderboardMetric.PASSPORT_POINTS, previous, 10), anonymized)));
    }

    private String ranking(String title, List<PassportService.LeaderboardEntry> rows, boolean anonymized) {
        StringBuilder text = new StringBuilder(title);
        for (var row : rows) {
            String name = anonymized ? "Jogador anónimo" : currentName(row.playerId());
            text.append('\n').append(row.rank()).append(". ").append(name).append(" — ").append(row.value());
        }
        if (rows.isEmpty()) text.append("\nAinda sem resultados.");
        return text.toString();
    }

    private String currentName(UUID id) {
        String name = plugin.getServer().getOfflinePlayer(id).getName();
        return name == null ? "Jogador anónimo" : name;
    }
    private void removePreviouslyOwnedLoadedEntities() {
        for (Entity entity : world.getEntities()) if (entity.getPersistentDataContainer().has(ownerKey)) entity.remove();
    }
    private void tag(Entity entity) { entity.getPersistentDataContainer().set(ownerKey, PersistentDataType.BYTE, (byte) 1); }
    private Location location(SafezonePresentationConfiguration.Point point) { return new Location(world, point.x(), point.y(), point.z()); }
    private static void requireLoaded(Location location) {
        if (!location.isChunkLoaded()) throw new IllegalStateException("O chunk da apresentação não está carregado; não será forçado.");
    }
    @Override public void close() { task.cancel(); owned.forEach(Entity::remove); owned.clear(); }
}
