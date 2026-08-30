package com.ciaac.minecraft.minigames.arena;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Serializes the single reserved/active arena slot. */
public final class ArenaMatchRegistry {
    private ArenaMatch current;

    public synchronized ArenaMatch reserve(UUID matchId, ArenaFormat format, ArenaKitMode kitMode,
                                            TeamRoster teamA, TeamRoster teamB) {
        return reserve(matchId, format, kitMode, teamA, teamB, ArenaFormatPolicy.defaultPolicy());
    }

    public synchronized ArenaMatch reserve(UUID matchId, ArenaFormat format, ArenaKitMode kitMode,
                                            TeamRoster teamA, TeamRoster teamB, ArenaFormatPolicy policy) {
        Objects.requireNonNull(policy, "policy").requireSupported(format);
        return reserveInternal(matchId, format, kitMode, teamA, teamB, null);
    }

    public synchronized ArenaMatch reserve(UUID matchId, ArenaFormat format, ArenaKitMode kitMode,
                                            TeamRoster teamA, TeamRoster teamB, StakedEscrow stakedEscrow) {
        return reserve(matchId, format, kitMode, teamA, teamB, stakedEscrow, ArenaFormatPolicy.defaultPolicy());
    }

    public synchronized ArenaMatch reserve(UUID matchId, ArenaFormat format, ArenaKitMode kitMode,
                                            TeamRoster teamA, TeamRoster teamB, StakedEscrow stakedEscrow,
                                            ArenaFormatPolicy policy) {
        Objects.requireNonNull(policy, "policy").requireSupported(format);
        return reserveInternal(matchId, format, kitMode, teamA, teamB,
                Objects.requireNonNull(stakedEscrow, "stakedEscrow"));
    }

    private ArenaMatch reserveInternal(UUID matchId, ArenaFormat format, ArenaKitMode kitMode,
                                       TeamRoster teamA, TeamRoster teamB, StakedEscrow stakedEscrow) {
        if (current != null) {
            throw new IllegalStateException("Release the terminal closed match before reserving again");
        }
        var match = stakedEscrow == null
                ? ArenaMatch.create(matchId, format, kitMode, teamA, teamB)
                : ArenaMatch.create(matchId, format, kitMode, teamA, teamB, stakedEscrow);
        match.transitionTo(ArenaPhase.WAITING);
        match.transitionTo(ArenaPhase.RESERVED_READY);
        current = match;
        return match;
    }

    public synchronized void release(ArenaMatch match) {
        Objects.requireNonNull(match, "match");
        if (current != match) {
            throw new IllegalArgumentException("Match is not the current arena reservation");
        }
        if (match.phase() != ArenaPhase.CLOSED) {
            throw new IllegalStateException("Only a terminal closed match can release the arena");
        }
        current = null;
    }

    public synchronized Optional<ArenaMatch> current() {
        return Optional.ofNullable(current);
    }
}
