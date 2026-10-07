package com.ciaac.minecraft.minigames.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.runtime.AdmissionResult;
import com.ciaac.minecraft.minigames.runtime.AdmissionStatus;
import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import com.ciaac.minecraft.minigames.runtime.SessionPhase;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MinigamePlatformRuntimeEvacuationGateTest {
    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    @Test
    void noPendingRecoveryAllowsEvacuationOnlyWithoutRegistrySession() {
        UUID playerId = UUID.randomUUID();
        SessionRegistry sessions = new SessionRegistry();
        AdmissionResult noRecovery = AdmissionResult.rejected(
                "NO_RECOVERY_PENDING", "Não tens nenhuma recuperação pendente.");
        assertTrue(MinigamePlatformRuntime.mayRelocateArenaOccupant(noRecovery, sessions, playerId));

        PlayerSession blocking = session(playerId);
        sessions.register(blocking);
        assertFalse(MinigamePlatformRuntime.mayRelocateArenaOccupant(noRecovery, sessions, playerId));
    }

    @Test
    void quarantineAndPendingResultsNeverAllowEvacuation() {
        UUID playerId = UUID.randomUUID();
        SessionRegistry sessions = new SessionRegistry();
        AdmissionResult pending = AdmissionResult.rejected("RECOVERY_PENDING", "A recuperação continua pendente.");
        AdmissionResult quarantined = new AdmissionResult(AdmissionStatus.QUARANTINED,
                "RECOVERY_QUARANTINED", "A recuperação precisa de revisão.", Optional.empty());
        assertFalse(MinigamePlatformRuntime.mayRelocateArenaOccupant(pending, sessions, playerId));
        assertFalse(MinigamePlatformRuntime.mayRelocateArenaOccupant(quarantined, sessions, playerId));
    }

    @Test
    void recoveredSessionMustBeReleasedFromRegistryBeforeEvacuation() {
        UUID playerId = UUID.randomUUID();
        SessionRegistry sessions = new SessionRegistry();
        PlayerSession session = session(playerId);
        sessions.register(session);
        AdmissionResult recovered = new AdmissionResult(AdmissionStatus.RECOVERED,
                "RECOVERED", "A recuperação terminou com segurança.", Optional.of(session));

        assertFalse(MinigamePlatformRuntime.mayRelocateArenaOccupant(recovered, sessions, playerId));

        session.transition(UUID.randomUUID(), SessionPhase.REQUESTED, SessionPhase.CLOSED,
                NOW.plusSeconds(1), "TEST_RECOVERY_COMPLETE");
        sessions.releaseClosed(session.sessionId());
        assertTrue(MinigamePlatformRuntime.mayRelocateArenaOccupant(recovered, sessions, playerId));
    }

    private static PlayerSession session(UUID playerId) {
        return new PlayerSession(UUID.randomUUID(), UUID.randomUUID(), playerId, GameKey.ARENA, NOW);
    }
}
