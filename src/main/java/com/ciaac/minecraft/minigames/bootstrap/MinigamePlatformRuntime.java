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
import com.ciaac.minecraft.minigames.paper.isolation.ExternalStateFacetHandler;
import com.ciaac.minecraft.minigames.paper.isolation.ExternalStateFacetPort;
import com.ciaac.minecraft.minigames.paper.isolation.FacetSnapshotHandler;
import com.ciaac.minecraft.minigames.paper.isolation.InventoryFacetHandler;
import com.ciaac.minecraft.minigames.paper.isolation.MobilityFacetHandler;
import com.ciaac.minecraft.minigames.paper.isolation.ScoreboardCooldownFacetHandler;
import com.ciaac.minecraft.minigames.paper.isolation.VanillaProgressFacetHandler;
import com.ciaac.minecraft.minigames.paper.isolation.VitalsFacetHandler;
import com.ciaac.minecraft.minigames.paper.recovery.SessionRecoveryService;
import com.ciaac.minecraft.minigames.persistence.AuditRepository;
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
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.scheduler.BukkitTask;

/** Owns all shared runtime resources and keeps construction failure fail-closed. */
public final class MinigamePlatformRuntime implements AutoCloseable {
    private static final List<String> COMMANDS = List.of(
            "minijogos", "coliseu", "buildbattle", "batataquente", "sumo",
            "parkour", "arco", "bigornas", "cores", "elytra");
    private final CiaacPlatformPlugin plugin;
    private final SqliteDatabase database;
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
            MinigameModuleRegistry modules,
            NativeDisplayController displays,
            BukkitTask heartbeat,
            PassportPaperRuntime passport,
            AuthenticationRegistry authentication,
            ConnectionRegistry connections,
            Clock clock) {
        this.plugin = plugin;
        this.database = database;
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
        try {
        SnapshotEnvelopeCodec snapshotCodec = new SnapshotEnvelopeCodec();
        SessionRepository sessionRepository = new SqliteSessionRepository(database);
        var snapshotRepository = new SqliteSnapshotRepository(database, snapshotCodec);
        AuditRepository audit = new SqliteAuditRepository(database);
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
        List<FacetSnapshotHandler> handlers = baseHandlers(plugin);
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
        CompositeBukkitPlayerStateGateway stateGateway =
                new CompositeBukkitPlayerStateGateway(plugin.getServer(), handlers);
        SessionCoordinator coordinator = new SessionCoordinator(
                authentication, sessions, sessionRepository, snapshotRepository, stateGateway,
                isolation, snapshotCodec, clock);

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
        PlatformServices services = new PlatformServices(
                plugin, configuration, clock, database, authentication, connections,
                new AdmissionRequestFactory(connections, clock), sessions, coordinator, regions,
                regionAdmissions, combatPolicies, temporaryItems, statistics, audit,
                announcementDispatcher, isolation, stateGateway.supportedFacets());

        AtomicReference<MinigameModuleRegistry> moduleReference = new AtomicReference<>(
                MinigameModuleRegistry.allUnavailable("A plataforma ainda está a iniciar."));
        SessionRecoveryService recovery = new SessionRecoveryService(
                sessionRepository, sessions, coordinator, audit, clock,
                session -> moduleReference.get().get(session.game()).shutdown());
        recovery.loadBlockingSessions();
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

        registerCoreListeners(plugin, authentication, connections, sessions, combatPolicies,
                temporaryItems, regions, regionAdmissions, recovery, clock);
        boolean chatEvents = plugin.getConfig().getBoolean("security-events.public-chat-enabled", false);
        boolean privacyApproved = plugin.getConfig().getBoolean(
                "security-events.public-chat-privacy-notice-approved", false);
        if (chatEvents && privacyApproved) plugin.getServer().getPluginManager().registerEvents(
                new AuthenticatedPublicChatListener(plugin, authentication, clock), plugin);
        else if (chatEvents) plugin.getLogger().warning(
                "Os eventos de conversa pública ficaram fechados porque o aviso de privacidade não foi aprovado.");
        registerNLogin(plugin, connections, authentication, player -> {
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
                plugin, database, readyModules, displays, heartbeat, passportRuntime,
                authentication, connections, clock);
        runtime.set(result);
        logIsolationReadiness(plugin, stateGateway.supportedFacets());
        return result;
        } catch (RuntimeException | LinkageError failure) {
            if (passportRuntime != null) passportRuntime.close();
            cleanupFailedStart(plugin, assembledModules, displays, heartbeat, database, failure);
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
            HandlerList.unregisterAll(plugin);
        } catch (RuntimeException failure) {
            original.addSuppressed(failure);
        }
        disableCommands(plugin, original);
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

    private static List<FacetSnapshotHandler> baseHandlers(CiaacPlatformPlugin plugin) {
        List<FacetSnapshotHandler> handlers = new ArrayList<>();
        handlers.add(new InventoryFacetHandler());
        handlers.add(new VitalsFacetHandler());
        handlers.add(new MobilityFacetHandler(plugin.getServer()));
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
                sessions, authentication, combatPolicies, temporaryItems, recovery), plugin);
        manager.registerEvents(new ProgressSuppressionListener(sessions), plugin);
        manager.registerEvents(new RegionProtectionListener(
                regions, regionAdmissions, sessions, recovery, clock), plugin);
        for (Player player : plugin.getServer().getOnlinePlayers()) connections.begin(player, clock.instant());
    }

    private static void registerNLogin(
            CiaacPlatformPlugin plugin,
            ConnectionRegistry connections,
            AuthenticationRegistry authentication,
            java.util.function.Consumer<Player> authenticatedHook,
            Clock clock) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("nLogin")) {
            plugin.getLogger().warning("O nLogin não está disponível; todas as admissões aos minijogos permanecem fechadas.");
            return;
        }
        try {
            plugin.getServer().getPluginManager().registerEvents(
                    new NLoginAuthenticationListener(
                            plugin, connections, authentication, clock, Duration.ofHours(24),
                            authenticatedHook), plugin);
        } catch (LinkageError failure) {
            plugin.getLogger().severe("A API do nLogin é incompatível; todas as admissões aos minijogos permanecem fechadas.");
        }
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
            CiaacPlatformPlugin plugin, Set<PlayerStateFacet> supported) {
        EnumSet<PlayerStateFacet> missing = EnumSet.allOf(PlayerStateFacet.class);
        missing.removeAll(supported);
        if (!missing.isEmpty()) {
            plugin.getLogger().warning("O isolamento rigoroso de progresso está incompleto; a admissão está fechada. Em falta: "
                    + missing);
        }
    }
}
