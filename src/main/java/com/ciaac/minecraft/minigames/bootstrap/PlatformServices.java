package com.ciaac.minecraft.minigames.bootstrap;

import com.ciaac.minecraft.minigames.announcement.AnnouncementDispatcher;
import com.ciaac.minecraft.minigames.configuration.RuntimeConfiguration;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.isolation.PlayerStateFacet;
import com.ciaac.minecraft.minigames.paper.TemporaryItemTagger;
import com.ciaac.minecraft.minigames.persistence.AuditRepository;
import com.ciaac.minecraft.minigames.persistence.SqliteDatabase;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.RegionAdmissionRegistry;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequestFactory;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.CombatPolicyRegistry;
import com.ciaac.minecraft.minigames.runtime.ConnectionRegistry;
import com.ciaac.minecraft.minigames.runtime.SessionCoordinator;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import com.ciaac.minecraft.minigames.statistics.StatisticsRepository;
import java.time.Clock;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import com.ciaac.minecraft.minigames.paper.isolation.AnvilHazardOwnership;
import com.ciaac.minecraft.minigames.paper.buildbattle.BuildBattleResetPort;
import java.util.Set;
import org.bukkit.plugin.Plugin;

/** Explicit dependency bundle for controller factories; contains no secrets or mutable world data. */
public record PlatformServices(
        Plugin plugin,
        RuntimeConfiguration configuration,
        Clock clock,
        SqliteDatabase database,
        AuthenticationRegistry authentication,
        ConnectionRegistry connections,
        AdmissionRequestFactory admissionRequests,
        SessionRegistry sessions,
        SessionCoordinator sessionCoordinator,
        ProtectedRegionRegistry regions,
        RegionAdmissionRegistry regionAdmissions,
        CombatPolicyRegistry combatPolicies,
        TemporaryItemTagger temporaryItems,
        StatisticsRepository statistics,
        AuditRepository audit,
        AnnouncementDispatcher announcements,
        IsolationPolicy isolationPolicy,
        Map<GameKey, Set<PlayerStateFacet>> supportedIsolationFacetsByGame,
        Optional<AnvilHazardOwnership> anvilHazards,
        Optional<BuildBattleResetPort> buildBattleReset) {
    public PlatformServices {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(authentication, "authentication");
        Objects.requireNonNull(connections, "connections");
        Objects.requireNonNull(admissionRequests, "admissionRequests");
        Objects.requireNonNull(sessions, "sessions");
        Objects.requireNonNull(sessionCoordinator, "sessionCoordinator");
        Objects.requireNonNull(regions, "regions");
        Objects.requireNonNull(regionAdmissions, "regionAdmissions");
        Objects.requireNonNull(combatPolicies, "combatPolicies");
        Objects.requireNonNull(temporaryItems, "temporaryItems");
        Objects.requireNonNull(statistics, "statistics");
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(announcements, "announcements");
        Objects.requireNonNull(isolationPolicy, "isolationPolicy");
        Objects.requireNonNull(supportedIsolationFacetsByGame, "supportedIsolationFacetsByGame");
        anvilHazards = Objects.requireNonNull(anvilHazards, "anvilHazards");
        buildBattleReset = Objects.requireNonNull(buildBattleReset, "buildBattleReset");
        EnumMap<GameKey, Set<PlayerStateFacet>> supported = new EnumMap<>(GameKey.class);
        for (GameKey game : GameKey.values()) {
            supported.put(game, Set.copyOf(supportedIsolationFacetsByGame.getOrDefault(game, Set.of())));
        }
        supportedIsolationFacetsByGame = Map.copyOf(supported);
    }

    /** Existing callers do not acquire authority to reset native plots by listing facets. */
    public PlatformServices(Plugin plugin, RuntimeConfiguration configuration, Clock clock,
            SqliteDatabase database, AuthenticationRegistry authentication, ConnectionRegistry connections,
            AdmissionRequestFactory admissionRequests, SessionRegistry sessions, SessionCoordinator sessionCoordinator,
            ProtectedRegionRegistry regions, RegionAdmissionRegistry regionAdmissions, CombatPolicyRegistry combatPolicies,
            TemporaryItemTagger temporaryItems, StatisticsRepository statistics, AuditRepository audit,
            AnnouncementDispatcher announcements, IsolationPolicy isolationPolicy,
            Map<GameKey, Set<PlayerStateFacet>> supportedIsolationFacetsByGame, Optional<AnvilHazardOwnership> anvilHazards) {
        this(plugin, configuration, clock, database, authentication, connections, admissionRequests, sessions,
                sessionCoordinator, regions, regionAdmissions, combatPolicies, temporaryItems, statistics, audit,
                announcements, isolationPolicy, supportedIsolationFacetsByGame, anvilHazards, Optional.empty());
    }

    /** Existing callers do not gain native marker ownership merely by supplying facet names. */
    public PlatformServices(Plugin plugin, RuntimeConfiguration configuration, Clock clock,
            SqliteDatabase database, AuthenticationRegistry authentication, ConnectionRegistry connections,
            AdmissionRequestFactory admissionRequests, SessionRegistry sessions, SessionCoordinator sessionCoordinator,
            ProtectedRegionRegistry regions, RegionAdmissionRegistry regionAdmissions, CombatPolicyRegistry combatPolicies,
            TemporaryItemTagger temporaryItems, StatisticsRepository statistics, AuditRepository audit,
            AnnouncementDispatcher announcements, IsolationPolicy isolationPolicy,
            Map<GameKey, Set<PlayerStateFacet>> supportedIsolationFacetsByGame) {
        this(plugin, configuration, clock, database, authentication, connections, admissionRequests, sessions,
                sessionCoordinator, regions, regionAdmissions, combatPolicies, temporaryItems, statistics, audit,
                announcements, isolationPolicy, supportedIsolationFacetsByGame, Optional.empty());
    }

    /** Source-compatible constructor for callers whose facet catalog is shared by every game. */
    public PlatformServices(
            Plugin plugin,
            RuntimeConfiguration configuration,
            Clock clock,
            SqliteDatabase database,
            AuthenticationRegistry authentication,
            ConnectionRegistry connections,
            AdmissionRequestFactory admissionRequests,
            SessionRegistry sessions,
            SessionCoordinator sessionCoordinator,
            ProtectedRegionRegistry regions,
            RegionAdmissionRegistry regionAdmissions,
            CombatPolicyRegistry combatPolicies,
            TemporaryItemTagger temporaryItems,
            StatisticsRepository statistics,
            AuditRepository audit,
            AnnouncementDispatcher announcements,
            IsolationPolicy isolationPolicy,
            Set<PlayerStateFacet> supportedIsolationFacets) {
        this(plugin, configuration, clock, database, authentication, connections, admissionRequests, sessions,
                sessionCoordinator, regions, regionAdmissions, combatPolicies, temporaryItems, statistics, audit,
                announcements, isolationPolicy, allGames(supportedIsolationFacets));
    }

    /** True only when every strict no-progress facet has a concrete adapter. */
    public boolean isolationReady() {
        return java.util.Arrays.stream(GameKey.values()).allMatch(this::isolationReady);
    }

    public boolean isolationReady(GameKey game) {
        return supportedIsolationFacetsByGame.get(Objects.requireNonNull(game, "game"))
                .containsAll(isolationPolicy.protectedFacets());
    }

    /** Union retained for diagnostics and source compatibility with the former shared catalog. */
    public Set<PlayerStateFacet> supportedIsolationFacets() {
        EnumSet<PlayerStateFacet> supported = EnumSet.noneOf(PlayerStateFacet.class);
        supportedIsolationFacetsByGame.values().forEach(supported::addAll);
        return Set.copyOf(supported);
    }

    private static Map<GameKey, Set<PlayerStateFacet>> allGames(Set<PlayerStateFacet> facets) {
        Set<PlayerStateFacet> immutable = Set.copyOf(Objects.requireNonNull(facets, "supportedIsolationFacets"));
        EnumMap<GameKey, Set<PlayerStateFacet>> supported = new EnumMap<>(GameKey.class);
        for (GameKey game : GameKey.values()) supported.put(game, immutable);
        return supported;
    }
}
