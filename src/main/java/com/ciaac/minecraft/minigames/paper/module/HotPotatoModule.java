package com.ciaac.minecraft.minigames.paper.module;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.hotpotato.HotPotatoPhase;
import com.ciaac.minecraft.minigames.module.ModuleActionResult;
import com.ciaac.minecraft.minigames.module.ModuleStatus;
import com.ciaac.minecraft.minigames.module.MinigameModule;
import com.ciaac.minecraft.minigames.paper.hotpotato.HotPotatoPaperController;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Command/runtime facade for one queued Hot Potato instance. */
public final class HotPotatoModule implements MinigameModule {
    private final HotPotatoPaperController controller;
    private final ModuleIdentity identity;
    private final Clock clock;
    private UUID matchId;
    private boolean faulted;

    public HotPotatoModule(HotPotatoPaperController controller, ModuleIdentity identity) {
        this(controller, identity, Clock.systemUTC());
    }

    public HotPotatoModule(HotPotatoPaperController controller, ModuleIdentity identity, Clock clock) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public GameKey key() { return GameKey.HOT_POTATO; }
    public GameKey game() { return key(); }

    @Override public synchronized ModuleStatus status() {
        if (faulted) return ModuleStatuses.unavailable(key());
        try {
            var value = controller.status();
            String phase = value.phase() == HotPotatoPhase.IDLE && value.enabled()
                    ? HotPotatoPhase.WAITING.name() : value.phase().name();
            return ModuleStatuses.of(key(), value.enabled(), phase, value.enabled(), value.players(),
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
                "A Batata Quente está fechada enquanto a recuperação é revista.");
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
            controller.leave(Objects.requireNonNull(player, "player").getUniqueId());
            clearIfIdle();
            return ModuleActionResult.accepted("LEFT", "Saíste da Batata Quente.");
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

    private void clearIfIdle() {
        if (faulted) return;
        try {
            var value = controller.status();
            if (matchId != null && value.phase() == HotPotatoPhase.IDLE
                    && value.players() == 0) matchId = null;
        } catch (RuntimeException failure) {
            faulted = true;
            throw failure;
        }
    }

    /** Typed event-routing access for the later Bukkit listener layer. */
    public HotPotatoPaperController controller() { return controller; }
}
