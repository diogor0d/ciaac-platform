package com.ciaac.minecraft.minigames.arena;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** An expiring, one-use direct challenge. */
public final class DirectChallenge {
    private final UUID id;
    private final UUID challenger;
    private final UUID target;
    private final ArenaFormat format;
    private final ArenaKitMode kitMode;
    private final TeamRoster challengerRoster;
    private final TeamRoster targetRoster;
    private final Instant expiresAt;
    private boolean consumed;

    public DirectChallenge(UUID id, UUID challenger, UUID target, ArenaFormat format, Instant expiresAt) {
        this(id, challenger, target, format, ArenaKitMode.FIXED,
                TeamRoster.of(challenger), TeamRoster.of(target), expiresAt);
    }

    public DirectChallenge(UUID id, UUID challenger, UUID target, ArenaFormat format, ArenaKitMode kitMode,
                           TeamRoster challengerRoster, TeamRoster targetRoster, Instant expiresAt) {
        this(id, challenger, target, format, kitMode, challengerRoster, targetRoster, expiresAt,
                ArenaFormatPolicy.defaultPolicy());
    }

    public DirectChallenge(UUID id, UUID challenger, UUID target, ArenaFormat format, ArenaKitMode kitMode,
                           TeamRoster challengerRoster, TeamRoster targetRoster, Instant expiresAt,
                           ArenaFormatPolicy policy) {
        this.id = Objects.requireNonNull(id, "id");
        this.challenger = Objects.requireNonNull(challenger, "challenger");
        this.target = Objects.requireNonNull(target, "target");
        this.format = Objects.requireNonNull(format, "format");
        this.kitMode = Objects.requireNonNull(kitMode, "kitMode");
        this.challengerRoster = Objects.requireNonNull(challengerRoster, "challengerRoster");
        this.targetRoster = Objects.requireNonNull(targetRoster, "targetRoster");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(policy, "policy").requireSupported(format);
        if (challenger.equals(target)) {
            throw new IllegalArgumentException("A player cannot challenge themselves");
        }
        if (!challengerRoster.players().contains(challenger) || !targetRoster.players().contains(target)) {
            throw new IllegalArgumentException("Challenge leaders must belong to their immutable rosters");
        }
        if (challengerRoster.players().stream().anyMatch(targetRoster.players()::contains)) {
            throw new IllegalArgumentException("Challenge rosters cannot overlap");
        }
        if (challengerRoster.players().size() != format.teamASize()
                || targetRoster.players().size() != format.teamBSize()) {
            throw new IllegalArgumentException("Challenge rosters do not match the arena format");
        }
        if (kitMode == ArenaKitMode.STAKED_SURVIVAL && !format.equals(ArenaFormat.standard(1))) {
            throw new IllegalArgumentException("Staked challenges are limited to 1v1");
        }
    }

    public synchronized boolean accept(UUID actor, Instant now) {
        if (!target.equals(Objects.requireNonNull(actor, "actor"))) {
            return false;
        }
        Objects.requireNonNull(now, "now");
        if (consumed || !now.isBefore(expiresAt)) {
            return false;
        }
        consumed = true;
        return true;
    }

    public UUID id() { return id; }
    public UUID challenger() { return challenger; }
    public UUID target() { return target; }
    public ArenaFormat format() { return format; }
    public ArenaKitMode kitMode() { return kitMode; }
    public TeamRoster challengerRoster() { return challengerRoster; }
    public TeamRoster targetRoster() { return targetRoster; }
    public Instant expiresAt() { return expiresAt; }

    public synchronized boolean isConsumed() { return consumed; }

    public boolean isExpired(Instant now) {
        return !Objects.requireNonNull(now, "now").isBefore(expiresAt);
    }
}
