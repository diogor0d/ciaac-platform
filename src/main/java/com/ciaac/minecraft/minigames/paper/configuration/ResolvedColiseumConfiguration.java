package com.ciaac.minecraft.minigames.paper.configuration;

import com.ciaac.minecraft.minigames.arena.ArenaFormat;
import com.ciaac.minecraft.minigames.arena.ArenaFormatPolicy;
import com.ciaac.minecraft.minigames.arena.ArenaKitMode;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

/** Resolved Coliseum rules and immutable named geometry. */
public record ResolvedColiseumConfiguration(
        World world,
        Map<String, Location> locations,
        Map<String, CuboidRegion> regions,
        String rulesetRevision,
        IsolationPolicy isolation,
        int minimumPlayers,
        ArenaFormatPolicy formatPolicy,
        Set<ArenaKitMode> kitModes,
        Map<String, List<Material>> fixedKits,
        Set<Material> protectedProhibitedMaterials,
        boolean friendlyFire,
        Duration roundDuration,
        Duration reconnectGrace,
        boolean stakedSurvivalEnabled,
        Set<ArenaFormat> stakedAllowedFormats,
        Set<Material> stakedProhibitedMaterials,
        Duration stakeConsentTimeout,
        String noContestPolicy) implements ResolvedGameConfiguration {

    public ResolvedColiseumConfiguration {
        world = Objects.requireNonNull(world, "world");
        locations = ResolvedConfigurationSupport.locations(locations);
        regions = ResolvedConfigurationSupport.regions(regions);
        rulesetRevision = ResolvedConfigurationSupport.text(rulesetRevision, "rulesetRevision", 32);
        isolation = Objects.requireNonNull(isolation, "isolation");
        if (minimumPlayers < 2 || minimumPlayers > 64) throw new IllegalArgumentException("minimumPlayers is invalid");
        formatPolicy = Objects.requireNonNull(formatPolicy, "formatPolicy");
        kitModes = Set.copyOf(Objects.requireNonNull(kitModes, "kitModes"));
        fixedKits = ResolvedConfigurationSupport.materialLists(fixedKits);
        protectedProhibitedMaterials = Set.copyOf(Objects.requireNonNull(protectedProhibitedMaterials, "protectedProhibitedMaterials"));
        roundDuration = ResolvedConfigurationSupport.positive(roundDuration, "roundDuration");
        reconnectGrace = ResolvedConfigurationSupport.nonNegative(reconnectGrace, "reconnectGrace");
        stakedAllowedFormats = Set.copyOf(Objects.requireNonNull(stakedAllowedFormats, "stakedAllowedFormats"));
        stakedProhibitedMaterials = Set.copyOf(Objects.requireNonNull(stakedProhibitedMaterials, "stakedProhibitedMaterials"));
        stakeConsentTimeout = ResolvedConfigurationSupport.positive(stakeConsentTimeout, "stakeConsentTimeout");
        noContestPolicy = ResolvedConfigurationSupport.text(noContestPolicy, "noContestPolicy", 64);
    }

    @Override public GameKey game() { return GameKey.ARENA; }
}
