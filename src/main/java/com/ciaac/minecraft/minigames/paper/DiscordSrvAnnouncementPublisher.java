package com.ciaac.minecraft.minigames.paper;

import com.ciaac.minecraft.minigames.announcement.Announcement;
import com.ciaac.minecraft.minigames.announcement.AnnouncementDestination;
import com.ciaac.minecraft.minigames.announcement.AnnouncementPublishResult;
import com.ciaac.minecraft.minigames.announcement.AnnouncementPublisher;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

/** Optional, reflection-bounded DiscordSRV 1.30.x outbound adapter. */
public final class DiscordSrvAnnouncementPublisher implements AnnouncementPublisher {
    private final Server server;
    private final String logicalChannelName;

    public DiscordSrvAnnouncementPublisher(Server server, String logicalChannelName) {
        this.server = Objects.requireNonNull(server, "server");
        if (logicalChannelName == null || !logicalChannelName.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("logicalChannelName is invalid");
        }
        this.logicalChannelName = logicalChannelName;
    }

    @Override
    public AnnouncementDestination destination() {
        return AnnouncementDestination.DISCORD;
    }

    @Override
    public AnnouncementPublishResult publish(Announcement announcement) {
        Plugin plugin = server.getPluginManager().getPlugin("DiscordSRV");
        if (plugin == null || !plugin.isEnabled()) {
            return AnnouncementPublishResult.failed("DISCORDSRV_UNAVAILABLE");
        }
        try {
            ClassLoader loader = plugin.getClass().getClassLoader();
            Class<?> discordSrv = Class.forName("github.scarsz.discordsrv.DiscordSRV", true, loader);
            Object discordPlugin = discordSrv.getMethod("getPlugin").invoke(null);
            Object channel = discordSrv
                    .getMethod("getDestinationTextChannelForGameChannelName", String.class)
                    .invoke(discordPlugin, logicalChannelName);
            if (channel == null) {
                return AnnouncementPublishResult.failed("DISCORD_CHANNEL_UNAVAILABLE");
            }
            Class<?> discordUtil = Class.forName("github.scarsz.discordsrv.util.DiscordUtil", true, loader);
            Method send = null;
            for (Method method : discordUtil.getMethods()) {
                if (Modifier.isStatic(method.getModifiers())
                        && method.getName().equals("sendMessage")
                        && method.getParameterCount() == 2
                        && method.getParameterTypes()[1] == String.class
                        && method.getParameterTypes()[0].isInstance(channel)) {
                    send = method;
                    break;
                }
            }
            if (send == null) {
                return AnnouncementPublishResult.failed("DISCORDSRV_API_INCOMPATIBLE");
            }
            send.invoke(null, channel, suppressMentions(announcement.plainTextPtPt()));
            return AnnouncementPublishResult.delivered("DISCORDSRV_SENT");
        } catch (ReflectiveOperationException | LinkageError exception) {
            return AnnouncementPublishResult.failed("DISCORDSRV_API_INCOMPATIBLE");
        }
    }

    private static String suppressMentions(String message) {
        String bounded = message.length() <= 500 ? message : message.substring(0, 500);
        return bounded
                .replace("@everyone", "@\u200Beveryone")
                .replace("@here", "@\u200Bhere")
                .replace("<@", "<@\u200B");
    }
}
