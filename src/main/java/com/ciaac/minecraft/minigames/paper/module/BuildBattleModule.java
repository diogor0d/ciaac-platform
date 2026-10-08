package com.ciaac.minecraft.minigames.paper.module;

import com.ciaac.minecraft.minigames.buildbattle.BuildBattlePhase;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattlePlot;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.module.ModuleActionResult;
import com.ciaac.minecraft.minigames.module.ModuleStatus;
import com.ciaac.minecraft.minigames.module.MinigameModule;
import com.ciaac.minecraft.minigames.paper.buildbattle.BuildBattlePaperController;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequest;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Command/runtime facade for the single managed Build Battle instance. */
public final class BuildBattleModule implements MinigameModule {
    private final BuildBattlePaperController controller;
    private final ModuleIdentity identity;
    private final Clock clock;
    private UUID matchId;
    private boolean faulted;

    public BuildBattleModule(BuildBattlePaperController controller, ModuleIdentity identity) {
        this(controller, identity, Clock.systemUTC());
    }

    public BuildBattleModule(BuildBattlePaperController controller, ModuleIdentity identity, Clock clock) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public GameKey key() { return GameKey.BUILD_BATTLE; }
    public GameKey game() { return key(); }

    @Override public synchronized ModuleStatus status() {
        if (faulted) return ModuleStatuses.unavailable(key());
        try {
            var value = controller.status();
            String phase = switch (value.phase()) {
                case IDLE -> value.enabled() ? BuildBattlePhase.WAITING.name() : BuildBattlePhase.IDLE.name();
                case THEME_VOTING -> BuildBattlePhase.VOTING.name();
                case REVIEWING -> BuildBattlePhase.BUILDING.name();
                default -> value.phase().name();
            };
            return ModuleStatuses.of(key(), value.enabled(), phase, value.ready(), value.players(),
                    OptionalInt.empty(), value.messagePtPt());
        } catch (RuntimeException ignored) {
            faulted = true;
            return ModuleStatuses.unavailable(key());
        }
    }

    @Override public synchronized Optional<UUID> currentMatchId() {
        if (faulted) return Optional.empty();
        clearIfIdle();
        return Optional.ofNullable(matchId);
    }

    @Override public synchronized ModuleActionResult join(Player player, List<String> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        return join(player);
    }

    public synchronized ModuleActionResult join(Player player) {
        if (faulted) return ModuleActionResult.rejected("MODULE_FAULTED",
                "O Build Battle está fechado enquanto a recuperação é revista.");
        UUID candidate = matchId == null ? UUID.randomUUID() : matchId;
        boolean newMatch = matchId == null;
        try {
            AdmissionRequest request = identity.request(Objects.requireNonNull(player, "player"), key(), candidate)
                    .orElse(null);
            if (request == null) {
                return ModuleActionResult.rejected("AUTHENTICATION_REQUIRED",
                        "Conclui primeiro a autenticação da tua sessão.");
            }
            ModuleActionResult result = ModuleResults.admission(controller.join(player, request));
            if (newMatch && result.accepted()) matchId = candidate;
            return result;
        } catch (RuntimeException ignored) {
            if (newMatch) clearIfIdle();
            return ModuleResults.failure();
        }
    }

    @Override public synchronized ModuleActionResult leave(Player player) {
        try {
            var result = controller.leave(Objects.requireNonNull(player, "player").getUniqueId());
            clearIfIdle();
            return ModuleResults.admission(result);
        } catch (RuntimeException ignored) {
            return ModuleResults.failure();
        }
    }

    @Override public synchronized void tick() { tick(clock.instant()); }

    public synchronized void tick(Instant now) {
        if (faulted) return;
        try {
            controller.tick(Objects.requireNonNull(now, "now"));
            clearIfIdle();
        } catch (RuntimeException failure) {
            faulted = true;
            try { controller.shutdown(); }
            catch (RuntimeException recoveryFailure) { failure.addSuppressed(recoveryFailure); }
            throw failure;
        }
    }

    @Override public synchronized void shutdown() {
        try {
            controller.shutdown();
            clearIfIdle();
        } catch (RuntimeException failure) {
            faulted = true;
            throw failure;
        }
    }

    @Override public synchronized ModuleActionResult action(Player player, String action, List<String> arguments) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(arguments, "arguments");
        if (faulted) return ModuleActionResult.rejected("MODULE_FAULTED",
                "O Build Battle está fechado enquanto a recuperação é revista.");
        String normalized = action.toLowerCase(Locale.ROOT);
        try {
            return switch (normalized) {
                case "tema" -> themeVote(player, arguments);
                case "avaliar", "votar", "vote" -> plotVote(player, arguments);
                case "ver" -> showReview(player, arguments);
                default -> ModuleActionResult.rejected("ACTION_UNKNOWN",
                        "Usa /buildbattle ajuda para veres as ações disponíveis.");
            };
        } catch (IllegalArgumentException | IllegalStateException failure) {
            return ModuleActionResult.rejected("ACTION_REJECTED", boundedMessage(failure.getMessage()));
        } catch (RuntimeException failure) {
            return ModuleResults.failure();
        }
    }

    @Override public synchronized List<String> actionCompletions(String action, List<String> arguments) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(arguments, "arguments");
        String normalized = action.toLowerCase(Locale.ROOT);
        if (normalized.equals("tema") && arguments.size() <= 1) {
            return controller.themeOptions().stream().map(value -> value.id()).toList();
        }
        if (normalized.equals("ver") && arguments.isEmpty()) return List.of("seguinte");
        if ((normalized.equals("avaliar") || normalized.equals("votar") || normalized.equals("vote"))
                && arguments.size() <= 1) {
            return controller.voteScoreOptions();
        }
        return List.of();
    }

    private void clearIfIdle() {
        if (faulted) return;
        try {
            var value = controller.status();
            if (matchId != null && value.phase() == BuildBattlePhase.IDLE
                    && value.players() == 0) matchId = null;
        } catch (RuntimeException failure) {
            faulted = true;
            throw failure;
        }
    }

    private ModuleActionResult themeVote(Player player, List<String> arguments) {
        if (arguments.size() != 1 || !arguments.get(0).matches("[a-z0-9][a-z0-9_.-]{0,63}")) {
            return ModuleActionResult.rejected("INVALID_THEME", "Indica um dos três temas apresentados.");
        }
        String themeId = arguments.get(0);
        UUID eventId = commandEvent(player, "tema", arguments);
        controller.voteTheme(player.getUniqueId(), themeId, eventId);
        return ModuleActionResult.accepted("THEME_VOTE_RECORDED", "O teu voto de tema foi registado.");
    }

    private ModuleActionResult plotVote(Player player, List<String> arguments) {
        if (arguments.size() != 2 || !arguments.get(0).matches("[a-z0-9][a-z0-9_.-]{0,31}")) {
            return ModuleActionResult.rejected("INVALID_VOTE", "Usa /buildbattle avaliar <parcela> <pontuação>.");
        }
        int score;
        try {
            score = Integer.parseInt(arguments.get(1));
        } catch (NumberFormatException invalid) {
            return ModuleActionResult.rejected("INVALID_VOTE", "A avaliação tem de ser um número.");
        }
        BuildBattlePlot plot = new BuildBattlePlot(arguments.get(0));
        controller.vote(player.getUniqueId(), plot, score, commandEvent(player, "avaliar", arguments));
        return ModuleActionResult.accepted("PLOT_VOTE_RECORDED", "Avaliação registada. Continua pela próxima parcela.");
    }

    private ModuleActionResult showReview(Player player, List<String> arguments) {
        if (arguments.size() > 1 || (arguments.size() == 1 && !arguments.get(0).equalsIgnoreCase("seguinte"))) {
            return ModuleActionResult.rejected("INVALID_REVIEW", "Usa /buildbattle ver para rever a parcela atual.");
        }
        controller.showCurrentReview(player.getUniqueId());
        return ModuleActionResult.accepted("REVIEW_SHOWN", "A parcela atual foi indicada no chat.");
    }

    private UUID commandEvent(Player player, String action, List<String> arguments) {
        UUID activeMatch = matchId == null ? controller.currentMatchId().orElse(UUID.nameUUIDFromBytes(
                ("buildbattle:" + player.getUniqueId()).getBytes(StandardCharsets.UTF_8))) : matchId;
        String seed = activeMatch + ":" + player.getUniqueId() + ":" + action + ":" + String.join("\u001f", arguments);
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static String boundedMessage(String value) {
        if (value == null || value.isBlank()) return "Não foi possível concluir a ação.";
        return value.length() <= 280 ? value : value.substring(0, 277) + "...";
    }

    /** Typed event-routing access for the later Bukkit listener layer. */
    public BuildBattlePaperController controller() { return controller; }
}
