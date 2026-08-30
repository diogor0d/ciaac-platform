package com.ciaac.minecraft.minigames.paper.display;

import com.ciaac.minecraft.minigames.display.DisplayDiagnostic;
import com.ciaac.minecraft.minigames.display.NativeDisplayConfig;
import com.ciaac.minecraft.minigames.display.NativeDisplayEntry;
import com.ciaac.minecraft.minigames.display.NativeDisplayText;
import com.ciaac.minecraft.minigames.module.ModuleActionResult;
import com.ciaac.minecraft.minigames.module.MinigameModuleRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/** Native TextDisplay + Interaction adapter. It owns only entities tagged by this plugin. */
public final class NativeDisplayController implements Listener {
    private static final String TEXT_ROLE = "text";
    private static final String INTERACTION_ROLE = "interaction";
    private final Plugin plugin;
    private final Server server;
    private final NativeDisplayConfig config;
    private final MinigameModuleRegistry modules;
    private final Clock clock;
    private final NamespacedKey ownerKey;
    private final NamespacedKey entryKey;
    private final NamespacedKey roleKey;
    private final Map<String, DisplayPair> pairs = new HashMap<>();
    private final Map<String, DisplayDiagnostic> diagnostics = new HashMap<>();
    private final Map<String, Instant> lastRefreshByEntry = new HashMap<>();
    private Instant lastRefresh;

    public NativeDisplayController(Plugin plugin, Server server, NativeDisplayConfig config,
                                   MinigameModuleRegistry modules, Clock clock,
                                   List<DisplayDiagnostic> initialDiagnostics) {
        this.plugin = Objects.requireNonNull(plugin, "plugin"); this.server = Objects.requireNonNull(server, "server"); this.config = Objects.requireNonNull(config, "config"); this.modules = Objects.requireNonNull(modules, "modules"); this.clock = Objects.requireNonNull(clock, "clock");
        ownerKey = new NamespacedKey(plugin, "native-display-owner"); entryKey = new NamespacedKey(plugin, "native-display-entry"); roleKey = new NamespacedKey(plugin, "native-display-role");
        for (DisplayDiagnostic diagnostic : Objects.requireNonNull(initialDiagnostics, "initialDiagnostics")) diagnostics.put(diagnostic.entryId(), diagnostic);
    }

    public NativeDisplayController(Plugin plugin, Server server, NativeDisplayConfig config, MinigameModuleRegistry modules, Clock clock) {
        this(plugin, server, config, modules, clock, List.of());
    }

    public synchronized List<DisplayDiagnostic> diagnostics() { return List.copyOf(diagnostics.values()); }

    public synchronized void start() { if (!config.enabled()) return; refresh(true); }

    public synchronized void tick() {
        if (!config.enabled()) return;
        refresh(false);
    }

    public synchronized void refresh() { if (config.enabled()) refresh(false); }

    private void refresh(boolean initial) {
        Instant now = clock.instant();
        lastRefresh = now;
        for (NativeDisplayEntry entry : config.entries()) {
            Instant previous = lastRefreshByEntry.get(entry.id());
            if (!initial && previous != null && now.isBefore(previous.plus(entry.refresh()))) continue;
            reconcile(entry);
            lastRefreshByEntry.put(entry.id(), now);
        }
    }

    private void reconcile(NativeDisplayEntry entry) {
        World world = server.getWorld(entry.world().uuid());
        if (world == null || !world.getName().equals(entry.world().name()) || !world.getUID().equals(entry.world().uuid())) {
            close(entry, "WORLD_UNAVAILABLE", "O mundo configurado para este display não está disponível."); return;
        }
        Location location = new Location(world, entry.location().x(), entry.location().y(), entry.location().z(), entry.location().yaw(), entry.location().pitch());
        if (!world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            close(entry, "CHUNK_UNLOADED", "O chunk deste display ainda não está carregado."); return;
        }
        try {
            var status = modules.get(entry.game()).status();
            String text = NativeDisplayText.render(status, entry.joinCommand());
            TextDisplay display = findText(world, location, entry.id()).orElseGet(() -> spawnText(world, location, entry));
            Interaction interaction = findInteraction(world, location, entry.id()).orElseGet(() -> spawnInteraction(world, location, entry));
            update(display, interaction, location, entry, text);
            pairs.put(entry.id(), new DisplayPair(display, interaction));
            diagnostics.remove(entry.id());
        } catch (RuntimeException failure) {
            close(entry, "DISPLAY_RECONCILE_FAILED", "O display foi fechado por uma falha de validação.");
        }
    }

    private TextDisplay spawnText(World world, Location location, NativeDisplayEntry entry) { return world.spawn(location, TextDisplay.class, display -> tag(display, entry, TEXT_ROLE)); }
    private Interaction spawnInteraction(World world, Location location, NativeDisplayEntry entry) { return world.spawn(location, Interaction.class, interaction -> tag(interaction, entry, INTERACTION_ROLE)); }
    private void update(TextDisplay display, Interaction interaction, Location location, NativeDisplayEntry entry, String text) {
        if (!display.teleport(location) || !interaction.teleport(location)) throw new IllegalStateException("display teleport rejected");
        display.text(Component.text(text)); display.setDisplayWidth(entry.dimensions().width()); display.setDisplayHeight(entry.dimensions().height()); display.setLineWidth(400); display.setBillboard(org.bukkit.entity.Display.Billboard.CENTER); display.setPersistent(true); display.setInvulnerable(true);
        interaction.setInteractionWidth(entry.dimensions().width()); interaction.setInteractionHeight(entry.dimensions().height()); interaction.setPersistent(true);
    }
    private void tag(Entity entity, NativeDisplayEntry entry, String role) { entity.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, plugin.getName()); entity.getPersistentDataContainer().set(entryKey, PersistentDataType.STRING, entry.id()); entity.getPersistentDataContainer().set(roleKey, PersistentDataType.STRING, role); entity.setPersistent(true); }
    private Optional<TextDisplay> findText(World world, Location location, String id) { List<TextDisplay> found = world.getEntities().stream().filter(entity -> entity instanceof TextDisplay && owned(entity, id, TEXT_ROLE)).map(entity -> (TextDisplay) entity).toList(); found.stream().skip(1).forEach(Entity::remove); return found.stream().findFirst(); }
    private Optional<Interaction> findInteraction(World world, Location location, String id) { List<Interaction> found = world.getEntities().stream().filter(entity -> entity instanceof Interaction && owned(entity, id, INTERACTION_ROLE)).map(entity -> (Interaction) entity).toList(); found.stream().skip(1).forEach(Entity::remove); return found.stream().findFirst(); }
    private boolean owned(Entity entity, String id, String role) { return plugin.getName().equals(entity.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING)) && id.equals(entity.getPersistentDataContainer().get(entryKey, PersistentDataType.STRING)) && role.equals(entity.getPersistentDataContainer().get(roleKey, PersistentDataType.STRING)); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public synchronized void onInteract(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        String id = clicked.getPersistentDataContainer().get(entryKey, PersistentDataType.STRING);
        if (!plugin.getName().equals(clicked.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING)) || id == null || !INTERACTION_ROLE.equals(clicked.getPersistentDataContainer().get(roleKey, PersistentDataType.STRING))) return;
        event.setCancelled(true);
        NativeDisplayEntry entry = config.entries().stream().filter(value -> value.id().equals(id)).findFirst().orElse(null);
        if (entry == null || event.getPlayer().getWorld() == null || !event.getPlayer().getWorld().getUID().equals(entry.world().uuid()) || !event.getPlayer().getWorld().getName().equals(entry.world().name())) { event.getPlayer().sendMessage("§cEste display já não está configurado."); return; }
        Location configured = new Location(event.getPlayer().getWorld(), entry.location().x(), entry.location().y(), entry.location().z());
        if (clicked.getLocation().distanceSquared(configured) > 16) { event.getPlayer().sendMessage("§cEste display já não está configurado."); return; }
        try {
            ModuleActionResult result = modules.get(entry.game()).join(event.getPlayer(), List.of());
            event.getPlayer().sendMessage(result.messagePtPt());
        } catch (RuntimeException failure) { event.getPlayer().sendMessage("§cNão foi possível iniciar a entrada neste minijogo."); }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public synchronized void onDisplayDamage(EntityDamageEvent event) {
        if (plugin.getName().equals(event.getEntity().getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING))) event.setCancelled(true);
    }

    public synchronized void shutdown() {
        for (World world : server.getWorlds()) {
            // getEntities() enumerates loaded chunks only; it never force-loads a world/chunk.
            for (Entity entity : new ArrayList<>(world.getEntities())) {
                if (plugin.getName().equals(entity.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING))) entity.remove();
            }
        }
        pairs.clear(); lastRefresh = null; lastRefreshByEntry.clear();
    }

    private void close(NativeDisplayEntry entry, String code, String message) { diagnostics.put(entry.id(), new DisplayDiagnostic(entry.id(), code, message)); DisplayPair pair = pairs.remove(entry.id()); if (pair != null) { if (pair.text().isValid()) pair.text().remove(); if (pair.interaction().isValid()) pair.interaction().remove(); } }
    private record DisplayPair(TextDisplay text, Interaction interaction) { }
}
