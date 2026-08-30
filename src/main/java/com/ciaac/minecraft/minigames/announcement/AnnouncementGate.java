package com.ciaac.minecraft.minigames.announcement;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class AnnouncementGate {
    private final Duration waitingCooldown;
    private final Set<UUID> acceptedIds = new HashSet<>();
    private final Set<MatchKind> acceptedMatchKinds = new HashSet<>();
    private final Map<GameKey, Instant> lastWaitingByGame = new HashMap<>();

    public AnnouncementGate(Duration waitingCooldown) {
        this.waitingCooldown = Objects.requireNonNull(waitingCooldown, "waitingCooldown");
        if (waitingCooldown.isNegative()) {
            throw new IllegalArgumentException("waitingCooldown must not be negative");
        }
    }

    public synchronized boolean accept(Announcement announcement) {
        Objects.requireNonNull(announcement, "announcement");
        if (acceptedIds.contains(announcement.announcementId())) {
            return false;
        }
        MatchKind matchKind = new MatchKind(announcement.matchId(), announcement.kind());
        if (acceptedMatchKinds.contains(matchKind)) {
            return false;
        }
        if (announcement.kind() == AnnouncementKind.WAITING_FOR_PLAYERS) {
            Instant previous = lastWaitingByGame.get(announcement.game());
            if (previous != null && announcement.createdAt().isBefore(previous.plus(waitingCooldown))) {
                return false;
            }
            lastWaitingByGame.put(announcement.game(), announcement.createdAt());
        }
        acceptedIds.add(announcement.announcementId());
        acceptedMatchKinds.add(matchKind);
        return true;
    }

    private record MatchKind(UUID matchId, AnnouncementKind kind) {}
}
