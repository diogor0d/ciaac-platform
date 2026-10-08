package com.ciaac.minecraft.minigames.paper.module;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.module.ModuleActionResult;
import com.ciaac.minecraft.minigames.module.ModuleStatus;
import com.ciaac.minecraft.minigames.module.MinigameModule;
import com.ciaac.minecraft.minigames.paper.archeryrange.ArcheryPaperController;
import com.ciaac.minecraft.minigames.runtime.AdmissionResult;
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

/** Command/runtime facade for independent lane-based archery runs. */
public final class ArcheryModule implements MinigameModule {
    private final ArcheryPaperController controller;
    private final ModuleIdentity identity;
    private final Runnable shutdownAction;
    private final Clock clock;
    private final Map<UUID, UUID> runIds = new LinkedHashMap<>();
    private boolean faulted;

    public ArcheryModule(ArcheryPaperController controller, ModuleIdentity identity) {
        this(controller, identity, () -> { }, Clock.systemUTC());
    }

    public ArcheryModule(ArcheryPaperController controller, ModuleIdentity identity, Runnable shutdownAction) {
        this(controller, identity, shutdownAction, Clock.systemUTC());
    }

    public ArcheryModule(ArcheryPaperController controller, ModuleIdentity identity,
                         Runnable shutdownAction, Clock clock) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.shutdownAction = Objects.requireNonNull(shutdownAction, "shutdownAction");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override public GameKey key() { return GameKey.ARCHERY_RANGE; }
    public GameKey game() { return key(); }

    @Override public synchronized ModuleStatus status() {
        if (faulted) return ModuleStatuses.unavailable(key());
        try {
            var value = controller.status();
            return ModuleStatuses.of(key(), value.enabled(), value.enabled() ? "WAITING" : "CLOSED",
                    value.enabled(), value.activeLanes(), OptionalInt.of(value.configuredLanes()),
                    value.messagePtPt());
        } catch (RuntimeException ignored) {
            faulted = true;
            return ModuleStatuses.unavailable(key());
        }
    }

    @Override public synchronized Optional<UUID> currentMatchId() { return currentRunId(); }

    /** Per-player run identity for later projectile/disconnect event routing. */
    public synchronized Optional<UUID> runId(UUID playerId) {
        return Optional.ofNullable(runIds.get(Objects.requireNonNull(playerId, "playerId")));
    }

    @Override public List<String> joinCompletions(List<String> arguments) {
        return arguments.size() <= 1 ? controller.menuLanes() : List.of();
    }

    @Override public synchronized ModuleActionResult join(Player player, List<String> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        OptionalInt requestedLane;
        if (arguments.isEmpty()) {
            requestedLane = OptionalInt.empty();
        } else if (arguments.size() == 1) {
            try {
                int lane = Integer.parseInt(Objects.requireNonNull(arguments.get(0), "lane").trim());
                if (lane < 0 || lane > 1024) throw new NumberFormatException("lane out of bounds");
                requestedLane = OptionalInt.of(lane);
            } catch (RuntimeException ignored) {
                return ModuleActionResult.rejected(
                        "LANE_INVALID", "Indica uma lane de tiro numérica e válida.");
            }
        } else {
            return ModuleActionResult.rejected(
                    "ARGUMENTS_INVALID", "Indica no máximo uma lane de tiro.");
        }

        return join(player, requestedLane);
    }

    public synchronized ModuleActionResult join(Player player) {
        return join(player, OptionalInt.empty());
    }

    private synchronized ModuleActionResult join(Player player, OptionalInt requestedLane) {
        if (faulted) return ModuleActionResult.rejected("MODULE_FAULTED",
                "O campo de tiro está fechado enquanto a recuperação é revista.");
        UUID playerId = Objects.requireNonNull(player, "player").getUniqueId();
        UUID runId = UUID.randomUUID();
        try {
            AdmissionRequest request = identity.request(player, key(), runId).orElse(null);
            if (request == null) {
                return ModuleActionResult.rejected("AUTHENTICATION_REQUIRED",
                        "Conclui primeiro a autenticação da tua sessão.");
            }
            AdmissionResult admission = requestedLane.isPresent()
                    ? controller.join(request, player, requestedLane)
                    : controller.join(request, player);
            ModuleActionResult result = ModuleResults.admission(admission);
            if (result.accepted()) {
                runIds.put(playerId, controller.runId(playerId).orElse(runId));
            }
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
            return ModuleActionResult.accepted("LEFT", "Saíste do campo de tiro.");
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
            if (controller.status().activeLanes() == 0) runIds.clear();
        } catch (RuntimeException ignored) {
            faulted = true;
            throw ignored;
        }
        return runIds.size() == 1 ? Optional.of(runIds.values().iterator().next()) : Optional.empty();
    }

    /** Typed event-routing access for the later Bukkit listener layer. */
    public ArcheryPaperController controller() { return controller; }

    /** Lane identity for event routing without exposing mutable controller state. */
    public OptionalInt laneId(UUID playerId) {
        return controller.laneId(Objects.requireNonNull(playerId, "playerId"));
    }
}
