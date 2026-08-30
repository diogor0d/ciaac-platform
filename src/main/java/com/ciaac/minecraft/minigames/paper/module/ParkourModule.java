package com.ciaac.minecraft.minigames.paper.module;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.module.ModuleActionResult;
import com.ciaac.minecraft.minigames.module.ModuleStatus;
import com.ciaac.minecraft.minigames.module.MinigameModule;
import com.ciaac.minecraft.minigames.paper.checkpointparkour.ParkourPaperController;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Command/runtime facade for independent ordered-checkpoint runs. */
public final class ParkourModule implements MinigameModule {
    private final ParkourPaperController controller;
    private final ModuleIdentity identity;
    private final Runnable shutdownAction;
    private final Clock clock;
    private final Map<UUID, UUID> runIds = new LinkedHashMap<>();
    private boolean faulted;

    public ParkourModule(ParkourPaperController controller, ModuleIdentity identity) {
        this(controller, identity, () -> { }, Clock.systemUTC());
    }

    public ParkourModule(ParkourPaperController controller, ModuleIdentity identity, Runnable shutdownAction) {
        this(controller, identity, shutdownAction, Clock.systemUTC());
    }

    public ParkourModule(ParkourPaperController controller, ModuleIdentity identity,
                         Runnable shutdownAction, Clock clock) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.shutdownAction = Objects.requireNonNull(shutdownAction, "shutdownAction");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public GameKey key() { return GameKey.CHECKPOINT_PARKOUR; }
    public GameKey game() { return key(); }

    @Override public synchronized ModuleStatus status() {
        if (faulted) return ModuleStatuses.unavailable(key());
        try {
            var value = controller.status();
            return ModuleStatuses.of(key(), value.enabled(), value.enabled() ? "WAITING" : "CLOSED",
                    value.enabled() && value.activeRuns() < value.maximumRuns(), value.activeRuns(),
                    OptionalInt.of(value.maximumRuns()), value.messagePtPt());
        } catch (RuntimeException ignored) {
            faulted = true;
            return ModuleStatuses.unavailable(key());
        }
    }

    @Override public synchronized Optional<UUID> currentMatchId() { return currentRunId(); }

    /** Per-player run identity for later checkpoint/disconnect event routing. */
    public synchronized Optional<UUID> runId(UUID playerId) {
        return Optional.ofNullable(runIds.get(Objects.requireNonNull(playerId, "playerId")));
    }

    @Override public synchronized ModuleActionResult join(Player player, List<String> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        return join(player);
    }

    public synchronized ModuleActionResult join(Player player) {
        if (faulted) return ModuleActionResult.rejected("MODULE_FAULTED",
                "O parkour está fechado enquanto a recuperação é revista.");
        UUID playerId = Objects.requireNonNull(player, "player").getUniqueId();
        UUID runId = UUID.randomUUID();
        try {
            AdmissionRequest request = identity.request(player, key(), runId).orElse(null);
            if (request == null) {
                return ModuleActionResult.rejected("AUTHENTICATION_REQUIRED",
                        "Conclui primeiro a autenticação da tua sessão.");
            }
            ModuleActionResult result = ModuleResults.admission(controller.join(request, player));
            if (result.accepted()) runIds.put(playerId, runId);
            return result;
        } catch (RuntimeException ignored) {
            return ModuleResults.failure();
        }
    }

    @Override public synchronized ModuleActionResult leave(Player player) {
        try {
            UUID playerId = Objects.requireNonNull(player, "player").getUniqueId();
            controller.leave(playerId);
            runIds.remove(playerId);
            return ModuleActionResult.accepted("LEFT", "Saíste do parkour.");
        } catch (RuntimeException ignored) {
            return ModuleResults.failure();
        }
    }

    @Override public synchronized void tick() { tick(clock.instant()); }

    public synchronized void tick(Instant now) {
        if (faulted) return;
        try {
            controller.tick(Objects.requireNonNull(now, "now"));
            runIds.entrySet().removeIf(entry -> controller.runId(entry.getKey()).isEmpty());
        } catch (RuntimeException failure) {
            faulted = true;
            for (UUID playerId : List.copyOf(runIds.keySet())) {
                try { controller.onDisconnect(playerId); }
                catch (RuntimeException recoveryFailure) { failure.addSuppressed(recoveryFailure); }
            }
            runIds.clear();
            throw failure;
        }
    }

    @Override public synchronized void shutdown() {
        RuntimeException failure = null;
        for (UUID playerId : List.copyOf(runIds.keySet())) {
            try { controller.onDisconnect(playerId); }
            catch (RuntimeException current) {
                if (failure == null) failure = current; else failure.addSuppressed(current);
            }
        }
        try { shutdownAction.run(); }
        catch (RuntimeException current) {
            if (failure == null) failure = current; else failure.addSuppressed(current);
        }
        runIds.clear();
        if (failure != null) { faulted = true; throw failure; }
    }

    private Optional<UUID> currentRunId() {
        if (faulted) return Optional.empty();
        try {
            if (controller.status().activeRuns() == 0) runIds.clear();
        } catch (RuntimeException ignored) {
            faulted = true;
            throw ignored;
        }
        return runIds.size() == 1 ? Optional.of(runIds.values().iterator().next()) : Optional.empty();
    }

    /** Typed event-routing access for the later Bukkit listener layer. */
    public ParkourPaperController controller() { return controller; }
}
