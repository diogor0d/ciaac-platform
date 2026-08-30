package com.ciaac.minecraft.minigames.bootstrap;

import com.ciaac.minecraft.minigames.announcement.AnnouncementDispatcher;
import com.ciaac.minecraft.minigames.configuration.RuntimeConfiguration;
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
import java.util.Objects;
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
        Set<PlayerStateFacet> supportedIsolationFacets) {
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
        supportedIsolationFacets = Set.copyOf(Objects.requireNonNull(
                supportedIsolationFacets, "supportedIsolationFacets"));
    }

    /** True only when every strict no-progress facet has a concrete adapter. */
    public boolean isolationReady() {
        return supportedIsolationFacets.containsAll(isolationPolicy.protectedFacets());
    }
}
