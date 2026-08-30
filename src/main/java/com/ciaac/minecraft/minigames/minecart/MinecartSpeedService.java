package com.ciaac.minecraft.minigames.minecart;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Applies and atomically reloads the rideable-minecart cap without touching rail velocity. */
public final class MinecartSpeedService implements Listener, AutoCloseable, MinecartSpeedControl {
    private final Plugin plugin;
    private final Server server;
    private final File configurationFile;
    private final MinecartSpeedConfigurationLoader loader;
    private final NamespacedKey originalMaxSpeedKey;
    private final Set<String> reportedIdentityMismatches = ConcurrentHashMap.newKeySet();
    private volatile MinecartSpeedConfiguration policy = MinecartSpeedConfiguration.disabled();
    private volatile MinecartSpeedOverrides overrides = MinecartSpeedOverrides.empty();

    public MinecartSpeedService(Plugin plugin, File configurationFile) {
        this(plugin, configurationFile, new MinecartSpeedConfigurationLoader());
    }

    MinecartSpeedService(Plugin plugin, File configurationFile, MinecartSpeedConfigurationLoader loader) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.server = plugin.getServer();
        this.configurationFile = Objects.requireNonNull(configurationFile, "configurationFile");
        this.loader = Objects.requireNonNull(loader, "loader");
        this.originalMaxSpeedKey = new NamespacedKey(plugin, "minecart-original-max-speed");
    }

    @Override
    public synchronized ReloadResult reload() {
        MinecartSpeedConfigurationLoader.LoadResult loaded = loader.load(configurationFile);
        if (!loaded.accepted()) return ReloadResult.failure(loaded.diagnosticPtPt());
        MinecartSpeedConfiguration candidate = loaded.configuration();
        Optional<String> mismatch = loadedIdentityMismatch(candidate);
        if (mismatch.isPresent()) return ReloadResult.failure(mismatch.orElseThrow());
        boolean cleared = overrides.active();
        List<WorldIdentity> worlds = cleared ? loadedWorldIdentities() : List.of();
        List<Optional<Double>> previous = cleared ? effectiveSpeeds(worlds) : List.of();
        MinecartSpeedConfiguration previousPolicy = policy;
        MinecartSpeedOverrides previousOverrides = overrides;
        Set<String> previousMismatches = Set.copyOf(reportedIdentityMismatches);
        List<CartState> cartStates = snapshotLoadedCarts();
        policy = candidate;
        overrides = MinecartSpeedOverrides.empty();
        try {
            reportedIdentityMismatches.clear();
            applyLoadedWorlds();
        } catch (RuntimeException failure) {
            policy = previousPolicy;
            overrides = previousOverrides;
            reportedIdentityMismatches.clear();
            reportedIdentityMismatches.addAll(previousMismatches);
            restoreCartStates(cartStates, failure);
            return ReloadResult.failure("A aplicação da nova política falhou; a configuração anterior foi mantida.");
        }
        String message = candidate.enabled()
                ? "A velocidade dos carrinhos foi recarregada e aplicada."
                : "A melhoria dos carrinhos ficou desativada e os limites originais foram repostos.";
        Optional<ChangeResult> clearedResult = cleared
                ? Optional.of(new ChangeResult(true, true, UUID.randomUUID(),
                        "O recarregamento apagou todas as substituições temporárias.",
                        changes(worlds, previous)))
                : Optional.empty();
        return ReloadResult.success(message, clearedResult);
    }

    @Override
    public String statusPtPt() {
        MinecartSpeedConfiguration current = policy;
        MinecartSpeedOverrides temporary = overrides;
        String base = current.enabled()
                ? "A melhoria dos carrinhos está ativa: padrão de " + format(current.defaultBlocksPerSecond())
                        + " blocos/s e " + current.worlds().size() + " substituições no ficheiro."
                : "A melhoria dos carrinhos está desativada no ficheiro.";
        if (!temporary.active()) return base;
        String defaultText = temporary.defaultBlocksPerSecond()
                .map(speed -> "predefinição de " + format(speed) + " blocos/s")
                .orElse("sem predefinição");
        return base + " Teste temporário ativo: " + defaultText + " e " + temporary.worlds().size()
                + " substituições por mundo; será apagado ao recarregar ou reiniciar.";
    }

    public MinecartSpeedConfiguration configuration() { return policy; }

    MinecartSpeedOverrides temporaryOverrides() { return overrides; }

    @Override
    public Optional<WorldIdentity> loadedWorldExact(String name) {
        String requested = Objects.requireNonNull(name, "name");
        World world = server.getWorld(requested);
        if (world == null || !world.getName().equals(requested)) return Optional.empty();
        return Optional.of(identity(world));
    }

    @Override
    public List<String> loadedWorldNames() {
        return server.getWorlds().stream().map(World::getName).sorted().toList();
    }

    @Override
    public synchronized ChangeResult setWorldOverride(WorldIdentity world, double blocksPerSecond) {
        UUID operationId = UUID.randomUUID();
        Optional<World> loaded = loadedWorld(world);
        if (loaded.isEmpty()) {
            return ChangeResult.rejected(operationId,
                    "O mundo indicado não está carregado com o nome e UUID esperados.");
        }
        MinecartSpeedOverrides candidate;
        try {
            candidate = overrides.withWorld(world.name(), world.worldId(), blocksPerSecond);
        } catch (IllegalArgumentException failure) {
            return ChangeResult.rejected(operationId, failure.getMessage());
        }
        Optional<Double> previous = effectiveSpeed(world);
        boolean changed = !candidate.equals(overrides);
        if (!applyTemporaryMutation(candidate, () -> applyLoadedWorld(loaded.orElseThrow()))) {
            return ChangeResult.rejected(operationId,
                    "A alteração temporária falhou; o estado anterior foi mantido.");
        }
        Optional<Double> current = effectiveSpeed(world);
        return new ChangeResult(true, changed, operationId,
                "A velocidade temporária de " + world.name() + " ficou em " + format(blocksPerSecond)
                        + " blocos/s.",
                List.of(new WorldChange(world, previous, current)));
    }

    @Override
    public synchronized ChangeResult setDefaultOverride(double blocksPerSecond) {
        UUID operationId = UUID.randomUUID();
        MinecartSpeedOverrides candidate;
        try {
            candidate = overrides.withDefault(blocksPerSecond);
        } catch (IllegalArgumentException failure) {
            return ChangeResult.rejected(operationId, failure.getMessage());
        }
        List<WorldIdentity> worlds = loadedWorldIdentities();
        List<Optional<Double>> previous = effectiveSpeeds(worlds);
        boolean changed = !candidate.equals(overrides);
        if (!applyTemporaryMutation(candidate, this::applyLoadedWorlds)) {
            return ChangeResult.rejected(operationId,
                    "A alteração temporária falhou; o estado anterior foi mantido.");
        }
        return new ChangeResult(true, changed, operationId,
                "A velocidade temporária predefinida ficou em " + format(blocksPerSecond) + " blocos/s.",
                changes(worlds, previous));
    }

    @Override
    public synchronized ChangeResult clearWorldOverride(WorldIdentity world) {
        UUID operationId = UUID.randomUUID();
        Optional<World> loaded = loadedWorld(world);
        if (loaded.isEmpty()) {
            return ChangeResult.rejected(operationId,
                    "O mundo indicado não está carregado com o nome e UUID esperados.");
        }
        Optional<Double> previous = effectiveSpeed(world);
        MinecartSpeedOverrides candidate = overrides.withoutWorld(world.name());
        boolean changed = !candidate.equals(overrides);
        if (!applyTemporaryMutation(candidate, () -> applyLoadedWorld(loaded.orElseThrow()))) {
            return ChangeResult.rejected(operationId,
                    "A alteração temporária falhou; o estado anterior foi mantido.");
        }
        Optional<Double> current = effectiveSpeed(world);
        String message = changed
                ? "A substituição temporária de " + world.name() + " foi reposta."
                : "O mundo " + world.name() + " não tinha uma substituição temporária.";
        return new ChangeResult(true, changed, operationId, message,
                List.of(new WorldChange(world, previous, current)));
    }

    @Override
    public synchronized ChangeResult clearAllOverrides() {
        UUID operationId = UUID.randomUUID();
        List<WorldIdentity> worlds = loadedWorldIdentities();
        List<Optional<Double>> previous = effectiveSpeeds(worlds);
        boolean changed = overrides.active();
        if (!applyTemporaryMutation(MinecartSpeedOverrides.empty(), this::applyLoadedWorlds)) {
            return ChangeResult.rejected(operationId,
                    "A alteração temporária falhou; o estado anterior foi mantido.");
        }
        String message = changed
                ? "Todas as substituições temporárias dos carrinhos foram apagadas."
                : "Não havia substituições temporárias dos carrinhos.";
        return new ChangeResult(true, changed, operationId, message, changes(worlds, previous));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleCreated(VehicleCreateEvent event) { apply(event.getVehicle()); }

    @EventHandler
    public void onEntitiesLoaded(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) apply(entity);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityTeleported(EntityTeleportEvent event) {
        if (event.getEntity() instanceof RideableMinecart) {
            server.getScheduler().runTask(plugin, () -> apply(event.getEntity()));
        }
    }

    @EventHandler
    public void onWorldLoaded(WorldLoadEvent event) {
        for (Entity entity : event.getWorld().getEntities()) apply(entity);
    }

    @Override
    public synchronized void close() {
        MinecartSpeedConfiguration previous = policy;
        MinecartSpeedOverrides previousOverrides = overrides;
        policy = MinecartSpeedConfiguration.disabled();
        overrides = MinecartSpeedOverrides.empty();
        if (previous.enabled() || previousOverrides.active()) applyLoadedWorlds();
    }

    private Optional<String> loadedIdentityMismatch(MinecartSpeedConfiguration candidate) {
        for (World world : server.getWorlds()) {
            MinecartSpeedConfiguration.WorldOverride override = candidate.worlds().get(world.getName());
            if (override != null && !override.worldId().equals(world.getUID())) {
                return Optional.of("O UUID configurado para o mundo " + world.getName()
                        + " não corresponde ao mundo carregado; a configuração anterior foi mantida.");
            }
        }
        return Optional.empty();
    }

    private void applyLoadedWorlds() {
        for (World world : server.getWorlds()) {
            applyLoadedWorld(world);
        }
    }

    private boolean applyTemporaryMutation(MinecartSpeedOverrides candidate, Runnable applier) {
        MinecartSpeedOverrides previous = overrides;
        Set<String> previousMismatches = Set.copyOf(reportedIdentityMismatches);
        List<CartState> cartStates = snapshotLoadedCarts();
        overrides = candidate;
        try {
            reportedIdentityMismatches.clear();
            applier.run();
            return true;
        } catch (RuntimeException failure) {
            overrides = previous;
            reportedIdentityMismatches.clear();
            reportedIdentityMismatches.addAll(previousMismatches);
            restoreCartStates(cartStates, failure);
            return false;
        }
    }

    private List<CartState> snapshotLoadedCarts() {
        ArrayList<CartState> states = new ArrayList<>();
        for (World world : server.getWorlds()) {
            for (RideableMinecart cart : world.getEntitiesByClass(RideableMinecart.class)) {
                Double original = cart.getPersistentDataContainer().get(
                        originalMaxSpeedKey, PersistentDataType.DOUBLE);
                states.add(new CartState(cart, cart.getMaxSpeed(), original));
            }
        }
        return List.copyOf(states);
    }

    private void restoreCartStates(List<CartState> states, RuntimeException originalFailure) {
        for (CartState state : states) {
            try {
                state.cart().setMaxSpeed(state.maxSpeed());
                if (state.originalMaxSpeed() == null) {
                    state.cart().getPersistentDataContainer().remove(originalMaxSpeedKey);
                } else {
                    state.cart().getPersistentDataContainer().set(
                            originalMaxSpeedKey, PersistentDataType.DOUBLE, state.originalMaxSpeed());
                }
            } catch (RuntimeException rollbackFailure) {
                originalFailure.addSuppressed(rollbackFailure);
            }
        }
    }

    private void applyLoadedWorld(World world) {
        for (RideableMinecart cart : world.getEntitiesByClass(RideableMinecart.class)) apply(cart);
    }

    private void apply(Entity entity) {
        if (!(entity instanceof RideableMinecart cart)) return;
        MinecartSpeedOverrides.Resolution resolution = overrides.resolve(
                policy, cart.getWorld().getName(), cart.getWorld().getUID());
        if (resolution.identityMismatch()) {
            String identity = cart.getWorld().getName() + ":" + cart.getWorld().getUID();
            if (reportedIdentityMismatches.add(identity)) {
                plugin.getLogger().severe("O mundo " + cart.getWorld().getName()
                        + " tem um UUID diferente do configurado; os carrinhos desse mundo não foram alterados.");
            }
            restore(cart);
            return;
        }
        Optional<Double> configured = resolution.blocksPerSecond();
        if (configured.isEmpty()) {
            restore(cart);
            return;
        }
        var data = cart.getPersistentDataContainer();
        if (!data.has(originalMaxSpeedKey, PersistentDataType.DOUBLE)) {
            data.set(originalMaxSpeedKey, PersistentDataType.DOUBLE, cart.getMaxSpeed());
        }
        cart.setMaxSpeed(configured.orElseThrow() / 20.0);
    }

    private Optional<World> loadedWorld(WorldIdentity expected) {
        Objects.requireNonNull(expected, "expected");
        World byId = server.getWorld(expected.worldId());
        if (byId == null || !byId.getName().equals(expected.name())) return Optional.empty();
        World byName = server.getWorld(expected.name());
        if (byName == null || !byName.getUID().equals(expected.worldId())) return Optional.empty();
        return Optional.of(byId);
    }

    private List<WorldIdentity> loadedWorldIdentities() {
        return server.getWorlds().stream()
                .map(MinecartSpeedService::identity)
                .sorted(Comparator.comparing(WorldIdentity::name))
                .toList();
    }

    private List<Optional<Double>> effectiveSpeeds(List<WorldIdentity> worlds) {
        return worlds.stream().map(this::effectiveSpeed).toList();
    }

    private Optional<Double> effectiveSpeed(WorldIdentity world) {
        return overrides.resolve(policy, world.name(), world.worldId()).blocksPerSecond();
    }

    private List<WorldChange> changes(List<WorldIdentity> worlds, List<Optional<Double>> previous) {
        ArrayList<WorldChange> changes = new ArrayList<>(worlds.size());
        for (int index = 0; index < worlds.size(); index++) {
            WorldIdentity world = worlds.get(index);
            changes.add(new WorldChange(world, previous.get(index), effectiveSpeed(world)));
        }
        return List.copyOf(changes);
    }

    private static WorldIdentity identity(World world) {
        return new WorldIdentity(world.getName(), world.getUID());
    }

    private record CartState(RideableMinecart cart, double maxSpeed, Double originalMaxSpeed) { }

    private void restore(RideableMinecart cart) {
        var data = cart.getPersistentDataContainer();
        Double original = data.get(originalMaxSpeedKey, PersistentDataType.DOUBLE);
        if (original == null) return;
        cart.setMaxSpeed(original);
        data.remove(originalMaxSpeedKey);
    }

    private static String format(double value) {
        return String.format(java.util.Locale.forLanguageTag("pt-PT"), "%.1f", value);
    }

}
