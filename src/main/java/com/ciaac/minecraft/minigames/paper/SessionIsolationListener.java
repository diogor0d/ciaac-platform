package com.ciaac.minecraft.minigames.paper;

import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.runtime.CombatPolicy;
import com.ciaac.minecraft.minigames.runtime.CombatPolicyRegistry;
import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import com.ciaac.minecraft.minigames.runtime.SessionPhase;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import com.ciaac.minecraft.minigames.runtime.SessionViolation;
import com.ciaac.minecraft.minigames.runtime.SessionViolationHandler;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.plugin.Plugin;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;

/** Blocks the common item, command, damage, and external-state laundering paths. */
public final class SessionIsolationListener implements Listener {
    private final Plugin owner;
    private final SessionRegistry sessions;
    private final AuthenticationRegistry authentication;
    private final CombatPolicyRegistry combatPolicies;
    private final TemporaryItemTagger temporaryItems;
    private final SessionViolationHandler violations;
    private final Function<PlayerSession, GameMode> expectedTemporaryMode;

    public SessionIsolationListener(
            Plugin owner,
            SessionRegistry sessions,
            AuthenticationRegistry authentication,
            CombatPolicyRegistry combatPolicies,
            TemporaryItemTagger temporaryItems,
            SessionViolationHandler violations) {
        this(owner, sessions, authentication, combatPolicies, temporaryItems, violations,
                ignored -> GameMode.ADVENTURE);
    }

    public SessionIsolationListener(
            Plugin owner,
            SessionRegistry sessions,
            AuthenticationRegistry authentication,
            CombatPolicyRegistry combatPolicies,
            TemporaryItemTagger temporaryItems,
            SessionViolationHandler violations,
            Function<PlayerSession, GameMode> expectedTemporaryMode) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.combatPolicies = Objects.requireNonNull(combatPolicies, "combatPolicies");
        this.temporaryItems = Objects.requireNonNull(temporaryItems, "temporaryItems");
        this.violations = Objects.requireNonNull(violations, "violations");
        this.expectedTemporaryMode = Objects.requireNonNull(expectedTemporaryMode, "expectedTemporaryMode");
    }

    /** Protects the fixed Elytra and phase-owned Build Battle modes from delayed world enforcement. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Optional<PlayerSession> session = sessions.findByPlayer(event.getPlayer().getUniqueId())
                .filter(value -> value.phase() == SessionPhase.PREPARING || value.phase() == SessionPhase.ACTIVE)
                .filter(value -> value.game() == GameKey.ELYTRA_RINGS || value.game() == GameKey.BUILD_BATTLE);
        if (session.isEmpty()) return;
        try {
            GameMode expected = Objects.requireNonNull(expectedTemporaryMode.apply(session.orElseThrow()),
                    "Expected temporary game mode policy returned null");
            if (event.getNewGameMode() != expected) event.setCancelled(true);
        } catch (RuntimeException unavailable) {
            event.setCancelled(true);
        }
    }

    /** These immutable games never create world entities. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player) {
            isolated(player).filter(session -> session.game() == GameKey.KNOCKBACK_SUMO
                    || session.game() == GameKey.HOT_POTATO
                    || session.game() == GameKey.CHECKPOINT_PARKOUR || session.game() == GameKey.BUILD_BATTLE
                    || session.game() == GameKey.ANVIL_DODGE || session.game() == GameKey.COLOR_FLOOR)
                    .ifPresent(session -> event.setCancelled(true));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        Optional<PlayerSession> session = isolated(event.getPlayer());
        if (session.isPresent() || temporaryItems.isTemporary(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            if (session.isPresent()) {
                event.getPlayer().sendMessage("§cNão podes largar itens durante um minijogo.");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            if (temporaryItems.isTemporary(event.getItem().getItemStack())) {
                event.setCancelled(true);
            }
            return;
        }
        if (isolated(player).isPresent() || temporaryItems.isTemporary(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || isolated(player).isEmpty()) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof Player)
                && !(event.getInventory().getHolder() instanceof MinigameInventoryHolder)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || isolated(player).isEmpty()) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof Player)
                && !(event.getView().getTopInventory().getHolder() instanceof MinigameInventoryHolder)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || isolated(player).isEmpty()) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof Player)
                && !(event.getView().getTopInventory().getHolder() instanceof MinigameInventoryHolder)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCraft(CraftItemEvent event) {
        if (event.getWhoClicked() instanceof Player player && isolated(player).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (temporaryItems.isTemporary(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        if (temporaryItems.isTemporary(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    /**
     * Prevents copied or temporary inventory items from becoming persistent
     * world entities. This deliberately also applies to mutable Build Battle
     * plots; server-owned template entities must be created by the plugin, not
     * transferred from a participant inventory.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getPlayer() != null && isolated(event.getPlayer()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() != null && isolated(event.getPlayer()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityInteraction(PlayerInteractEntityEvent event) {
        if (isolated(event.getPlayer()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player && isolated(player).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onLeash(PlayerLeashEntityEvent event) {
        if (isolated(event.getPlayer()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFish(PlayerFishEvent event) {
        if (isolated(event.getPlayer()).isPresent()) {
            event.setCancelled(true);
            event.setExpToDrop(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        if (isolated(event.getPlayer()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onShear(PlayerShearEntityEvent event) {
        if (isolated(event.getPlayer()).isPresent()) {
            event.setCancelled(true);
            event.setDrops(java.util.List.of());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTakeLecternBook(PlayerTakeLecternBookEvent event) {
        if (isolated(event.getPlayer()).isPresent()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            if (event instanceof EntityDamageByEntityEvent byEntity) {
                Player attacker = attackingPlayer(byEntity.getDamager()).orElse(null);
                if (attacker != null && isolated(attacker).isPresent()) {
                    event.setCancelled(true);
                }
            }
            return;
        }
        Optional<PlayerSession> victimSession = isolated(victim);
        if (victimSession.isEmpty()) {
            if (event instanceof EntityDamageByEntityEvent byEntity) {
                attackingPlayer(byEntity.getDamager())
                        .flatMap(this::isolated)
                        .ifPresent(ignored -> event.setCancelled(true));
            }
            return;
        }
        PlayerSession victimState = victimSession.orElseThrow();
        CombatPolicy policy = combatPolicies.find(victimState.matchId()).orElse(null);
        if (policy == null) {
            event.setCancelled(true);
            return;
        }
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            event.setCancelled(!policy.environmentalDamage());
            return;
        }
        Player attacker = attackingPlayer(byEntity.getDamager()).orElse(null);
        Optional<PlayerSession> attackerSession = attacker == null ? Optional.empty() : isolated(attacker);
        boolean permitted = attacker != null
                && attackerSession.isPresent()
                && attackerSession.orElseThrow().matchId().equals(victimState.matchId())
                && policy.permits(attacker.getUniqueId(), victim.getUniqueId());
        event.setCancelled(!permitted);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (isolated(event.getPlayer()).isPresent() && !allowedSessionCommand(event.getMessage(), owner)
                && !(authentication.current(event.getPlayer().getUniqueId(), java.time.Instant.now()).isEmpty()
                        && providerLoginCommand(event.getMessage(), owner))) {
            event.setCancelled(true);
            event.getPlayer().sendRichMessage(
                    "<red>Esse comando não está disponível durante um minijogo.</red>");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        isolated(event.getEntity()).ifPresent(ignored -> {
            event.getDrops().clear();
            event.setDroppedExp(0);
            event.setKeepInventory(true);
            event.setKeepLevel(true);
            // The per-game router translates this into a controlled
            // elimination, checkpoint reset, or recovery. Triggering the
            // generic recovery path here as well would race that state
            // machine and could restore a player while a match continues.
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        authentication.invalidatePlayer(event.getPlayer().getUniqueId());
        isolated(event.getPlayer()).ifPresent(session ->
                violations.onViolation(event.getPlayer(), session, SessionViolation.DISCONNECT));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        isolated(event.getPlayer()).filter(session -> session.phase() == SessionPhase.ACTIVE)
                .ifPresent(session -> violations.onViolation(
                        event.getPlayer(), session, SessionViolation.UNAUTHORIZED_WORLD_CHANGE));
    }

    private Optional<PlayerSession> isolated(Player player) {
        return sessions.findByPlayer(player.getUniqueId())
                .filter(session -> session.phase() != SessionPhase.REQUESTED)
                .filter(session -> session.phase() != SessionPhase.SNAPSHOTTING)
                .filter(session -> session.phase() != SessionPhase.CLOSED);
    }

    private static Optional<Player> attackingPlayer(Entity damager) {
        if (damager instanceof Player player) {
            return Optional.of(player);
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return Optional.of(player);
        }
        return Optional.empty();
    }

    static boolean allowedSessionCommand(String raw, Plugin owner) {
        String command = raw.startsWith("/") ? raw.substring(1) : raw;
        command = command.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        int space = command.indexOf(' ');
        String label = space < 0 ? command : command.substring(0, space);
        PluginCommand resolved = owner.getServer().getPluginCommand(label);
        if (resolved == null || resolved.getPlugin() != owner) return false;
        command = resolved.getName().toLowerCase(Locale.ROOT) + (space < 0 ? "" : command.substring(space));
        if (command.equals("minijogos") || command.equals("minigames")
                || command.equals("minijogos estado") || command.equals("minigames estado")
                || command.equals("minijogos ajuda") || command.equals("minigames ajuda")) {
            return true;
        }
        if (command.matches("(buildbattle|bb) (ver)( seguinte)?")
                || command.matches("(buildbattle|bb) (avaliar|votar|vote) [a-z0-9][a-z0-9_.-]{0,31} [0-9]{1,3}")) {
            return true;
        }
        return command.matches("(coliseu|arena|buildbattle|bb|batataquente|hotpotato|sumo|parkour|arco|bigornas|cores|elytra) (estado|ajuda|sair)");
    }

    /** Recovery cannot finish unless the actual provider can process normal login. */
    static boolean providerLoginCommand(String raw, Plugin owner) {
        String text = raw.startsWith("/") ? raw.substring(1) : raw;
        String label = text.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        // Paper's Brigadier registrations are not PluginCommand instances.
        var command = owner.getServer().getCommandMap().getCommand(label);
        if (!(command instanceof PluginIdentifiableCommand identified)) return false;
        Plugin provider = identified.getPlugin();
        var manager = owner.getServer().getPluginManager();
        boolean authMe = manager.isPluginEnabled("AuthMe");
        boolean nLogin = manager.isPluginEnabled("nLogin");
        if (authMe == nLogin) return false;
        if (provider != manager.getPlugin(authMe ? "AuthMe" : "nLogin") || !provider.isEnabled()) return false;
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (authMe && name.startsWith("authme:")) name = name.substring("authme:".length());
        return name.equals("login") || authMe && (name.equals("l") || name.equals("log"));
    }
}
