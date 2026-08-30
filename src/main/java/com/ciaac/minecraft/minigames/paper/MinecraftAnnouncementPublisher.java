package com.ciaac.minecraft.minigames.paper;

import com.ciaac.minecraft.minigames.announcement.Announcement;
import com.ciaac.minecraft.minigames.announcement.AnnouncementDestination;
import com.ciaac.minecraft.minigames.announcement.AnnouncementPublishResult;
import com.ciaac.minecraft.minigames.announcement.AnnouncementPublisher;
import com.ciaac.minecraft.minigames.core.GameKey;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Server;

public final class MinecraftAnnouncementPublisher implements AnnouncementPublisher {
    private static final Map<GameKey, String> JOIN_COMMANDS = Map.of(
            GameKey.ARENA, "/coliseu entrar",
            GameKey.BUILD_BATTLE, "/buildbattle entrar",
            GameKey.HOT_POTATO, "/batataquente entrar",
            GameKey.KNOCKBACK_SUMO, "/sumo entrar",
            GameKey.CHECKPOINT_PARKOUR, "/parkour entrar",
            GameKey.ARCHERY_RANGE, "/arco entrar",
            GameKey.ANVIL_DODGE, "/bigornas entrar",
            GameKey.COLOR_FLOOR, "/cores entrar",
            GameKey.ELYTRA_RINGS, "/elytra entrar");
    private final Server server;

    public MinecraftAnnouncementPublisher(Server server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    @Override
    public AnnouncementDestination destination() {
        return AnnouncementDestination.MINECRAFT;
    }

    @Override
    public AnnouncementPublishResult publish(Announcement announcement) {
        String command = JOIN_COMMANDS.get(announcement.game());
        Component message = Component.text("[Minijogos] ", NamedTextColor.GOLD)
                .append(Component.text(announcement.plainTextPtPt(), NamedTextColor.YELLOW));
        if (command != null) {
            message = message.append(Component.space())
                    .append(Component.text("[Entrar]", NamedTextColor.AQUA)
                            .clickEvent(ClickEvent.runCommand(command)));
        }
        server.broadcast(message);
        return AnnouncementPublishResult.delivered("BROADCAST_SENT");
    }
}
