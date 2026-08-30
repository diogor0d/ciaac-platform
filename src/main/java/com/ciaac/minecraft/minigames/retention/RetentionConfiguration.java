package com.ciaac.minecraft.minigames.retention;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Validated, Paper-neutral Passport configuration. Bukkit configuration wiring belongs to bootstrap. */
public record RetentionConfiguration(
        boolean enabled,
        ZoneId timezone,
        LocalDate seasonAnchor,
        int seasonMonths,
        int claimGraceDays,
        Duration joinQualification,
        int activeSampleMinutes,
        int weeklyActiveDays,
        int detailRetentionMonths,
        boolean placeholdersEnabled,
        boolean safezoneDisplaysEnabled,
        boolean particlesEnabled,
        List<RewardDescriptor> rewards) {
    public RetentionConfiguration {
        timezone = Objects.requireNonNull(timezone, "timezone");
        seasonAnchor = Objects.requireNonNull(seasonAnchor, "seasonAnchor");
        joinQualification = Objects.requireNonNull(joinQualification, "joinQualification");
        rewards = List.copyOf(Objects.requireNonNull(rewards, "rewards"));
        if (!timezone.equals(LisbonSeasonCalendar.LISBON) || !seasonAnchor.equals(LisbonSeasonCalendar.ANCHOR)
                || seasonMonths != 3 || claimGraceDays != 14) {
            throw new IllegalArgumentException("Passport seasons are fixed to the approved Lisbon calendar");
        }
        if (!joinQualification.equals(Duration.ofMinutes(10)) || activeSampleMinutes != 15
                || weeklyActiveDays != 3 || detailRetentionMonths != 12) {
            throw new IllegalArgumentException("Passport qualification and retention policy is invalid");
        }
        if (rewards.stream().map(RewardDescriptor::id).distinct().count() != rewards.size()) {
            throw new IllegalArgumentException("reward ids are duplicated");
        }
    }

    public static RetentionConfiguration disabledDefaults() {
        return new RetentionConfiguration(false, LisbonSeasonCalendar.LISBON, LisbonSeasonCalendar.ANCHOR,
                3, 14, Duration.ofMinutes(10), 15, 3, 12, false, false, false, List.of());
    }

    /** Loader input deliberately uses a bounded primitive map, making it testable without Bukkit. */
    public static RetentionConfiguration fromMap(Map<String, ?> values, List<RewardDescriptor> rewards) {
        Objects.requireNonNull(values, "values");
        Object enabled = values.containsKey("enabled") ? values.get("enabled") : Boolean.FALSE;
        if (!(enabled instanceof Boolean value)) throw new IllegalArgumentException("retention.enabled must be boolean");
        return new RetentionConfiguration(value, LisbonSeasonCalendar.LISBON, LisbonSeasonCalendar.ANCHOR,
                3, 14, Duration.ofMinutes(10), 15, 3, 12,
                booleanValue(values, "placeholders-enabled"), booleanValue(values, "safezone-displays-enabled"),
                booleanValue(values, "particles-enabled"), rewards);
    }

    private static boolean booleanValue(Map<String, ?> values, String key) {
        Object value = values.containsKey(key) ? values.get(key) : Boolean.FALSE;
        if (!(value instanceof Boolean result)) throw new IllegalArgumentException("retention." + key + " must be boolean");
        return result;
    }
}
