package com.ciaac.minecraft.minigames.paper.archeryrange;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;

/** Read-only readiness check over entities Paper currently exposes in the lane world. */
public final class PaperArcheryTargetProbe {
    private static final String TARGET_TAG_PREFIX = "ciaac-archery-target:";
    private static final Set<String> REQUIRED_BANDS = Set.of("bullseye", "inner", "middle", "outer");

    private PaperArcheryTargetProbe() { }

    /**
     * Returns true only when every configured score band has a live, non-marker armor stand
     * tagged for this lane inside its immutable participant region. This method never loads chunks
     * or changes entities.
     */
    public static boolean ready(
            ArcheryPaperSettings.LaneSettings lane,
            ProtectedRegion region,
            Map<String, Integer> scores) {
        if (lane == null || region == null || scores == null
                || !scores.keySet().equals(REQUIRED_BANDS)
                || !region.id().equals(lane.regionId())
                || region.game() != GameKey.ARCHERY_RANGE
                || region.role() != ProtectedRegionRole.PARTICIPANT_ONLY
                || !region.immutable()) {
            return false;
        }

        Location spawn = lane.spawn();
        World world = spawn.getWorld();
        if (world == null || !region.bounds().worldId().equals(world.getUID())
                || !region.bounds().contains(spawn)) {
            return false;
        }
        var worldId = region.bounds().worldId();

        String targetPrefix = TARGET_TAG_PREFIX + lane.targetId() + ":";
        Set<String> coveredBands = new HashSet<>();
        for (Entity entity : world.getEntities()) {
            if (entity == null) return false;
            Set<String> targetTags = Objects.requireNonNull(entity.getScoreboardTags(), "entity scoreboard tags")
                    .stream()
                    .filter(tag -> tag.startsWith(targetPrefix))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (targetTags.isEmpty()) continue;

            Location location = entity.getLocation();
            if (entity.getWorld() == null || !worldId.equals(entity.getWorld().getUID())
                    || location == null || location.getWorld() == null
                    || !worldId.equals(location.getWorld().getUID())
                    || !region.bounds().contains(location)) {
                return false;
            }
            if (!(entity instanceof ArmorStand stand) || !stand.isValid() || stand.isDead() || stand.isMarker()) {
                return false;
            }

            Set<String> bands = targetTags.stream()
                    .map(tag -> tag.substring(targetPrefix.length()))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            if (bands.size() != 1 || !REQUIRED_BANDS.containsAll(bands)) return false;
            coveredBands.add(bands.iterator().next());
        }
        return coveredBands.containsAll(REQUIRED_BANDS);
    }
}
