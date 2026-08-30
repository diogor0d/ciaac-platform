package com.ciaac.minecraft.minigames.configuration;

import java.time.Duration;

public record AnnouncementConfiguration(
        Duration minecraftWaitingCooldown,
        boolean discordEnabled,
        String discordChannelName,
        Duration discordWaitingCooldown,
        boolean announceStarting,
        boolean announceWinner) {

    public AnnouncementConfiguration {
        if (minecraftWaitingCooldown == null || minecraftWaitingCooldown.isNegative()
                || discordWaitingCooldown == null || discordWaitingCooldown.isNegative()) {
            throw new IllegalArgumentException("Announcement cooldowns must not be negative");
        }
        if (discordChannelName == null
                || !discordChannelName.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("Discord logical channel name is invalid");
        }
    }
}
