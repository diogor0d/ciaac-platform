package com.ciaac.minecraft.minigames.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.announcement.Announcement;
import com.ciaac.minecraft.minigames.announcement.AnnouncementDestination;
import com.ciaac.minecraft.minigames.announcement.AnnouncementKind;
import com.ciaac.minecraft.minigames.core.GameKey;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.UUID;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;

class MinecraftAnnouncementPublisherReadinessTest {
    @Test
    void arenaReservationAnnouncementExplainsReadinessWithoutGlobalJoinAction() {
        Capture capture = new Capture();
        MinecraftAnnouncementPublisher publisher = new MinecraftAnnouncementPublisher(capture.server);

        var result = publisher.publish(announcement(GameKey.ARENA, AnnouncementKind.STARTING,
                "O Coliseu está prestes a começar!"));

        assertEquals("BROADCAST_SENT", result.code());
        assertTrue(capture.message.toString().contains("/coliseu pronto"));
        assertFalse(capture.message.toString().contains("Entrar"));
        assertFalse(hasClickAction(capture.message));
    }

    @Test
    void otherGamesKeepTheirExistingStartingJoinAction() {
        Capture capture = new Capture();
        MinecraftAnnouncementPublisher publisher = new MinecraftAnnouncementPublisher(capture.server);

        publisher.publish(announcement(GameKey.BUILD_BATTLE, AnnouncementKind.STARTING,
                "A Construção está prestes a começar!"));

        assertTrue(hasClickAction(capture.message));
        assertTrue(capture.message.toString().contains("Entrar"));
    }

    private static Announcement announcement(GameKey game, AnnouncementKind kind, String text) {
        return new Announcement(UUID.randomUUID(), UUID.randomUUID(), game, kind, text, Instant.EPOCH);
    }

    private static boolean hasClickAction(net.kyori.adventure.text.Component component) {
        if (component.style().clickEvent() != null) return true;
        return component.children().stream().anyMatch(MinecraftAnnouncementPublisherReadinessTest::hasClickAction);
    }

    private static final class Capture {
        private net.kyori.adventure.text.Component message;
        private final Server server = Server.class.cast(Proxy.newProxyInstance(Server.class.getClassLoader(),
                new Class<?>[]{Server.class}, (self, method, args) -> {
                    if (method.getName().equals("broadcast") && args[0] instanceof net.kyori.adventure.text.Component component) {
                        message = component;
                    }
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                }));
    }
}
