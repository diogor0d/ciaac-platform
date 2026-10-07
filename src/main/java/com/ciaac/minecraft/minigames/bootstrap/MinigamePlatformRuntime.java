package com.ciaac.minecraft.minigames.bootstrap;

import com.ciaac.minecraft.platform.CiaacPlatformPlugin;
import com.ciaac.minecraft.platform.securityevents.AuthenticatedPublicChatListener;
import com.ciaac.minecraft.platform.securityevents.SecurityEvent;
import com.ciaac.minecraft.minigames.announcement.AnnouncementDispatcher;
import com.ciaac.minecraft.minigames.announcement.AnnouncementService;
import com.ciaac.minecraft.minigames.announcement.ModuleAnnouncementMonitor;
import com.ciaac.minecraft.minigames.command.MinigamesCommand;
import com.ciaac.minecraft.minigames.configuration.RuntimeConfiguration;
import com.ciaac.minecraft.minigames.configuration.RuntimeConfigurationLoader;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.display.DisplayConfigLoadResult;
import com.ciaac.minecraft.minigames.display.NativeDisplayConfigLoader;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.isolation.SnapshotEnvelopeCodec;
import com.ciaac.minecraft.minigames.module.MinigameModuleRegistry;
import com.ciaac.minecraft.minigames.paper.DiscordSrvAnnouncementPublisher;
import com.ciaac.minecraft.minigames.paper.MinecraftAnnouncementPublisher;
import com.ciaac.minecraft.minigames.paper.ProgressSuppressionListener;
import com.ciaac.minecraft.minigames.paper.RegionProtectionListener;
import com.ciaac.minecraft.minigames.paper.SessionIsolationListener;
import com.ciaac.minecraft.minigames.paper.TemporaryItemTagger;
import com.ciaac.minecraft.minigames.paper.auth.ConnectionLifecycleListener;
import com.ciaac.minecraft.minigames.paper.auth.NLoginAuthenticationListener;
import com.ciaac.minecraft.minigames.paper.display.NativeDisplayController;
import com.ciaac.minecraft.minigames.paper.isolation.CompositeBukkitPlayerStateGateway;
import com.ciaac.minecraft.minigames.paper.isolation.BuiltinExternalStateAdapters;
import com.ciaac.minecraft.minigames.paper.isolation.ArenaProjectileLifecycleListener;
import com.ciaac.minecraft.minigames.paper.isolation.ArenaProjectileOwnership;
import com.ciaac.minecraft.minigames.paper.isolation.ArenaWorldStatePort;
import com.ciaac.minecraft.minigames.paper.isolation.ExternalStateFacetHandler;
import com.ciaac.minecraft.minigames.paper.isolation.ExternalStateFacetPort;
import com.ciaac.minecraft.minigames.paper.isolation.FacetSnapshotHandler;
import com.ciaac.minecraft.minigames.paper.isolation.InventoryFacetHandler;
import com.ciaac.minecraft.minigames.paper.isolation.MobilityFacetHandler;
import com.ciaac.minecraft.minigames.paper.isolation.MultiverseInventoryIsolationBinding;
import com.ciaac.minecraft.minigames.paper.isolation.ScoreboardCooldownFacetHandler;
import com.ciaac.minecraft.minigames.paper.isolation.VanillaProgressFacetHandler;
import com.ciaac.minecraft.minigames.paper.isolation.VitalsFacetHandler;
import com.ciaac.minecraft.minigames.paper.module.ColiseumModule;
import com.ciaac.minecraft.minigames.paper.recovery.SessionRecoveryService;
import com.ciaac.minecraft.minigames.persistence.AuditRepository;
import com.ciaac.minecraft.minigames.persistence.ExternalOperationJournal;
import com.ciaac.minecraft.minigames.persistence.ArenaWorldLedger;
import com.ciaac.minecraft.minigames.persistence.SessionRepository;
import com.ciaac.minecraft.minigames.persistence.SqliteAnnouncementRepository;
import com.ciaac.minecraft.minigames.persistence.SqliteAuditRepository;
import com.ciaac.minecraft.minigames.persistence.SqliteDatabase;
import com.ciaac.minecraft.minigames.persistence.SqliteSessionRepository;
import com.ciaac.minecraft.minigames.persistence.SqliteSnapshotRepository;
import com.ciaac.minecraft.minigames.persistence.SqliteStatisticsRepository;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.RegionAdmissionRegistry;
import com.ciaac.minecraft.minigames.retention.PassportPaperRuntime;
import com.ciaac.minecraft.minigames.retention.PassportService;
import com.ciaac.minecraft.minigames.retention.RetentionConfiguration;
import com.ciaac.minecraft.minigames.retention.RetentionConfigurationLoader;
import com.ciaac.minecraft.minigames.retention.SqliteRetentionRepository;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequestFactory;
import com.ciaac.minecraft.minigames.runtime.AdmissionResult;
import com.ciaac.minecraft.minigames.runtime.AdmissionStatus;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.CombatPolicyRegistry;
import com.ciaac.minecraft.minigames.runtime.ConnectionRegistry;
import com.ciaac.minecraft.minigames.runtime.SessionCoordinator;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import com.ciaac.minecraft.minigames.statistics.StatisticsRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

/** Owns all shared runtime resources and keeps construction failure fail-closed. */
public final class MinigamePlatformRuntime implements AutoCloseable {
    private static final List<String> COMMANDS = List.of(
            "minijogos", "coliseu", "buildbattle", "batataquente", "sumo",
            "parkour", "arco", "bigornas", "cores", "elytra");
    private final CiaacPlatformPlugin plugin;
    private final SqliteDatabase database;
    private final ExternalOperationJournal externalJournal;
    private final ArenaWorldLedger arenaWorldLedger;
    private final ArenaProjectileLifecycleListener arenaProjectiles;
    private final MinigameModuleRegistry modules;
    private final NativeDisplayController displays;
    private final BukkitTask heartbeat;
    private final PassportPaperRuntime passport;
    private final AuthenticationRegistry authentication;
    private final ConnectionRegistry connections;
    private final Clock clock;
    private long ticks;

    private MinigamePlatformRuntime(
            CiaacPlatformPlugin plugin,
            SqliteDatabase database,
            ExternalOperationJournal externalJournal,
            ArenaWorldLedger arenaWorldLedger,
            ArenaProjectileLifecycleListener arenaProjectiles,
            MinigameModuleRegistry modules,
            NativeDisplayController displays,
            BukkitTask heartbeat,
            PassportPaperRuntime passport,
            AuthenticationRegistry authentication,
            ConnectionRegistry connections,
            Clock clock) {
        this.plugin = plugin;
        this.database = database;
        this.externalJournal = externalJournal;
        this.arenaWorldLedger = arenaWorldLedger;
        this.arenaProjectiles = arenaProjectiles;
        this.modules = modules;
        this.displays = displays;
        this.heartbeat = heartbeat;
        this.passport = passport;
        this.authentication = authentication;
        this.connections = connections;
        this.clock = clock;
    }

    public static MinigamePlatformRuntime start(
            CiaacPlatformPlugin plugin, ModuleAssembler assembler) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(assembler, "assembler");
        Clock clock = Clock.systemUTC();
        RuntimeConfiguration configuration = new RuntimeConfigurationLoader().load(plugin.getConfig());
        SqliteDatabase database = new SqliteDatabase(
                plugin.getDataFolder().toPath(), configuration.sqlitePath());
        MinigameModuleRegistry assembledModules = null;
        NativeDisplayController displays = null;
        BukkitTask heartbeat = null;
        PassportPaperRuntime passportRuntime = null;
        ExternalOperationJournal externalJournal = null;
        ArenaWorldLedger arenaWorldLedger = null;
        ArenaProjectileLifecycleListener arenaProjectiles = null;
        try {
        SnapshotEnvelopeCodec snapshotCodec = new SnapshotEnvelopeCodec();
        SessionRepository sessionRepository = new SqliteSessionRepository(database);
        var snapshotRepository = new SqliteSnapshotRepository(database, snapshotCodec);
        AuditRepository audit = new SqliteAuditRepository(database);
        externalJournal = new ExternalOperationJournal(plugin.getDataFolder().toPath().resolve("external-state"));
        StatisticsRepository statistics = new SqliteStatisticsRepository(database);
        var announcementRepository = new SqliteAnnouncementRepository(database);

        AuthenticationRegistry authentication = new AuthenticationRegistry();
        ConnectionRegistry connections = new ConnectionRegistry();
        SessionRegistry sessions = new SessionRegistry();
        ProtectedRegionRegistry regions = new ProtectedRegionRegistry();
        RegionAdmissionRegistry regionAdmissions = new RegionAdmissionRegistry();
        CombatPolicyRegistry combatPolicies = new CombatPolicyRegistry();
        TemporaryItemTagger temporaryItems = new TemporaryItemTagger(plugin);
        IsolationPolicy isolation = IsolationPolicy.strictNoProgress();
        var inventorySharing = MultiverseInventoryIsolationBinding.register(plugin, sessions);
        List<FacetSnapshotHandler> handlers = baseHandlers(plugin, inventorySharing::preflight);
        Set<PlayerStateFacet> claimed = EnumSet.noneOf(PlayerStateFacet.class);
        handlers.forEach(handler -> claimed.addAll(handler.facets()));
        for (RegisteredServiceProvider<ExternalStateFacetPort> registration
                : plugin.getServer().getServicesManager().getRegistrations(ExternalStateFacetPort.class)) {
            ExternalStateFacetPort port = registration.getProvider();
            if (port.available()) {
                ExternalStateFacetHandler handler = new ExternalStateFacetHandler(port);
                if (!java.util.Collections.disjoint(claimed, handler.facets())) {
                    throw new IllegalStateException("External isolation adapters claim overlapping facets");
                }
                handlers.add(handler);
                claimed.addAll(handler.facets());
            }
        }
        for (ExternalStateFacetPort port : BuiltinExternalStateAdapters.create(plugin, externalJournal, audit)) {
            ExternalStateFacetHandler handler = new ExternalStateFacetHandler(port);
            if (!java.util.Collections.disjoint(claimed, handler.facets())) continue;
            handlers.add(handler);
            claimed.addAll(handler.facets());
        }
        ArenaWorldStatePort arenaWorld = null;
        ArenaProjectileOwnership ownedProjectiles = null;
        if (!claimed.contains(PlayerStateFacet.TEMPORARY_WORLD_BLOCKS_AND_ENTITIES)) {
            if (ArenaWorldStatePort.nativeBuildMatches()) {
                arenaWorldLedger = new ArenaWorldLedger(plugin.getDataFolder().toPath().resolve("arena-world"));
                ownedProjectiles = new ArenaProjectileOwnership(plugin, arenaWorldLedger);
                arenaWorld = new ArenaWorldStatePort(plugin.getServer(), regions, arenaWorldLedger,
                        ownedProjectiles, externalJournal, audit);
                handlers.add(new ExternalStateFacetHandler(arenaWorld));
                claimed.addAll(arenaWorld.facets());
            } else plugin.getLogger().warning(
                    "O isolamento de mundo da Arena ficou fechado: exige o build Paper 26.2-84-26e81c4 verificado.");
        }
        CompositeBukkitPlayerStateGateway stateGateway =
                new CompositeBukkitPlayerStateGateway(plugin.getServer(), handlers, authentication, connections, clock);
        SessionCoordinator coordinator = new SessionCoordinator(
                authentication, sessions, sessionRepository, snapshotRepository, stateGateway,
                isolation, snapshotCodec, clock, plugin.getLogger()::warning);

        var minecraftAnnouncements = new AnnouncementService(
                announcementRepository,
                new MinecraftAnnouncementPublisher(plugin.getServer()),
                configuration.announcements().minecraftWaitingCooldown());
        Optional<AnnouncementService> discordAnnouncements = configuration.announcements().discordEnabled()
                ? Optional.of(new AnnouncementService(
                        announcementRepository,
                        new DiscordSrvAnnouncementPublisher(
                                plugin.getServer(), configuration.announcements().discordChannelName()),
                        configuration.announcements().discordWaitingCooldown()))
                : Optional.empty();
        AnnouncementDispatcher announcementDispatcher = new AnnouncementDispatcher(
                minecraftAnnouncements,
                discordAnnouncements,
                configuration.announcements().announceStarting(),
                configuration.announcements().announceWinner(),
                clock,
                id -> playerName(plugin, id));
        var facetsByGame = new java.util.EnumMap<GameKey, Set<PlayerStateFacet>>(GameKey.class);
        for (GameKey game : GameKey.values()) facetsByGame.put(game, stateGateway.supportedFacets(game));
        PlatformServices services = new PlatformServices(
                plugin, configuration, clock, database, authentication, connections,
                new AdmissionRequestFactory(connections, authentication, clock), sessions, coordinator, regions,
                regionAdmissions, combatPolicies, temporaryItems, statistics, audit,
                announcementDispatcher, isolation, facetsByGame);

        AtomicReference<MinigameModuleRegistry> moduleReference = new AtomicReference<>(
                MinigameModuleRegistry.allUnavailable("A plataforma ainda está a iniciar."));
        SessionRecoveryService recovery = new SessionRecoveryService(
                sessionRepository, sessions, coordinator, audit, authentication, connections, clock,
                session -> {
                    var module = moduleReference.get().get(session.game());
                    if (module instanceof ColiseumModule arena) {
                        Player player = plugin.getServer().getPlayer(session.playerId());
                        if (player != null) arena.resumeAfterAuthentication(player);
                    } else {
                        module.shutdown();
                    }
                });
        recovery.loadBlockingSessions();
        registerCoreListeners(plugin, authentication, connections, sessions, combatPolicies,
                temporaryItems, regions, regionAdmissions, recovery, clock);
        if (arenaWorld != null) {
            arenaProjectiles = new ArenaProjectileLifecycleListener(plugin, arenaWorldLedger, ownedProjectiles,
                    arenaWorld, sessions, authentication, connections, regions, regionAdmissions, recovery, clock);
            plugin.getServer().getPluginManager().registerEvents(arenaProjectiles, plugin);
            arenaWorld.lifecycleReady();
        }
        assembledModules = Objects.requireNonNull(assembler.assemble(services), "modules");
        MinigameModuleRegistry readyModules = assembledModules;
        moduleReference.set(readyModules);

        RetentionConfiguration retentionConfiguration;
        try {
            retentionConfiguration = new RetentionConfigurationLoader().load(
                    new java.io.File(plugin.getDataFolder(), "retention.yml"));
        } catch (RuntimeException failure) {
            plugin.getLogger().severe("O Passaporte ficou fechado por configuração inválida: " + failure.getMessage());
            retentionConfiguration = RetentionConfiguration.disabledDefaults();
        }
        PassportService passportService;
        try {
            SqliteRetentionRepository retentionRepository = new SqliteRetentionRepository(database);
            passportService = new PassportService(retentionConfiguration,
                    retentionRepository, retentionConfiguration.rewards(), List.of(),
                    retentionRepository.initializeScoringStart(
                            new com.ciaac.minecraft.minigames.retention.LisbonSeasonCalendar(), clock.instant()));
        } catch (RuntimeException failure) {
            plugin.getLogger().severe("O Passaporte ficou fechado porque a persistência requer revisão: "
                    + failure.getClass().getSimpleName());
            passportService = new PassportService(RetentionConfiguration.disabledDefaults(),
                    new com.ciaac.minecraft.minigames.retention.InMemoryRetentionRepository(), List.of(), List.of());
        }
        passportRuntime = new PassportPaperRuntime(plugin, passportService, authentication, connections, sessions, clock);
        PassportPaperRuntime readyPassport = passportRuntime;

        boolean chatEvents = plugin.getConfig().getBoolean("security-events.public-chat-enabled", false);
        boolean privacyApproved = plugin.getConfig().getBoolean(
                "security-events.public-chat-privacy-notice-approved", false);
        if (chatEvents && privacyApproved) plugin.getServer().getPluginManager().registerEvents(
                new AuthenticatedPublicChatListener(plugin, authentication, clock), plugin);
        else if (chatEvents) plugin.getLogger().warning(
                "Os eventos de conversa pública ficaram fechados porque o aviso de privacidade não foi aprovado.");
        registerAuthentication(plugin, connections, authentication, player -> {
            Instant authenticatedAt = clock.instant();
            authentication.current(player.getUniqueId(), authenticatedAt).ifPresent(auth -> plugin.emit(
                    SecurityEvent.authenticatedPlayer(authenticatedAt, player.getUniqueId(), player.getName(),
                            auth.connectionId(), "PLAYER_AUTHENTICATED", SecurityEvent.Severity.INFO, "SUCCESS")));
            var recoveryResult = recovery.onAuthenticated(player);
            if (!"NO_RECOVERY_PENDING".equals(recoveryResult.code())) {
                authentication.current(player.getUniqueId(), clock.instant()).ifPresent(auth -> plugin.emit(
                        SecurityEvent.authenticatedPlayer(clock.instant(), player.getUniqueId(), player.getName(),
                                auth.connectionId(), "SESSION_RECOVERY",
                                recoveryResult.status() == com.ciaac.minecraft.minigames.runtime.AdmissionStatus.QUARANTINED
                                        ? SecurityEvent.Severity.HIGH : SecurityEvent.Severity.MEDIUM,
                                recoveryResult.code().replaceAll("[^A-Z0-9_.-]", "_"))));
            }
            if (mayRelocateArenaOccupant(recoveryResult, sessions, player.getUniqueId())
                    && readyModules.get(GameKey.ARENA) instanceof ColiseumModule arena
                    && !arena.relocateUnaffiliatedFloorOccupant(player)) {
                plugin.getLogger().severe("ARENA_AUTHENTICATED_EVICTION_FAILED");
                try {
                    player.kick(Component.text(
                            "Não foi possível sair da arena com segurança. Volta a entrar mais tarde."));
                } catch (RuntimeException kickFailure) {
                    plugin.getLogger().severe("ARENA_AUTHENTICATED_EVICTION_DISCONNECT_FAILED");
                }
                return;
            }
            readyPassport.onAuthenticated(player);
        }, clock);
        DisplayConfigLoadResult displayConfig = new NativeDisplayConfigLoader().load(
                sectionMap(plugin.getConfig().getConfigurationSection("displays")));
        displayConfig.diagnostics().forEach(diagnostic -> plugin.getLogger().warning(
                "A apresentação " + diagnostic.entryId() + " ficou fechada: " + diagnostic.code()));
        NativeDisplayController readyDisplays = new NativeDisplayController(
                plugin, plugin.getServer(), displayConfig.config(), readyModules, clock,
                displayConfig.diagnostics());
        displays = readyDisplays;
        plugin.getServer().getPluginManager().registerEvents(readyDisplays, plugin);
        readyDisplays.start();
        ModuleAnnouncementMonitor monitor = new ModuleAnnouncementMonitor(readyModules, announcementDispatcher);

        AtomicReference<MinigamePlatformRuntime> runtime = new AtomicReference<>();
        heartbeat = Objects.requireNonNull(plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            MinigamePlatformRuntime value = runtime.get();
            if (value == null) return;
            value.ticks++;
            for (var module : readyModules.all()) {
                try { module.tick(); }
                catch (RuntimeException failure) {
                    plugin.getLogger().severe("A atualização periódica do módulo ficou fechada para " + module.key().id()
                            + ": " + failure.getClass().getSimpleName());
                }
            }
            if (value.ticks % 20 == 0) {
                monitor.tick();
                readyDisplays.tick();
            }
        }, 1L, 1L), "heartbeat task");
        registerCommands(plugin, readyModules, statistics, clock);
        MinigamePlatformRuntime result = new MinigamePlatformRuntime(
                plugin, database, externalJournal, arenaWorldLedger, arenaProjectiles, readyModules, displays, heartbeat, passportRuntime,
                authentication, connections, clock);
        runtime.set(result);
        logIsolationReadiness(plugin, stateGateway);
        return result;
        } catch (RuntimeException | LinkageError failure) {
            if (passportRuntime != null) {
                try { passportRuntime.close(); }
                catch (RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
            }
            cleanupFailedStart(plugin, assembledModules, displays, heartbeat, database, externalJournal,
                    arenaWorldLedger, arenaProjectiles, failure);
            throw failure;
        }
    }

    public MinigameModuleRegistry modules() { return modules; }

    public Optional<SecurityEvent.Actor> authenticatedActor(Player player) {
        Objects.requireNonNull(player, "player");
        return authentication.current(player.getUniqueId(), clock.instant())
                .filter(auth -> connections.isCurrent(player, auth.connectionId()))
                .map(auth -> new SecurityEvent.Actor(
                        SecurityEvent.IdentityKind.AUTHENTICATED_UUID,
                        player.getUniqueId().toString(), player.getName(), true));
    }

    @Override
    public void close() {
        RuntimeException firstFailure = null;
        try {
            heartbeat.cancel();
        } catch (RuntimeException failure) {
            firstFailure = failure;
        }
        for (var module : modules.all()) {
            try { module.shutdown(); }
            catch (RuntimeException failure) {
                plugin.getLogger().severe("O encerramento do módulo requer revisão de recuperação: " + module.key().id());
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        try {
            displays.shutdown();
        } catch (RuntimeException failure) {
            if (firstFailure == null) firstFailure = failure;
            else firstFailure.addSuppressed(failure);
        }
        try {
            passport.close();
        } catch (RuntimeException failure) {
            if (firstFailure == null) firstFailure = failure;
            else firstFailure.addSuppressed(failure);
        }
        if (arenaProjectiles != null) {
            try { arenaProjectiles.close(); }
            catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        if (arenaWorldLedger != null) {
            try { arenaWorldLedger.close(); }
            catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        try {
            externalJournal.close();
        } catch (RuntimeException failure) {
            if (firstFailure == null) firstFailure = failure;
            else firstFailure.addSuppressed(failure);
        }
        try {
            database.close();
        } catch (RuntimeException failure) {
            if (firstFailure == null) firstFailure = failure;
            else firstFailure.addSuppressed(failure);
        }
        if (firstFailure != null) throw firstFailure;
    }

    private static void cleanupFailedStart(
            CiaacPlatformPlugin plugin,
            MinigameModuleRegistry modules,
            NativeDisplayController displays,
            BukkitTask heartbeat,
            SqliteDatabase database,
            ExternalOperationJournal externalJournal,
            ArenaWorldLedger arenaWorldLedger,
            ArenaProjectileLifecycleListener arenaProjectiles,
            Throwable original) {
        if (heartbeat != null) {
            try { heartbeat.cancel(); }
            catch (RuntimeException failure) { original.addSuppressed(failure); }
        }
        if (modules != null) {
            for (var module : modules.all()) {
                try { module.shutdown(); }
                catch (RuntimeException failure) { original.addSuppressed(failure); }
            }
        }
        if (displays != null) {
            try { displays.shutdown(); }
            catch (RuntimeException failure) { original.addSuppressed(failure); }
        }
        try {
            plugin.unregisterRuntimeListeners();
        } catch (RuntimeException failure) {
            original.addSuppressed(failure);
        }
        disableCommands(plugin, original);
        if (arenaProjectiles != null) {
            try { arenaProjectiles.close(); }
            catch (RuntimeException closeFailure) { original.addSuppressed(closeFailure); }
        }
        if (arenaWorldLedger != null) {
            try { arenaWorldLedger.close(); }
            catch (RuntimeException closeFailure) { original.addSuppressed(closeFailure); }
        }
        if (externalJournal != null) {
            try { externalJournal.close(); }
            catch (RuntimeException closeFailure) { original.addSuppressed(closeFailure); }
        }
        try {
            database.close();
        } catch (RuntimeException closeFailure) {
            original.addSuppressed(closeFailure);
        }
    }

    private static void disableCommands(CiaacPlatformPlugin plugin, Throwable original) {
        for (String name : COMMANDS) {
            PluginCommand command = plugin.getCommand(name);
            if (command == null) continue;
            try {
                command.setExecutor((sender, ignoredCommand, label, args) -> {
                    sender.sendMessage("§cOs minijogos estão temporariamente fechados.");
                    return true;
                });
                command.setTabCompleter((sender, ignoredCommand, alias, args) -> List.of());
            } catch (RuntimeException failure) {
                original.addSuppressed(failure);
            }
        }
    }

    private static List<FacetSnapshotHandler> baseHandlers(CiaacPlatformPlugin plugin, Runnable sharingPreflight) {
        List<FacetSnapshotHandler> handlers = new ArrayList<>();
        handlers.add(new InventoryFacetHandler());
        handlers.add(new VitalsFacetHandler());
        handlers.add(new MobilityFacetHandler(plugin.getServer(), sharingPreflight));
        handlers.add(new VanillaProgressFacetHandler(plugin.getServer()));
        handlers.add(new ScoreboardCooldownFacetHandler(plugin.getServer()));
        return handlers;
    }

    private static void registerCoreListeners(
            CiaacPlatformPlugin plugin,
            AuthenticationRegistry authentication,
            ConnectionRegistry connections,
            SessionRegistry sessions,
            CombatPolicyRegistry combatPolicies,
            TemporaryItemTagger temporaryItems,
            ProtectedRegionRegistry regions,
            RegionAdmissionRegistry regionAdmissions,
            SessionRecoveryService recovery,
            Clock clock) {
        var manager = plugin.getServer().getPluginManager();
        manager.registerEvents(new ConnectionLifecycleListener(connections, authentication, clock), plugin);
        manager.registerEvents(new SessionIsolationListener(
                plugin, sessions, authentication, combatPolicies, temporaryItems, recovery), plugin);
        manager.registerEvents(new ProgressSuppressionListener(sessions), plugin);
        manager.registerEvents(new RegionProtectionListener(
                regions, regionAdmissions, sessions, recovery, clock), plugin);
        for (Player player : plugin.getServer().getOnlinePlayers()) connections.begin(player, clock.instant());
    }

    private static void registerAuthentication(
            CiaacPlatformPlugin plugin,
            ConnectionRegistry connections,
            AuthenticationRegistry authentication,
            java.util.function.Consumer<Player> authenticatedHook,
            Clock clock) {
        var manager = plugin.getServer().getPluginManager();
        boolean nLogin = manager.isPluginEnabled("nLogin");
        boolean authMe = manager.isPluginEnabled("AuthMe");
        if (nLogin && authMe) {
            plugin.getLogger().severe("AUTHENTICATION_PROVIDERS_AMBIGUOUS: admissão e recuperação permanecem fechadas.");
            return;
        }
        if (authMe) {
            com.ciaac.minecraft.minigames.paper.auth.AuthMeCompletionBinding.register(
                    plugin, manager.getPlugin("AuthMe"), connections, authentication, clock, authenticatedHook);
            return;
        }
        if (!nLogin) {
            plugin.getLogger().warning("Nenhum fornecedor de autenticação suportado está disponível; as admissões permanecem fechadas.");
            return;
        }
        try {
            plugin.getServer().getPluginManager().registerEvents(
                    new NLoginAuthenticationListener(
                            plugin, connections, authentication, clock, Duration.ofHours(24),
                            authenticatedHook), plugin);
            plugin.getLogger().severe("NLOGIN_STATE_COMPLETION_UNAVAILABLE: a integração nLogin não dispõe de prova "
                    + "de conclusão do restauro. A admissão, o Passaporte e a recuperação autenticada permanecem fechados; "
                    + "as sessões pendentes e os snapshots são preservados.");
        } catch (LinkageError failure) {
            plugin.getLogger().severe("A API do nLogin é incompatível; todas as admissões aos minijogos permanecem fechadas.");
        }
    }

    static boolean mayRelocateArenaOccupant(
            AdmissionResult recoveryResult, SessionRegistry sessions, java.util.UUID playerId) {
        Objects.requireNonNull(recoveryResult, "recoveryResult");
        Objects.requireNonNull(sessions, "sessions");
        Objects.requireNonNull(playerId, "playerId");
        boolean recoverySafe = "NO_RECOVERY_PENDING".equals(recoveryResult.code())
                || recoveryResult.status() == AdmissionStatus.RECOVERED;
        return recoverySafe && sessions.findByPlayer(playerId).isEmpty();
    }

    private static void registerCommands(
            CiaacPlatformPlugin plugin,
            MinigameModuleRegistry modules,
            StatisticsRepository statistics,
            Clock clock) {
        List<PluginCommand> commands = new ArrayList<>(COMMANDS.size());
        for (String name : COMMANDS) {
            commands.add(Objects.requireNonNull(plugin.getCommand(name),
                    "Falta a declaração do comando " + name));
        }
        MinigamesCommand executor = new MinigamesCommand(
                modules, Optional.of(statistics), id -> playerName(plugin, id), clock,
                plugin::updaterOutcome);
        for (PluginCommand command : commands) {
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
    }

    private static String playerName(CiaacPlatformPlugin plugin, UUID id) {
        Player online = plugin.getServer().getPlayer(id);
        if (online != null) return online.getName();
        OfflinePlayer cached = plugin.getServer().getOfflinePlayer(id);
        return cached.getName();
    }

    private static Map<String, Object> sectionMap(ConfigurationSection section) {
        if (section == null) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            result.put(key, value instanceof ConfigurationSection child ? sectionMap(child) : value);
        }
        return Map.copyOf(result);
    }

    private static void logIsolationReadiness(
            CiaacPlatformPlugin plugin, CompositeBukkitPlayerStateGateway gateway) {
        Map<Set<PlayerStateFacet>, List<String>> missingByGames = new LinkedHashMap<>();
        for (GameKey game : GameKey.values()) {
            EnumSet<PlayerStateFacet> missing = EnumSet.allOf(PlayerStateFacet.class);
            missing.removeAll(gateway.supportedFacets(game));
            if (!missing.isEmpty()) missingByGames.computeIfAbsent(Set.copyOf(missing), ignored -> new ArrayList<>()).add(game.id());
        }
        missingByGames.forEach((missing, games) -> plugin.getLogger().warning(
                "O isolamento rigoroso está incompleto para " + games + "; a admissão desses jogos está fechada. Em falta: " + missing));
    }
}
