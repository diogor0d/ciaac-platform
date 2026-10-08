package com.ciaac.minecraft.minigames.paper.module;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.module.ModuleActionResult;
import com.ciaac.minecraft.minigames.module.ModuleStatus;
import com.ciaac.minecraft.minigames.module.MinigameModule;
import com.ciaac.minecraft.minigames.region.RegionAdmissionToken;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequest;
import com.ciaac.minecraft.minigames.runtime.AdmissionResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;

/** Shared lifecycle for one fixed physical controller instance. */
abstract class AbstractFixedModule<C> implements MinigameModule {
    private final GameKey game;
    private final ModuleIdentity identity;
    private final FixedControllerPort<C> port;
    private final RegionTokenFactory tokenFactory;
    private final String regionId;
    private final Duration tokenLifetime;
    private final Clock clock;
    private C controller;
    private UUID matchId;
    private boolean faulted;

    protected AbstractFixedModule(GameKey game, ModuleIdentity identity, FixedControllerPort<C> port,
                                  RegionTokenFactory tokenFactory, String regionId,
                                  Duration tokenLifetime, Clock clock) {
        this.game = Objects.requireNonNull(game, "game");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.port = Objects.requireNonNull(port, "port");
        this.tokenFactory = Objects.requireNonNull(tokenFactory, "tokenFactory");
        this.regionId = Objects.requireNonNull(regionId, "regionId");
        this.tokenLifetime = Objects.requireNonNull(tokenLifetime, "tokenLifetime");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (tokenLifetime.isZero() || tokenLifetime.isNegative()) {
            throw new IllegalArgumentException("tokenLifetime must be positive");
        }
        if (!regionId.matches("[a-z0-9][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("regionId is invalid");
        }
    }

    @Override public GameKey key() { return game; }
    /** Compatibility accessor for typed Paper callers. */
    public GameKey game() { return game; }

    @Override public synchronized Optional<UUID> currentMatchId() {
        if (faulted) return Optional.empty();
        cleanupIfTerminal();
        return Optional.ofNullable(matchId);
    }

    @Override public synchronized ModuleStatus status() {
        if (faulted) return ModuleStatuses.unavailable(game);
        try {
            return controller == null ? port.inactiveStatus() : port.status(controller, matchId);
        } catch (RuntimeException ignored) {
            faulted = true;
            return ModuleStatuses.unavailable(game);
        }
    }

    @Override public synchronized ModuleActionResult join(Player player, List<String> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        return join(player);
    }

    /** Typed convenience overload for adapters/tests that do not have command arguments. */
    public synchronized ModuleActionResult join(Player player) {
        if (faulted) return ModuleActionResult.rejected("MODULE_FAULTED",
                "Este minijogo está fechado enquanto a recuperação é revista.");
        try {
            Objects.requireNonNull(player, "player");
            if (!player.isOnline() || !player.isValid()) {
                return ModuleActionResult.rejected("PLAYER_UNAVAILABLE",
                        "O jogador não está disponível neste momento.");
            }
            if (controller == null) {
                ModuleStatus inactive = port.inactiveStatus();
                if (!inactive.joinable()) {
                    return ModuleActionResult.rejected("CONFIGURATION_UNAVAILABLE", inactive.messagePtPt());
                }
                matchId = UUID.randomUUID();
                controller = port.create(matchId, player);
            }
            ModuleStatus status = port.status(controller, matchId);
            if (!status.joinable()) {
                return ModuleActionResult.rejected("CONFIGURATION_UNAVAILABLE", status.messagePtPt());
            }
            AdmissionRequest request = identity.request(player, game, matchId).orElse(null);
            if (request == null) {
                return ModuleActionResult.rejected("AUTHENTICATION_REQUIRED",
                        "Conclui primeiro a autenticação da tua sessão.");
            }
            Instant now = clock.instant();
            RegionAdmissionToken token = tokenFactory.create(game, request, regionId, now, tokenLifetime);
            validateToken(token, request, now);
            AdmissionResult result = port.join(controller, player, request, token);
            cleanupIfTerminal();
            return ModuleResults.admission(result);
        } catch (RuntimeException failure) {
            faultAndRecover(failure);
            return ModuleResults.failure();
        }
    }

    @Override public synchronized ModuleActionResult leave(Player player) {
        try {
            Objects.requireNonNull(player, "player");
            if (controller == null) {
                return ModuleActionResult.rejected("NOT_ACTIVE",
                        "Não tens uma sessão ativa neste minijogo.");
            }
            port.leave(controller, player.getUniqueId(), UUID.randomUUID());
            cleanupIfTerminal();
            return ModuleActionResult.accepted("LEFT", "Saíste do minijogo.");
        } catch (RuntimeException failure) {
            faultAndRecover(failure);
            return ModuleResults.failure();
        }
    }

    @Override public synchronized void tick() { tick(clock.instant()); }

    /** Clock-injected tick for deterministic adapter tests and scheduler bridges. */
    public synchronized void tick(Instant now) {
        if (faulted) return;
        if (controller == null) {
            try {
                port.tickInactive(Objects.requireNonNull(now, "now"));
            } catch (RuntimeException failure) {
                faulted = true;
                try { port.shutdownInactive(); }
                catch (RuntimeException recoveryFailure) { failure.addSuppressed(recoveryFailure); }
                throw failure;
            }
            return;
        }
        try {
            port.tick(controller, Objects.requireNonNull(now, "now"));
            cleanupIfTerminal();
        } catch (RuntimeException failure) {
            faulted = true;
            try { port.shutdown(controller, UUID.randomUUID()); }
            catch (RuntimeException recoveryFailure) { failure.addSuppressed(recoveryFailure); }
            try { cleanupIfTerminal(); }
            catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    @Override public synchronized void shutdown() {
        if (controller == null) {
            try {
                port.shutdownInactive();
            } catch (RuntimeException failure) {
                faulted = true;
                throw failure;
            }
            return;
        }
        try {
            port.shutdown(controller, UUID.randomUUID());
            cleanupIfTerminal();
        } catch (RuntimeException failure) {
            faulted = true;
            throw failure;
        }
    }

    @Override public synchronized ModuleActionResult ready(Player player) {
        return ModuleActionResult.rejected("READY_UNSUPPORTED",
                "Este minijogo não usa confirmação de prontidão.");
    }

    protected synchronized C controller() { return controller; }

    private void cleanupIfTerminal() {
        if (controller != null && port.terminal(controller)) {
            controller = null;
            matchId = null;
        }
    }

    /**
     * A controller operation may have changed player or arena state before it
     * failed. Keep the instance closed and request its normal recovery path;
     * never make an uncertain controller joinable again in this lifecycle.
     */
    private void faultAndRecover(RuntimeException failure) {
        faulted = true;
        if (controller == null) return;
        try { port.shutdown(controller, UUID.randomUUID()); }
        catch (RuntimeException recoveryFailure) { failure.addSuppressed(recoveryFailure); }
        try { cleanupIfTerminal(); }
        catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
    }

    private void validateToken(RegionAdmissionToken token, AdmissionRequest request, Instant now) {
        if (token == null
                || !token.playerId().equals(request.playerId())
                || !token.sessionId().equals(request.sessionId())
                || !token.regionId().equals(regionId)
                || token.expiresAt().isAfter(now.plus(tokenLifetime))
                || !token.validAt(now)) {
            throw new IllegalArgumentException("token does not match request");
        }
    }
}
