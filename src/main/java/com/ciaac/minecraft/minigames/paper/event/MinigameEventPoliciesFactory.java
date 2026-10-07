package com.ciaac.minecraft.minigames.paper.event;

import com.ciaac.minecraft.minigames.buildbattle.BuildBattlePlot;
import com.ciaac.minecraft.minigames.bootstrap.PlatformServices;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.module.MinigameModuleRegistry;
import com.ciaac.minecraft.minigames.paper.configuration.ConfigurationResolutionResult;
import com.ciaac.minecraft.minigames.paper.configuration.ResolvedAnvilDodgeConfiguration;
import com.ciaac.minecraft.minigames.paper.configuration.ResolvedArcheryConfiguration;
import com.ciaac.minecraft.minigames.paper.configuration.ResolvedGameConfiguration;
import com.ciaac.minecraft.minigames.paper.configuration.ResolvedParkourConfiguration;
import com.ciaac.minecraft.minigames.paper.module.ArcheryModule;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.runtime.SessionPhase;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/** Creates immutable, server-resolved geometry and identity policies for the Bukkit event router. */
public final class MinigameEventPoliciesFactory {
    private static final String ARCHERY_TARGET_TAG = "ciaac-archery-target:";

    private MinigameEventPoliciesFactory() { }

    public static MinigameEventRouter.Policies create(
            MinigameModuleRegistry modules,
            ConfigurationResolutionResult resolved,
            PlatformServices services) {
        Objects.requireNonNull(modules, "modules");
        Objects.requireNonNull(resolved, "resolved");
        Objects.requireNonNull(services, "services");

        ResolvedParkourConfiguration parkour = configuration(
                resolved, GameKey.CHECKPOINT_PARKOUR, ResolvedParkourConfiguration.class).orElse(null);
        ResolvedArcheryConfiguration archery = configuration(
                resolved, GameKey.ARCHERY_RANGE, ResolvedArcheryConfiguration.class).orElse(null);
        ResolvedAnvilDodgeConfiguration anvil = configuration(
                resolved, GameKey.ANVIL_DODGE, ResolvedAnvilDodgeConfiguration.class).orElse(null);
        ArcheryModule archeryModule = typed(modules, GameKey.ARCHERY_RANGE, ArcheryModule.class);

        return new MinigameEventRouter.Policies(
                (player, destination) -> parkour != null
                        && contains(parkour.regions().get("course-boundary"), destination),
                (player, destination) -> containsArcheryLane(archeryModule, archery, player.getUniqueId(), destination),
                scope(services, GameKey.KNOCKBACK_SUMO),
                (player, destination) -> checkpoint(parkour, destination),
                (shooter, event) -> archeryHit(archeryModule, archery, shooter.getUniqueId(),
                        event.getHitEntity()),
                scope(services, GameKey.BUILD_BATTLE),
                (voter, entity, block, action) -> Optional.empty(),
                MinigameEventPoliciesFactory::buildBattleVote,
                scope(services, GameKey.HOT_POTATO),
                scope(services, GameKey.ANVIL_DODGE),
                (player, destination) -> anvilCell(anvil, destination),
                event -> false);
    }

    private static MinigameEventRouter.PlayerScope scope(PlatformServices services, GameKey game) {
        return player -> services.sessions().findByPlayer(player.getUniqueId())
                .filter(session -> session.game() == game && session.phase() != SessionPhase.CLOSED)
                .isPresent();
    }

    private static OptionalInt checkpoint(ResolvedParkourConfiguration configuration, Location location) {
        if (configuration == null || location == null) return OptionalInt.empty();
        List<String> order = configuration.checkpointOrder();
        for (int index = 0; index < order.size(); index++) {
            CuboidRegion region = configuration.checkpointRegions().get(order.get(index));
            if (contains(region, location)) return OptionalInt.of(index);
        }
        return OptionalInt.empty();
    }

    private static boolean containsArcheryLane(
            ArcheryModule module,
            ResolvedArcheryConfiguration configuration,
            java.util.UUID playerId,
            Location destination) {
        if (module == null || configuration == null) return false;
        OptionalInt lane = module.laneId(playerId);
        if (lane.isEmpty()) return false;
        ResolvedArcheryConfiguration.LaneDefinition definition = configuration.lanes().get(lane.getAsInt());
        return definition != null && contains(configuration.regions().get(definition.regionId()), destination);
    }

    /**
     * Target hitboxes are operator-owned entities with one exact scoreboard tag:
     * {@code ciaac-archery-target:<configured-target-id>:<score-band>}.
     */
    private static Optional<MinigameEventRouter.ArcheryHit> archeryHit(
            ArcheryModule module,
            ResolvedArcheryConfiguration configuration,
            java.util.UUID playerId,
            Entity hitEntity) {
        if (module == null || configuration == null || hitEntity == null) return Optional.empty();
        OptionalInt laneId = module.laneId(playerId);
        if (laneId.isEmpty()) return Optional.empty();
        ResolvedArcheryConfiguration.LaneDefinition lane = configuration.lanes().get(laneId.getAsInt());
        if (lane == null || !contains(configuration.regions().get(lane.regionId()), hitEntity.getLocation())) {
            return Optional.empty();
        }
        String prefix = ARCHERY_TARGET_TAG + lane.targetId() + ":";
        String band = hitEntity.getScoreboardTags().stream()
                .filter(tag -> tag.startsWith(prefix))
                .map(tag -> tag.substring(prefix.length()))
                .filter(configuration.scoreBands()::containsKey)
                .sorted()
                .findFirst().orElse(null);
        if (band == null) return Optional.empty();
        int points = configuration.scoreBands().get(band);
        module.controller().registerTarget(
                hitEntity.getUniqueId(), lane.id(), lane.targetId(), band);
        return Optional.of(new MinigameEventRouter.ArcheryHit(
                lane.id(), points, band.equals("bullseye")));
    }

    private static Optional<MinigameEventRouter.BuildBattleVote> buildBattleVote(
            org.bukkit.entity.Player voter,
            String rawCommand) {
        if (rawCommand == null || rawCommand.length() > 256) return Optional.empty();
        String command = rawCommand.trim();
        if (command.startsWith("/")) command = command.substring(1).trim();
        String[] parts = command.split("\\s+");
        if (parts.length != 4
                || !(parts[0].equalsIgnoreCase("buildbattle") || parts[0].equalsIgnoreCase("bb"))) {
            return Optional.empty();
        }
        String action = parts[1].toLowerCase(Locale.ROOT);
        if (!(action.equals("avaliar") || action.equals("votar") || action.equals("vote"))
                || !parts[2].matches("[a-z0-9][a-z0-9_.-]{0,31}")) {
            return Optional.empty();
        }
        try {
            int score = Integer.parseInt(parts[3]);
            if (score < 0 || score > 100) return Optional.empty();
            return Optional.of(new MinigameEventRouter.BuildBattleVote(
                    new BuildBattlePlot(parts[2]), score));
        } catch (NumberFormatException invalid) {
            return Optional.empty();
        }
    }

    private static OptionalInt anvilCell(ResolvedAnvilDodgeConfiguration configuration, Location location) {
        if (configuration == null || location == null) return OptionalInt.empty();
        CuboidRegion floor = configuration.regions().get("floor");
        if (!contains(floor, location)) return OptionalInt.empty();
        int x = location.getBlockX() - floor.minX();
        int z = location.getBlockZ() - floor.minZ();
        int cell = z * configuration.floorWidth() + x;
        return cell < 0 || cell >= configuration.floorWidth() * configuration.floorDepth()
                ? OptionalInt.empty() : OptionalInt.of(cell);
    }

    private static boolean contains(CuboidRegion region, Location location) {
        return region != null && location != null && region.contains(location);
    }

    private static <T extends ResolvedGameConfiguration> Optional<T> configuration(
            ConfigurationResolutionResult resolved, GameKey game, Class<T> type) {
        return resolved.module(game).flatMap(module -> module.gameConfiguration())
                .filter(type::isInstance).map(type::cast);
    }

    private static <T> T typed(MinigameModuleRegistry modules, GameKey game, Class<T> type) {
        Object module = modules.get(game);
        return type.isInstance(module) ? type.cast(module) : null;
    }
}
