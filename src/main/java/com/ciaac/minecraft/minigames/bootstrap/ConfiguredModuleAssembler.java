package com.ciaac.minecraft.minigames.bootstrap;

import com.ciaac.minecraft.minigames.anvildodge.AnvilDodgeGame;
import com.ciaac.minecraft.minigames.anvildodge.AnvilDodgePhase;
import com.ciaac.minecraft.minigames.archeryrange.ArcheryConfig;
import com.ciaac.minecraft.minigames.arena.ArenaEquipmentContract;
import com.ciaac.minecraft.minigames.arena.ArenaKitMode;
import com.ciaac.minecraft.minigames.arena.ArenaLocationPolicy;
import com.ciaac.minecraft.minigames.arena.StakedItem;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattlePlot;
import com.ciaac.minecraft.minigames.colorfloor.ColorFloorGame;
import com.ciaac.minecraft.minigames.colorfloor.ColorFloorPhase;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.elytrarings.ElytraRingsGame;
import com.ciaac.minecraft.minigames.elytrarings.ElytraRingsPhase;
import com.ciaac.minecraft.minigames.module.MinigameModule;
import com.ciaac.minecraft.minigames.module.MinigameModuleRegistry;
import com.ciaac.minecraft.minigames.module.ModuleAvailability;
import com.ciaac.minecraft.minigames.module.ModuleStatus;
import com.ciaac.minecraft.minigames.module.UnavailableMinigameModule;
import com.ciaac.minecraft.minigames.paper.anvildodge.AnvilDodgeController;
import com.ciaac.minecraft.minigames.paper.anvildodge.AnvilDodgePaperSettings;
import com.ciaac.minecraft.minigames.paper.archeryrange.ArcheryPaperController;
import com.ciaac.minecraft.minigames.paper.archeryrange.ArcheryPaperSettings;
import com.ciaac.minecraft.minigames.paper.arena.ArenaItemManifestBuilder;
import com.ciaac.minecraft.minigames.paper.arena.ArenaKitProvider;
import com.ciaac.minecraft.minigames.paper.arena.ArenaLoadoutSnapshot;
import com.ciaac.minecraft.minigames.paper.arena.ColiseumController;
import com.ciaac.minecraft.minigames.paper.arena.ColiseumSpectatorBoundaryListener;
import com.ciaac.minecraft.minigames.paper.arena.ColiseumSettings;
import com.ciaac.minecraft.minigames.paper.arena.StakedEscrowPort;
import com.ciaac.minecraft.minigames.paper.arena.staked.SqliteStakedEscrowRepository;
import com.ciaac.minecraft.minigames.paper.arena.staked.StakedEscrowRecoveryService;
import com.ciaac.minecraft.minigames.paper.buildbattle.BuildBattlePaperController;
import com.ciaac.minecraft.minigames.paper.buildbattle.BuildBattlePaperSettings;
import com.ciaac.minecraft.minigames.paper.buildbattle.BuildBattleResetPort;
import com.ciaac.minecraft.minigames.paper.checkpointparkour.ParkourPaperController;
import com.ciaac.minecraft.minigames.paper.checkpointparkour.ParkourPaperSettings;
import com.ciaac.minecraft.minigames.paper.colorfloor.ColorFloorController;
import com.ciaac.minecraft.minigames.paper.colorfloor.ColorFloorPaperSettings;
import com.ciaac.minecraft.minigames.paper.colorfloor.ColorFloorTemplatePort;
import com.ciaac.minecraft.minigames.paper.colorfloor.PaperColorFloorTemplatePort;
import com.ciaac.minecraft.minigames.paper.configuration.ConfigurationResolutionResult;
import com.ciaac.minecraft.minigames.paper.configuration.PaperConfigurationResolver;
import com.ciaac.minecraft.minigames.paper.configuration.ResolvedAnvilDodgeConfiguration;
import com.ciaac.minecraft.minigames.paper.configuration.ResolvedArcheryConfiguration;
import com.ciaac.minecraft.minigames.paper.configuration.ResolvedBuildBattleConfiguration;
import com.ciaac.minecraft.minigames.paper.configuration.ResolvedColiseumConfiguration;
import com.ciaac.minecraft.minigames.paper.configuration.ResolvedColorFloorConfiguration;
import com.ciaac.minecraft.minigames.paper.configuration.ResolvedElytraRingsConfiguration;
import com.ciaac.minecraft.minigames.paper.configuration.ResolvedGameConfiguration;
import com.ciaac.minecraft.minigames.paper.configuration.ResolvedHotPotatoConfiguration;
import com.ciaac.minecraft.minigames.paper.configuration.ResolvedModuleConfiguration;
import com.ciaac.minecraft.minigames.paper.configuration.ResolvedParkourConfiguration;
import com.ciaac.minecraft.minigames.paper.configuration.ResolvedSumoConfiguration;
import com.ciaac.minecraft.minigames.paper.elytrarings.ElytraRingsController;
import com.ciaac.minecraft.minigames.paper.elytrarings.ElytraRingsPaperSettings;
import com.ciaac.minecraft.minigames.paper.hotpotato.HotPotatoPaperController;
import com.ciaac.minecraft.minigames.paper.hotpotato.HotPotatoPaperSettings;
import com.ciaac.minecraft.minigames.paper.event.MinigameEventPoliciesFactory;
import com.ciaac.minecraft.minigames.paper.event.MinigameEventRouter;
import com.ciaac.minecraft.minigames.paper.knockbacksumo.SumoPaperController;
import com.ciaac.minecraft.minigames.paper.knockbacksumo.SumoPaperSettings;
import com.ciaac.minecraft.minigames.paper.module.AnvilDodgeModule;
import com.ciaac.minecraft.minigames.paper.module.ArcheryModule;
import com.ciaac.minecraft.minigames.paper.module.BuildBattleModule;
import com.ciaac.minecraft.minigames.paper.module.ColiseumEquipmentPort;
import com.ciaac.minecraft.minigames.paper.module.ColiseumModule;
import com.ciaac.minecraft.minigames.paper.module.ColorFloorModule;
import com.ciaac.minecraft.minigames.paper.module.ElytraRingsModule;
import com.ciaac.minecraft.minigames.paper.module.FixedControllerPort;
import com.ciaac.minecraft.minigames.paper.module.HotPotatoModule;
import com.ciaac.minecraft.minigames.paper.module.ModuleIdentity;
import com.ciaac.minecraft.minigames.paper.module.ParkourModule;
import com.ciaac.minecraft.minigames.paper.module.RegionTokenFactory;
import com.ciaac.minecraft.minigames.paper.module.SumoModule;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import com.ciaac.minecraft.minigames.region.RegionAdmissionToken;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequest;
import com.ciaac.minecraft.minigames.runtime.AdmissionResult;
import com.ciaac.minecraft.minigames.statistics.recording.StatisticsResultSink;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import pt.ciaac.minigames.paper.template.TemplateArtifactRepository;

/** Typed, fail-closed construction of the complete modular Paper game catalog. */
public final class ConfiguredModuleAssembler implements ModuleAssembler {
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(15);

    @Override
    public MinigameModuleRegistry assemble(PlatformServices services) {
        Objects.requireNonNull(services, "services");
        ConfigurationResolutionResult resolved = new PaperConfigurationResolver()
                .resolve(services.configuration(), services.plugin().getServer());
        resolved.diagnostics().forEach(diagnostic -> services.plugin().getLogger().warning(
                "Minigame configuration closed at " + diagnostic.path() + ": " + diagnostic.code()));

        if (!resolved.canAttemptAdmission()) {
            return MinigameModuleRegistry.allUnavailable(
                    "Entradas fechadas pela configuração global dos minijogos.");
        }
        EnumMap<GameKey, List<ProtectedRegion>> regionPlans = new EnumMap<>(GameKey.class);
        for (GameKey game : GameKey.values()) {
            ResolvedModuleConfiguration module = resolved.module(game).orElseThrow();
            if (module.admissionAllowed() && services.isolationReady(game)) {
                regionPlans.put(game, plannedRegions(module));
            }
        }
        Set<GameKey> geometryConflicts = conflictingGames(regionPlans);
        geometryConflicts.forEach(game -> services.plugin().getLogger().severe(
                "Protected geometry conflict closed module " + game.id()));
        for (Map.Entry<GameKey, List<ProtectedRegion>> entry : regionPlans.entrySet()) {
            if (geometryConflicts.contains(entry.getKey())) continue;
            entry.getValue().forEach(services.regions()::register);
        }

        StatisticsResultSink statistics = new StatisticsResultSink(
                services.statistics(),
                services.announcements()::winner,
                failure -> services.plugin().getLogger().severe(
                        "Statistics result was not committed: " + failure.resultId() + " / " + failure.code()));
        ModuleIdentity identity = new ModuleIdentity(services.connections(), services.admissionRequests());
        RegionTokenFactory tokens = (game, request, regionId, now, lifetime) ->
                new RegionAdmissionToken(UUID.randomUUID(), request.sessionId(), request.playerId(),
                        regionId, now, now.plus(lifetime));

        List<MinigameModule> modules = new ArrayList<>();
        for (GameKey game : GameKey.values()) {
            ResolvedModuleConfiguration module = resolved.module(game).orElseThrow();
            if (!module.admissionAllowed()) {
                modules.add(unavailable(game, module.enabled()
                        ? "Configuração incompleta; consulta os diagnósticos do servidor."
                        : "Este minijogo está fechado pelo operador."));
                continue;
            }
            if (!services.isolationReady(game)) {
                services.plugin().getLogger().warning(
                        "Minigame " + game.id() + " fechado: faltam facetas de isolamento para este jogo.");
                modules.add(unavailable(game,
                        "Este minijogo está fechado porque o isolamento da progressão não está disponível."));
                continue;
            }
            if (geometryConflicts.contains(game)) {
                modules.add(unavailable(game,
                        "A geometria protegida deste minijogo entra em conflito com outra área."));
                continue;
            }
            try {
                modules.add(create(game, module.gameConfiguration().orElseThrow(), services,
                        identity, tokens, statistics));
            } catch (RuntimeException | LinkageError failure) {
                services.plugin().getLogger().severe("O módulo de minijogo ficou fechado para " + game.id()
                        + ": " + failure.getClass().getSimpleName());
                modules.add(unavailable(game,
                        "O módulo ficou fechado porque a construção segura não foi concluída."));
            }
        }
        MinigameModuleRegistry registry = new MinigameModuleRegistry(modules);
        services.plugin().getServer().getPluginManager().registerEvents(
                MinigameEventRouter.fromRegistry(registry, services.clock(),
                        MinigameEventPoliciesFactory.create(registry, resolved, services),
                        route -> services.plugin().getLogger().warning(
                                "Minigame disconnect cleanup failed [" + route + "]")),
                services.plugin());
        return registry;
    }

    private MinigameModule create(
            GameKey game,
            ResolvedGameConfiguration configuration,
            PlatformServices services,
            ModuleIdentity identity,
            RegionTokenFactory tokens,
            StatisticsResultSink statistics) {
        return switch (game) {
            case ARENA -> arena((ResolvedColiseumConfiguration) configuration, services, identity, statistics);
            case BUILD_BATTLE -> buildBattle((ResolvedBuildBattleConfiguration) configuration, services, identity, statistics);
            case HOT_POTATO -> hotPotato((ResolvedHotPotatoConfiguration) configuration, services, identity, statistics);
            case KNOCKBACK_SUMO -> sumo((ResolvedSumoConfiguration) configuration, services, identity, statistics);
            case CHECKPOINT_PARKOUR -> parkour((ResolvedParkourConfiguration) configuration, services, identity, statistics);
            case ARCHERY_RANGE -> archery((ResolvedArcheryConfiguration) configuration, services, identity, statistics);
            case ANVIL_DODGE -> anvil((ResolvedAnvilDodgeConfiguration) configuration, services, identity, tokens, statistics);
            case COLOR_FLOOR -> colorFloor((ResolvedColorFloorConfiguration) configuration, services, identity, tokens, statistics);
            case ELYTRA_RINGS -> elytra((ResolvedElytraRingsConfiguration) configuration, services, identity, tokens, statistics);
        };
    }

    private static UnavailableMinigameModule unavailable(GameKey game, String reason) {
        return new UnavailableMinigameModule(game, reason);
    }

    private ColiseumModule arena(
            ResolvedColiseumConfiguration value,
            PlatformServices services,
            ModuleIdentity identity,
            StatisticsResultSink statistics) {
        String combatRegion = regionId(GameKey.ARENA, "combat-floor");
        String spectatorRegion = regionId(GameKey.ARENA, "spectator-benches");
        ArenaLocationPolicy locations = new ArenaLocationPolicy(
                value.world().getName(), combatRegion, spectatorRegion,
                "team-a", "team-b", "recovery");
        ColiseumSettings settings = new ColiseumSettings(
                true,
                Optional.of(value.world().getUID()),
                Optional.of(locations),
                Optional.of(required(value.locations(), "team-a")),
                Optional.of(required(value.locations(), "team-b")),
                Optional.of(required(value.locations(), "recovery")),
                value.formatPolicy(),
                Duration.ofSeconds(60),
                Duration.ofSeconds(30),
                value.roundDuration(),
                value.reconnectGrace(),
                value.friendlyFire(),
                materialNames(value.protectedProhibitedMaterials()),
                value.stakedSurvivalEnabled(), value.stakedAllowedFormats(),
                materialNames(value.stakedProhibitedMaterials()), value.stakeConsentTimeout(),
                value.noContestPolicy(), value.rulesetRevision());
        Map<String, List<ItemStack>> kits = new LinkedHashMap<>();
        value.fixedKits().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                kits.put(entry.getKey(), entry.getValue().stream().map(ItemStack::new).toList()));
        ArenaKitProvider kitProvider = id -> Optional.ofNullable(kits.get(id))
                .map(items -> items.stream().map(ItemStack::clone).toList());
        SqliteStakedEscrowRepository escrowRepository = new SqliteStakedEscrowRepository(services.database());
        StakedEscrowRecoveryService.RecoveryReport escrowRecovery =
                StakedEscrowRecoveryService.reconcile(escrowRepository);
        if (escrowRecovery.refundedEscrows() > 0) {
            services.plugin().getLogger().warning("Foram recuperadas " + escrowRecovery.refundedEscrows()
                    + " apostas interrompidas; o equipamento retirado ficou disponível para reclamação.");
        }
        if (escrowRecovery.blockedEscrows() > 0) {
            services.plugin().getLogger().severe("As apostas ficaram fechadas [STAKED_RECOVERY_REQUIRED]: "
                    + escrowRecovery.blockedEscrows() + " operações ambíguas precisam de revisão.");
        }
        // Keep the repository reachable even while creation of new stakes is
        // disabled, so already durable player claims remain collectible.
        Optional<StakedEscrowPort> escrow = Optional.of(escrowRepository);
        ColiseumController controller = new ColiseumController(
                settings,
                services.plugin().getServer(),
                services.sessionCoordinator(),
                player -> services.connections().current(player.getUniqueId())
                        .filter(connection -> connection.player() == player)
                        .map(com.ciaac.minecraft.minigames.runtime.ConnectionRegistry.Connection::id),
                kitProvider,
                services.temporaryItems(),
                services.regions(),
                services.regionAdmissions(),
                services.combatPolicies(),
                escrow,
                services.clock(),
                statistics,
                services.authentication(),
                services.connections());
        String defaultKit = kits.keySet().stream().sorted().findFirst().orElse(null);
        ArenaItemManifestBuilder manifests = new ArenaItemManifestBuilder();
        ColiseumEquipmentPort equipment = (player, mode) -> switch (mode) {
            case FIXED -> {
                if (!value.kitModes().contains(mode) || defaultKit == null) {
                    throw new IllegalArgumentException("FIXED_KIT_UNAVAILABLE");
                }
                yield ArenaEquipmentContract.fixed(defaultKit);
            }
            case MIRRORED_SURVIVAL -> {
                if (!value.kitModes().contains(mode)) throw new IllegalArgumentException("MODE_UNAVAILABLE");
                var manifest = manifests.build(player.getUniqueId(), ArenaLoadoutSnapshot.capture(player),
                        materialNames(value.protectedProhibitedMaterials()));
                yield ArenaEquipmentContract.protectedCopy(manifestDigest(manifest.items()));
            }
            case STAKED_SURVIVAL -> {
                if (!value.kitModes().contains(mode) || !value.stakedSurvivalEnabled()) {
                    throw new IllegalArgumentException("STAKED_MODE_UNAVAILABLE");
                }
                yield ArenaEquipmentContract.staked();
            }
        };
        ColiseumModule module = new ColiseumModule(controller, identity, equipment, services.clock());
        services.plugin().getServer().getPluginManager().registerEvents(
                new ColiseumSpectatorBoundaryListener(
                        settings, services.regions(), controller, services.plugin()),
                services.plugin());
        return module;
    }

    private BuildBattleModule buildBattle(
            ResolvedBuildBattleConfiguration value,
            PlatformServices services,
            ModuleIdentity identity,
            StatisticsResultSink statistics) {
        Map<BuildBattlePlot, BuildBattlePaperSettings.PlotSettings> plots = new LinkedHashMap<>();
        value.plots().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var plot = entry.getValue();
            plots.put(new BuildBattlePlot(plot.id()), new BuildBattlePaperSettings.PlotSettings(
                    regionId(GameKey.BUILD_BATTLE, plot.regionId()), plot.spawn()));
        });
        BuildBattlePaperSettings settings = new BuildBattlePaperSettings(
                true, value.domain(), value.themes(), value.world(),
                regionId(GameKey.BUILD_BATTLE, "lobby"), required(value.locations(), "lobby"),
                plots, value.queueDuration(), value.buildDuration(),
                value.voteDuration().multipliedBy(value.domain().maximumPlayers()), TOKEN_LIFETIME,
                value.themeVoteDuration(), Duration.ofSeconds(value.secondsPerPlot()));
        Optional<BuildBattleResetPort> reset = services.buildBattleReset();
        if (reset.isEmpty()) throw new IllegalStateException("BUILD_BATTLE_RESET_PROVIDER_UNAVAILABLE");
        BuildBattlePaperController controller = new BuildBattlePaperController(
                settings, services.sessionCoordinator(), services.sessions(), services.regions(),
                services.regionAdmissions(), services.temporaryItems(), reset, services.clock(), statistics, session -> {
                    Player player = services.plugin().getServer().getPlayer(session.playerId());
                    var auth = services.authentication().current(session.playerId(), services.clock().instant());
                    return player != null && player.isOnline() && player.isValid() && auth.isPresent()
                            && services.connections().isCurrent(player, auth.orElseThrow().connectionId());
                });
        return new BuildBattleModule(controller, identity, services.clock());
    }

    private HotPotatoModule hotPotato(
            ResolvedHotPotatoConfiguration value,
            PlatformServices services,
            ModuleIdentity identity,
            StatisticsResultSink statistics) {
        HotPotatoPaperSettings settings = new HotPotatoPaperSettings(
                true, value.domain(), value.world(), regionId(GameKey.HOT_POTATO, "arena"),
                value.spawns(), value.countdown(), value.tokenLifetime());
        HotPotatoPaperController controller = new HotPotatoPaperController(
                settings, services.sessionCoordinator(), services.sessions(), services.regions(),
                services.regionAdmissions(), services.temporaryItems(), services.clock(), statistics);
        return new HotPotatoModule(controller, identity, services.clock());
    }

    private SumoModule sumo(
            ResolvedSumoConfiguration value,
            PlatformServices services,
            ModuleIdentity identity,
            StatisticsResultSink statistics) {
        SumoPaperSettings settings = new SumoPaperSettings(
                true, regionId(GameKey.KNOCKBACK_SUMO, "boundary"),
                required(value.locations(), "side-a"), required(value.locations(), "side-b"),
                TOKEN_LIFETIME, value.knockbackItem(), value.knockbackLevel(), value.fallThresholdY());
        SumoPaperController controller = new SumoPaperController(
                settings, value.domain(), services.sessionCoordinator(), services.regions(),
                services.regionAdmissions(), services.temporaryItems(), services.clock(), statistics);
        return new SumoModule(controller, identity, () -> { }, services.clock());
    }

    private ParkourModule parkour(
            ResolvedParkourConfiguration value,
            PlatformServices services,
            ModuleIdentity identity,
            StatisticsResultSink statistics) {
        List<Location> checkpoints = value.checkpointOrder().stream()
                .map(id -> center(value.world(), required(value.checkpointRegions(), id))).toList();
        ParkourPaperSettings settings = new ParkourPaperSettings(
                true, regionId(GameKey.CHECKPOINT_PARKOUR, "course-boundary"),
                required(value.locations(), "start"), checkpoints, TOKEN_LIFETIME,
                value.concurrentRunners(), value.hideOtherRunners());
        ParkourPaperController controller = new ParkourPaperController(
                settings, value.domain(), services.sessionCoordinator(), services.regions(),
                services.regionAdmissions(), services.temporaryItems(), services.clock(),
                services.plugin(), statistics);
        return new ParkourModule(controller, identity, () -> { }, services.clock());
    }

    private ArcheryModule archery(
            ResolvedArcheryConfiguration value,
            PlatformServices services,
            ModuleIdentity identity,
            StatisticsResultSink statistics) {
        List<ArcheryPaperSettings.LaneSettings> lanes = value.lanes().values().stream()
                .sorted(Comparator.comparingInt(ResolvedArcheryConfiguration.LaneDefinition::id))
                .map(lane -> new ArcheryPaperSettings.LaneSettings(
                        lane.id(), regionId(GameKey.ARCHERY_RANGE, lane.regionId()),
                        lane.spawn(), lane.targetId()))
                .toList();
        ArcheryPaperSettings settings = ArcheryPaperSettings.multi(
                true, lanes, value.shotsPerAttempt(), TOKEN_LIFETIME, value.attemptTimeout(),
                value.bowMaterial(), value.scoreBands());
        Map<Integer, ArcheryConfig> configs = value.lanes().values().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        ResolvedArcheryConfiguration.LaneDefinition::id,
                        ResolvedArcheryConfiguration.LaneDefinition::domain));
        ArcheryPaperController controller = new ArcheryPaperController(
                settings, configs, services.sessionCoordinator(), services.regions(),
                services.regionAdmissions(), services.temporaryItems(), services.clock(), statistics);
        return new ArcheryModule(controller, identity, () -> { }, services.clock());
    }

    private AnvilDodgeModule anvil(
            ResolvedAnvilDodgeConfiguration value,
            PlatformServices services,
            ModuleIdentity identity,
            RegionTokenFactory tokens,
            StatisticsResultSink statistics) {
        String region = regionId(GameKey.ANVIL_DODGE, "boundary");
        CuboidRegion floor = required(value.regions(), "floor");
        Location floorOrigin = new Location(value.world(), floor.minX(), floor.minY(), floor.minZ());
        AnvilDodgePaperSettings settings = new AnvilDodgePaperSettings(
                true, value.domain(), value.world(), region, required(value.locations(), "start"),
                floorOrigin, value.floorWidth(), value.floorDepth());
        FixedControllerPort<AnvilDodgeController> port = new FixedControllerPort<>() {
            @Override public ModuleStatus inactiveStatus() {
                return waiting(GameKey.ANVIL_DODGE, value.domain().maximumPlayers(),
                        "A Fuga às Bigornas está disponível.");
            }

            @Override public AnvilDodgeController create(UUID matchId, Player initialPlayer) {
                var controller = new AnvilDodgeController(matchId, new AnvilDodgeGame(matchId, value.domain()),
                        settings, services.sessionCoordinator(), services.sessions(),
                        services.regionAdmissions(), services.regions(), services.clock(),
                        services.plugin(), statistics, session -> {
                            Player player = services.plugin().getServer().getPlayer(session.playerId());
                            var auth = services.authentication().current(session.playerId(), services.clock().instant());
                            return player != null && player.isOnline() && player.isValid() && auth.isPresent()
                                    && services.connections().isCurrent(player, auth.orElseThrow().connectionId());
                        });
                controller.hazardOwnership(services.anvilHazards().orElseThrow(
                        () -> new IllegalStateException("ANVIL_NATIVE_OWNERSHIP_UNAVAILABLE")));
                return controller;
            }

            @Override public ModuleStatus status(AnvilDodgeController controller, UUID matchId) {
                var status = controller.status();
                int players = services.sessions().findByMatch(matchId).size();
                String phase = status.phase() == AnvilDodgePhase.DISABLED && status.admissionReady()
                        ? "WAITING" : status.phase().name();
                return ConfiguredModuleAssembler.status(
                        GameKey.ANVIL_DODGE, status.enabled(), status.admissionReady(), phase,
                        players, value.domain().maximumPlayers(), status.messagePtPt());
            }

            @Override public AdmissionResult join(AnvilDodgeController controller, Player player,
                                                   AdmissionRequest request, RegionAdmissionToken token) {
                return controller.join(player, request, token);
            }
            @Override public void leave(AnvilDodgeController controller, UUID playerId, UUID operationId) {
                controller.leave(playerId, operationId);
            }
            @Override public void tick(AnvilDodgeController controller, Instant now) { controller.tick(now); }
            @Override public void shutdown(AnvilDodgeController controller, UUID operationId) {
                controller.shutdown(operationId);
            }
            @Override public boolean terminal(AnvilDodgeController controller) {
                return controller.status().phase() == AnvilDodgePhase.CLOSED;
            }
        };
        return new AnvilDodgeModule(identity, port, tokens, region, TOKEN_LIFETIME, services.clock());
    }

    private ColorFloorModule colorFloor(
            ResolvedColorFloorConfiguration value,
            PlatformServices services,
            ModuleIdentity identity,
            RegionTokenFactory tokens,
            StatisticsResultSink statistics) {
        ColorFloorTemplatePort nativeTemplate = new PaperColorFloorTemplatePort(
                templateRepository(services), value.floorTemplate().identifier());
        ColorFloorTemplatePort templatePort = Optional.of(
                        service(services, ColorFloorTemplatePort.class).orElse(nativeTemplate))
                .filter(ColorFloorTemplatePort::available)
                .orElseThrow(() -> new IllegalStateException("COLOR_TEMPLATE_UNAVAILABLE"));
        CuboidRegion floor = required(value.regions(), "floor");
        ColorFloorTemplatePort.Template template = templatePort
                .load(value.world(), floor, value.rulesetRevision())
                .orElseThrow(() -> new IllegalStateException("COLOR_TEMPLATE_UNAVAILABLE"));
        if (!new HashSet<>(template.colors().values()).equals(new HashSet<>(value.palette()))) {
            throw new IllegalStateException("COLOR_TEMPLATE_PALETTE_MISMATCH");
        }
        String region = regionId(GameKey.COLOR_FLOOR, "boundary");
        ColorFloorPaperSettings settings = new ColorFloorPaperSettings(
                true, value.domain(), value.world(), region, required(value.locations(), "start"),
                template.colors(), template.blocks(), value.announceDuration());
        FixedControllerPort<ColorFloorController> port = new FixedControllerPort<>() {
            @Override public ModuleStatus inactiveStatus() {
                return waiting(GameKey.COLOR_FLOOR, value.domain().maximumPlayers(),
                        "O Chão de Cores está disponível.");
            }

            @Override public ColorFloorController create(UUID matchId, Player initialPlayer) {
                return new ColorFloorController(matchId, new ColorFloorGame(matchId, value.domain()),
                        settings, services.sessionCoordinator(), services.sessions(),
                        services.regionAdmissions(), services.regions(), services.clock(), statistics, session -> {
                            Player player = services.plugin().getServer().getPlayer(session.playerId());
                            var auth = services.authentication().current(session.playerId(), services.clock().instant());
                            return player != null && player.isOnline() && player.isValid() && auth.isPresent()
                                    && services.connections().isCurrent(player, auth.orElseThrow().connectionId());
                        });
            }

            @Override public ModuleStatus status(ColorFloorController controller, UUID matchId) {
                var status = controller.status();
                int players = services.sessions().findByMatch(matchId).size();
                String phase = status.phase() == ColorFloorPhase.DISABLED && status.admissionReady()
                        ? "WAITING" : status.phase().name();
                return ConfiguredModuleAssembler.status(
                        GameKey.COLOR_FLOOR, status.enabled(), status.admissionReady(), phase,
                        players, value.domain().maximumPlayers(), status.messagePtPt());
            }

            @Override public AdmissionResult join(ColorFloorController controller, Player player,
                                                   AdmissionRequest request, RegionAdmissionToken token) {
                return controller.join(player, request, token);
            }
            @Override public void leave(ColorFloorController controller, UUID playerId, UUID operationId) {
                controller.leave(playerId, operationId);
            }
            @Override public void tick(ColorFloorController controller, Instant now) { controller.tick(now); }
            @Override public void shutdown(ColorFloorController controller, UUID operationId) {
                controller.shutdown(operationId);
            }
            @Override public boolean terminal(ColorFloorController controller) {
                return controller.status().phase() == ColorFloorPhase.CLOSED;
            }
        };
        return new ColorFloorModule(identity, port, tokens, region, TOKEN_LIFETIME, services.clock());
    }

    private ElytraRingsModule elytra(
            ResolvedElytraRingsConfiguration value,
            PlatformServices services,
            ModuleIdentity identity,
            RegionTokenFactory tokens,
            StatisticsResultSink statistics) {
        String region = regionId(GameKey.ELYTRA_RINGS, "course-boundary");
        ElytraRingsPaperSettings settings = new ElytraRingsPaperSettings(
                true, value.domain(), value.world(), region, required(value.locations(), "start"),
                value.ringRadius(), value.allowRockets() ? value.fireworkRockets() : 0,
                value.preloadRadiusChunks(), value.ringOrder().stream()
                        .map(id -> Objects.requireNonNull(value.ringRegions().get(id),
                                "Missing resolved ring region " + id))
                        .toList());
        FixedControllerPort<ElytraRingsController> port = new FixedControllerPort<>() {
            @Override public ModuleStatus inactiveStatus() {
                return waiting(GameKey.ELYTRA_RINGS, 1, "Os Anéis de Elytra estão disponíveis.");
            }

            @Override public ElytraRingsController create(UUID matchId, Player initialPlayer) {
                return new ElytraRingsController(matchId,
                        new ElytraRingsGame(matchId, initialPlayer.getUniqueId(), value.domain()),
                settings, services.sessionCoordinator(), services.sessions(),
                services.regionAdmissions(), services.regions(), services.temporaryItems(),
                services.clock(), statistics,
                new com.ciaac.minecraft.minigames.paper.elytrarings.ElytraChunkPreparation(settings, services.plugin()),
                session -> {
                    Player player = services.plugin().getServer().getPlayer(session.playerId());
                    var auth = services.authentication().current(session.playerId(), services.clock().instant());
                    return player != null && player.isOnline() && player.isValid() && auth.isPresent()
                            && services.connections().isCurrent(player, auth.orElseThrow().connectionId());
                });
            }

            @Override public ModuleStatus status(ElytraRingsController controller, UUID matchId) {
                var status = controller.status();
                int players = services.sessions().findByMatch(matchId).size();
                String phase = status.phase() == ElytraRingsPhase.DISABLED && status.admissionReady()
                        ? "WAITING" : status.phase().name();
                return ConfiguredModuleAssembler.status(
                        GameKey.ELYTRA_RINGS, status.enabled(), status.admissionReady(), phase,
                        players, 1, status.messagePtPt());
            }

            @Override public AdmissionResult join(ElytraRingsController controller, Player player,
                                                   AdmissionRequest request, RegionAdmissionToken token) {
                return controller.join(player, request, token);
            }
            @Override public void leave(ElytraRingsController controller, UUID playerId, UUID operationId) {
                controller.leave(playerId, operationId);
            }
            @Override public void tick(ElytraRingsController controller, Instant now) { controller.tick(now); }
            @Override public void shutdown(ElytraRingsController controller, UUID operationId) {
                controller.shutdown(operationId);
            }
            @Override public boolean terminal(ElytraRingsController controller) {
                return controller.status().phase() == ElytraRingsPhase.CLOSED;
            }
        };
        return new ElytraRingsModule(identity, port, tokens, region, TOKEN_LIFETIME, services.clock());
    }

    private static List<ProtectedRegion> plannedRegions(ResolvedModuleConfiguration module) {
        ResolvedGameConfiguration configuration = module.gameConfiguration().orElseThrow();
        List<ProtectedRegion> regions = new ArrayList<>();
        switch (module.game()) {
            case ARENA -> {
                add(regions, module.game(), "combat-floor", required(configuration.regions(), "combat-floor"),
                        ProtectedRegionRole.PARTICIPANT_ONLY, true);
                add(regions, module.game(), "spectator-benches", required(configuration.regions(), "spectator-benches"),
                        ProtectedRegionRole.SPECTATOR_PUBLIC, true);
            }
            case BUILD_BATTLE -> {
                add(regions, module.game(), "lobby", required(configuration.regions(), "lobby"),
                        ProtectedRegionRole.PARTICIPANT_ONLY, true);
                ResolvedBuildBattleConfiguration build = (ResolvedBuildBattleConfiguration) configuration;
                for (var plot : build.plots().values()) {
                    add(regions, module.game(), plot.regionId(), required(configuration.regions(), plot.regionId()),
                            ProtectedRegionRole.PARTICIPANT_ONLY, false);
                }
            }
            case HOT_POTATO -> add(regions, module.game(), "arena",
                    required(configuration.regions(), "arena"), ProtectedRegionRole.GAME_WORLD_BOUNDARY, true);
            case KNOCKBACK_SUMO -> add(regions, module.game(), "boundary",
                    required(configuration.regions(), "boundary"), ProtectedRegionRole.PARTICIPANT_ONLY, true);
            case CHECKPOINT_PARKOUR -> add(regions, module.game(), "course-boundary",
                    required(configuration.regions(), "course-boundary"), ProtectedRegionRole.PARTICIPANT_ONLY, true);
            case ARCHERY_RANGE -> {
                ResolvedArcheryConfiguration archery = (ResolvedArcheryConfiguration) configuration;
                Set<String> laneRegions = new LinkedHashSet<>();
                archery.lanes().values().forEach(lane -> laneRegions.add(lane.regionId()));
                for (String id : laneRegions) {
                    add(regions, module.game(), id, required(configuration.regions(), id),
                            ProtectedRegionRole.PARTICIPANT_ONLY, true);
                }
            }
            case ANVIL_DODGE, COLOR_FLOOR -> add(regions, module.game(), "boundary",
                    required(configuration.regions(), "boundary"), ProtectedRegionRole.PARTICIPANT_ONLY, true);
            case ELYTRA_RINGS -> add(regions, module.game(), "course-boundary",
                    required(configuration.regions(), "course-boundary"),
                    ProtectedRegionRole.GAME_WORLD_BOUNDARY, true);
        }
        return List.copyOf(regions);
    }

    private static void add(
            List<ProtectedRegion> target,
            GameKey game,
            String sourceId,
            CuboidRegion bounds,
            ProtectedRegionRole role,
            boolean immutable) {
        target.add(new ProtectedRegion(regionId(game, sourceId), game, bounds, role, immutable));
    }

    private static Set<GameKey> conflictingGames(Map<GameKey, List<ProtectedRegion>> plans) {
        List<ProtectedRegion> all = plans.values().stream().flatMap(List::stream).toList();
        Set<GameKey> conflicts = new HashSet<>();
        for (int left = 0; left < all.size(); left++) {
            for (int right = left + 1; right < all.size(); right++) {
                ProtectedRegion first = all.get(left);
                ProtectedRegion second = all.get(right);
                if (first.id().equals(second.id()) || first.bounds().intersects(second.bounds())) {
                    conflicts.add(first.game());
                    conflicts.add(second.game());
                }
            }
        }
        return Set.copyOf(conflicts);
    }

    private static String regionId(GameKey game, String sourceId) {
        String normalized = Objects.requireNonNull(sourceId, "sourceId")
                .trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        String result = game.id() + "." + normalized;
        if (!result.matches("[a-z0-9][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("protected region identifier is invalid");
        }
        return result;
    }

    private static <T> T required(Map<String, T> values, String key) {
        return Objects.requireNonNull(values.get(Objects.requireNonNull(key, "key")),
                "Missing configured value " + key);
    }

    private static Location center(World world, CuboidRegion region) {
        return new Location(world,
                (region.minX() + region.maxX() + 1) / 2.0,
                region.minY() + 0.1,
                (region.minZ() + region.maxZ() + 1) / 2.0);
    }

    private static Set<String> materialNames(Set<Material> materials) {
        return materials.stream().map(material -> material.getKey().toString())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static TemplateArtifactRepository templateRepository(PlatformServices services) {
        return new TemplateArtifactRepository(services.plugin().getDataFolder().toPath().resolve("templates"));
    }

    private static String manifestDigest(List<StakedItem> items) {
        String canonical = items.stream().sorted(Comparator.comparing(StakedItem::itemId))
                .map(item -> item.itemId() + "|" + item.material() + "|" + item.amount()
                        + "|" + item.canonicalFingerprint())
                .collect(java.util.stream.Collectors.joining("\n"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError("JRE must provide SHA-256", failure);
        }
    }

    private static <T> Optional<T> service(PlatformServices services, Class<T> type) {
        RegisteredServiceProvider<T> registration = services.plugin().getServer()
                .getServicesManager().getRegistration(type);
        return Optional.ofNullable(registration).map(RegisteredServiceProvider::getProvider);
    }

    private static ModuleStatus waiting(GameKey game, int capacity, String message) {
        return new ModuleStatus(game, ModuleAvailability.WAITING, true, 0,
                OptionalInt.of(capacity), message);
    }

    private static ModuleStatus status(
            GameKey game,
            boolean enabled,
            boolean ready,
            String phase,
            int participants,
            int capacity,
            String message) {
        ModuleAvailability availability;
        if (!enabled || !ready) {
            availability = ModuleAvailability.CLOSED;
        } else {
            availability = switch (phase.toUpperCase(java.util.Locale.ROOT)) {
                case "IDLE", "WAITING" -> ModuleAvailability.WAITING;
                case "COUNTDOWN", "ADMITTING", "RESERVED_READY" -> ModuleAvailability.STARTING;
                case "RUNNING", "ACTIVE", "REACTION", "RESOLVING" -> ModuleAvailability.RUNNING;
                case "VOTING" -> ModuleAvailability.VOTING;
                case "FINISHING", "RESTORING", "RESULTS" -> ModuleAvailability.FINISHING;
                case "RECOVERING", "RESETTING" -> ModuleAvailability.RECOVERY;
                default -> ModuleAvailability.CLOSED;
            };
        }
        boolean joinable = availability == ModuleAvailability.WAITING && participants < capacity;
        return new ModuleStatus(game, availability, joinable, participants,
                OptionalInt.of(Math.max(capacity, participants)), message);
    }
}
