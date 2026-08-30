package com.ciaac.minecraft.minigames.announcement;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.statistics.MatchOutcome;
import com.ciaac.minecraft.minigames.statistics.MatchResult;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/** Delivers bounded low-volume lifecycle messages to Minecraft and optionally DiscordSRV. */
public final class AnnouncementDispatcher {
    private final AnnouncementService minecraft;
    private final Optional<AnnouncementService> discord;
    private final boolean discordStarting;
    private final boolean discordWinner;
    private final Clock clock;
    private final Function<UUID, String> playerName;

    public AnnouncementDispatcher(
            AnnouncementService minecraft,
            Optional<AnnouncementService> discord,
            boolean discordStarting,
            boolean discordWinner,
            Clock clock,
            Function<UUID, String> playerName) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.discord = Objects.requireNonNull(discord, "discord");
        this.discordStarting = discordStarting;
        this.discordWinner = discordWinner;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.playerName = Objects.requireNonNull(playerName, "playerName");
    }

    public Map<AnnouncementDestination, AnnouncementPublishResult> waiting(
            GameKey game, UUID matchId, int participants) {
        String text = game.portugueseName() + " está à espera de jogadores"
                + (participants > 0 ? " (" + participants + " na fila)." : ".");
        return publish(game, matchId, AnnouncementKind.WAITING_FOR_PLAYERS, text, true);
    }

    public Map<AnnouncementDestination, AnnouncementPublishResult> starting(GameKey game, UUID matchId) {
        return publish(game, matchId, AnnouncementKind.STARTING,
                game.portugueseName() + " está prestes a começar!", discordStarting);
    }

    /** Cancelled/no-contest outcomes intentionally produce no winner announcement. */
    public Map<AnnouncementDestination, AnnouncementPublishResult> winner(MatchResult result) {
        Objects.requireNonNull(result, "result");
        if (!result.outcome().ranked()) return Map.of();
        List<String> names = new ArrayList<>();
        result.players().forEach((id, player) -> {
            if (player.winner()) names.add(safeName(id));
        });
        names.sort(String.CASE_INSENSITIVE_ORDER);
        if (names.isEmpty()) return Map.of();
        String shown = String.join(", ", names.subList(0, Math.min(3, names.size())));
        if (names.size() > 3) shown += " e mais " + (names.size() - 3);
        return publish(result.game(), result.matchId(), AnnouncementKind.WINNER,
                "Resultado de " + result.game().portugueseName() + ": " + shown + " venceu!",
                discordWinner);
    }

    private Map<AnnouncementDestination, AnnouncementPublishResult> publish(
            GameKey game, UUID matchId, AnnouncementKind kind, String text, boolean includeDiscord) {
        UUID id = UUID.randomUUID();
        Announcement logical = new Announcement(id, matchId, game, kind, text, clock.instant());
        EnumMap<AnnouncementDestination, AnnouncementPublishResult> outcomes =
                new EnumMap<>(AnnouncementDestination.class);
        outcomes.put(AnnouncementDestination.MINECRAFT,
                minecraft.publish(logical.forDestination(AnnouncementDestination.MINECRAFT)));
        if (includeDiscord && discord.isPresent()) {
            outcomes.put(AnnouncementDestination.DISCORD,
                    discord.orElseThrow().publish(logical.forDestination(AnnouncementDestination.DISCORD)));
        }
        return Map.copyOf(outcomes);
    }

    private String safeName(UUID id) {
        String name = playerName.apply(id);
        return name == null || name.isBlank() ? id.toString().substring(0, 8) : name;
    }
}
