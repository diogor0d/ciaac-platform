package com.ciaac.minecraft.minigames.paper.module;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.module.ModuleActionResult;
import com.ciaac.minecraft.minigames.module.ModuleStatus;
import com.ciaac.minecraft.minigames.module.MinigameModule;
import com.ciaac.minecraft.minigames.paper.knockbacksumo.SumoPaperController;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Command/runtime facade for the single two-player Knockback Sumo ring. */
public final class SumoModule implements MinigameModule {
    private final SumoPaperController controller;
    private final ModuleIdentity identity;
    private final Runnable shutdownAction;
    private final Clock clock;
    private final Set<UUID> participantIds = new LinkedHashSet<>();
    private UUID matchId;
    private boolean faulted;

    public SumoModule(SumoPaperController controller, ModuleIdentity identity) {
        this(controller, identity, () -> { }, Clock.systemUTC());
    }

    public SumoModule(SumoPaperController controller, ModuleIdentity identity, Runnable shutdownAction) {
        this(controller, identity, shutdownAction, Clock.systemUTC());
    }

    public SumoModule(SumoPaperController controller, ModuleIdentity identity,
                      Runnable shutdownAction, Clock clock) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.shutdownAction = Objects.requireNonNull(shutdownAction, "shutdownAction");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public GameKey key() { return GameKey.KNOCKBACK_SUMO; }
    public GameKey game() { return key(); }

    @Override public synchronized ModuleStatus status() {
        if (faulted) return ModuleStatuses.unavailable(key());
        try {
            var value = controller.status();
            return ModuleStatuses.of(key(), value.enabled(), value.phase().name(), value.enabled(),
                    value.participants(), OptionalInt.of(2), value.messagePtPt());
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
        if (faulted) return faultedResult();
        UUID candidate = matchId == null ? UUID.randomUUID() : matchId;
        boolean newMatch = matchId == null;
        try {
            AdmissionRequest request = identity.request(Objects.requireNonNull(player, "player"), key(), candidate)
                    .orElse(null);
            if (request == null) {
                return ModuleActionResult.rejected("AUTHENTICATION_REQUIRED",
                        "Conclui primeiro a autenticação da tua sessão.");
            }
            ModuleActionResult result = ModuleResults.admission(controller.join(request, player));
            if (result.accepted()) {
                if (newMatch) matchId = candidate;
                participantIds.add(player.getUniqueId());
            }
            return result;
        } catch (RuntimeException ignored) {
            if (newMatch) clearIfIdle();
            return ModuleResults.failure();
        }
    }

    @Override public synchronized ModuleActionResult leave(Player player) {
        try {
            UUID playerId = Objects.requireNonNull(player, "player").getUniqueId();
            controller.leave(playerId);
            participantIds.remove(playerId);
            clearIfIdle();
            return ModuleActionResult.accepted("LEFT", "Saíste do Knockback Sumo.");
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
            for (UUID playerId : List.copyOf(participantIds)) {
                try { controller.onDisconnect(playerId, UUID.randomUUID()); }
                catch (RuntimeException recoveryFailure) { failure.addSuppressed(recoveryFailure); }
            }
            participantIds.clear();
            throw failure;
        }
    }

    @Override public synchronized void shutdown() {
        RuntimeException failure = null;
        for (UUID playerId : List.copyOf(participantIds)) {
            try { controller.onDisconnect(playerId, UUID.randomUUID()); }
            catch (RuntimeException current) {
                if (failure == null) failure = current; else failure.addSuppressed(current);
            }
        }
        participantIds.clear();
        try { shutdownAction.run(); }
        catch (RuntimeException current) {
            if (failure == null) failure = current; else failure.addSuppressed(current);
        }
        clearIfIdle();
        if (failure != null) { faulted = true; throw failure; }
    }

    private void clearIfIdle() {
        try {
            if (matchId != null && controller.status().participants() == 0) {
                matchId = null;
                participantIds.clear();
            }
        } catch (RuntimeException failure) {
            faulted = true;
            throw failure;
        }
    }

    /** Typed event-routing access for the later Bukkit listener layer. */
    public SumoPaperController controller() { return controller; }

    private static ModuleActionResult faultedResult() {
        return ModuleActionResult.rejected("MODULE_FAULTED",
                "O Knockback Sumo está fechado enquanto a recuperação é revista.");
    }
}
