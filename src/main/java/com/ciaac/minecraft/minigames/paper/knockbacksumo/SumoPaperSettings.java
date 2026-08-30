package com.ciaac.minecraft.minigames.paper.knockbacksumo;

import java.time.Duration;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.Material;

/** Explicit operator-owned spawn geometry; an enabled controller needs all values. */
public record SumoPaperSettings(
        boolean enabled,
        String regionId,
        Location firstSpawn,
        Location secondSpawn,
        Duration tokenTtl,
        Material knockbackItem,
        int knockbackLevel,
        int fallThresholdY) {
    public SumoPaperSettings {
        if (regionId == null || !regionId.matches("[a-z0-9][a-z0-9_.-]{0,63}")) throw new IllegalArgumentException("invalid regionId");
        firstSpawn = requireLocation(firstSpawn, "firstSpawn"); secondSpawn = requireLocation(secondSpawn, "secondSpawn");
        tokenTtl = Objects.requireNonNull(tokenTtl, "tokenTtl");
        if (tokenTtl.isNegative() || tokenTtl.isZero()) throw new IllegalArgumentException("tokenTtl must be positive");
        knockbackItem = Objects.requireNonNull(knockbackItem, "knockbackItem");
        if (!knockbackItem.isItem()) throw new IllegalArgumentException("knockbackItem must be an item");
        if (knockbackLevel < 1 || knockbackLevel > 10) throw new IllegalArgumentException("knockbackLevel is invalid");
        if (fallThresholdY < -64 || fallThresholdY > 320) throw new IllegalArgumentException("fallThresholdY is invalid");
    }

    /** Compatibility constructor retained for bounded adapter fixtures. */
    public SumoPaperSettings(boolean enabled, String regionId, Location firstSpawn,
                             Location secondSpawn, Duration tokenTtl) {
        this(enabled, regionId, firstSpawn, secondSpawn, tokenTtl, Material.STICK, 2, 0);
    }

    public static SumoPaperSettings disabled(String regionId, Location first, Location second) {
        return new SumoPaperSettings(false, regionId, first, second, Duration.ofMinutes(10),
                Material.STICK, 2, 0);
    }
    private static Location requireLocation(Location value, String name) { Objects.requireNonNull(value, name); if (value.getWorld() == null) throw new IllegalArgumentException(name + " needs a world"); return value.clone(); }
}
