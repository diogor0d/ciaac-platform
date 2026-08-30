package com.ciaac.minecraft.platform.securityevents;

import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.platform.CiaacPlatformPlugin;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.time.Clock;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Optional local public-chat event source. It never observes pre-authentication or private messages. */
public final class AuthenticatedPublicChatListener implements Listener {
    private final CiaacPlatformPlugin plugin;
    private final AuthenticationRegistry authentication;
    private final Clock clock;

    public AuthenticatedPublicChatListener(CiaacPlatformPlugin plugin, AuthenticationRegistry authentication, Clock clock) {
        this.plugin = plugin; this.authentication = authentication; this.clock = clock;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPublicChat(AsyncChatEvent event) {
        var player = event.getPlayer(); var now = clock.instant();
        authentication.current(player.getUniqueId(), now).ifPresent(auth -> {
            String raw = PlainTextComponentSerializer.plainText().serialize(event.message()).strip();
            if (raw.isEmpty()) return;
            String content = raw.length() > 512 ? raw.substring(0, 512) : raw;
            plugin.emit(SecurityEvent.authenticatedPublicChat(now, player.getUniqueId(), player.getName(),
                    auth.connectionId(), content));
        });
    }
}
