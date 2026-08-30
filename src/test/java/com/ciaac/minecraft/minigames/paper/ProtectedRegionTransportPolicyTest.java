package com.ciaac.minecraft.minigames.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import com.ciaac.minecraft.minigames.region.RegionAdmissionRegistry;
import com.ciaac.minecraft.minigames.region.RegionAdmissionToken;
import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import com.ciaac.minecraft.minigames.runtime.SessionPhase;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProtectedRegionTransportPolicyTest {
    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID MATCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final ProtectedRegion REGION = new ProtectedRegion(
            "build-battle.participant",
            GameKey.BUILD_BATTLE,
            new CuboidRegion(UUID.fromString("00000000-0000-0000-0000-000000000004"), 0, 0, 0, 10, 10, 10),
            ProtectedRegionRole.PARTICIPANT_ONLY,
            false);
    private static final ProtectedRegion PUBLIC_REGION = new ProtectedRegion(
            "build-battle.spectator",
            GameKey.BUILD_BATTLE,
            REGION.bounds(),
            ProtectedRegionRole.SPECTATOR_PUBLIC,
            false);

    @Test
    void deniesProtectedRegionWithoutAdmission() {
        assertEquals(
                ProtectedRegionTransportPolicy.Decision.DENY_NO_ADMISSION,
                evaluate(ProtectedRegionTransportPolicy.Destination.known(Optional.of(REGION)), Optional.empty(), new RegionAdmissionRegistry()));
    }

    @Test
    void permitsMatchingLiveSessionToken() {
        RegionAdmissionRegistry admissions = new RegionAdmissionRegistry();
        admissions.issue(new RegionAdmissionToken(
                UUID.randomUUID(), SESSION_ID, PLAYER_ID, REGION.id(), NOW.minusSeconds(1), NOW.plusSeconds(30)));

        assertEquals(
                ProtectedRegionTransportPolicy.Decision.ALLOW,
                evaluate(ProtectedRegionTransportPolicy.Destination.known(Optional.of(REGION)),
                        Optional.of(session(GameKey.BUILD_BATTLE)), admissions));
    }

    @Test
    void rejectsExpiredOrWrongGameAdmission() {
        RegionAdmissionRegistry admissions = new RegionAdmissionRegistry();
        admissions.issue(new RegionAdmissionToken(
                UUID.randomUUID(), SESSION_ID, PLAYER_ID, REGION.id(), NOW.minusSeconds(30), NOW.plusSeconds(30)));

        assertEquals(
                ProtectedRegionTransportPolicy.Decision.DENY_NO_ADMISSION,
                evaluate(ProtectedRegionTransportPolicy.Destination.known(Optional.of(REGION)),
                        Optional.of(session(GameKey.HOT_POTATO)), admissions));
    }

    @Test
    void allowsPublicRegionAndDeniesAmbiguousDestination() {
        assertEquals(
                ProtectedRegionTransportPolicy.Decision.ALLOW,
                evaluate(ProtectedRegionTransportPolicy.Destination.known(Optional.of(PUBLIC_REGION)), Optional.empty(), new RegionAdmissionRegistry()));
        assertEquals(
                ProtectedRegionTransportPolicy.Decision.DENY_AMBIGUOUS_DESTINATION,
                evaluate(ProtectedRegionTransportPolicy.Destination.ambiguous(), Optional.empty(), new RegionAdmissionRegistry()));
    }

    @Test
    void activeNonArenaSessionCannotExitToPublicSpace() {
        assertEquals(
                ProtectedRegionTransportPolicy.Decision.DENY_ACTIVE_ESCAPE,
                evaluate(
                        ProtectedRegionTransportPolicy.Destination.known(Optional.empty()),
                        Optional.of(activeSession(GameKey.BUILD_BATTLE)),
                        new RegionAdmissionRegistry()));
    }

    @Test
    void finishingSessionCanExitForRestoration() {
        PlayerSession session = activeSession(GameKey.BUILD_BATTLE);
        session.transition(
                UUID.randomUUID(), SessionPhase.ACTIVE, SessionPhase.FINISHING, NOW.plusSeconds(1), "FINISH");

        assertEquals(
                ProtectedRegionTransportPolicy.Decision.ALLOW,
                ProtectedRegionTransportPolicy.evaluate(
                        ProtectedRegionTransportPolicy.Destination.known(Optional.empty()),
                        PLAYER_ID,
                        Optional.of(session),
                        new RegionAdmissionRegistry(),
                        NOW.plusSeconds(1)));
    }

    private static ProtectedRegionTransportPolicy.Decision evaluate(
            ProtectedRegionTransportPolicy.Destination destination,
            Optional<PlayerSession> session,
            RegionAdmissionRegistry admissions) {
        return ProtectedRegionTransportPolicy.evaluate(destination, PLAYER_ID, session, admissions, NOW);
    }

    private static PlayerSession session(GameKey game) {
        return new PlayerSession(SESSION_ID, MATCH_ID, PLAYER_ID, game, NOW.minusSeconds(10));
    }

    private static PlayerSession activeSession(GameKey game) {
        PlayerSession session = session(game);
        session.transition(UUID.randomUUID(), SessionPhase.REQUESTED, SessionPhase.SNAPSHOTTING,
                NOW.minusSeconds(9), "START");
        session.transition(UUID.randomUUID(), SessionPhase.SNAPSHOTTING, SessionPhase.SNAPSHOT_COMMITTED,
                NOW.minusSeconds(8), "SNAPSHOT");
        session.transition(UUID.randomUUID(), SessionPhase.SNAPSHOT_COMMITTED, SessionPhase.PREPARING,
                NOW.minusSeconds(7), "PREPARE");
        session.transition(UUID.randomUUID(), SessionPhase.PREPARING, SessionPhase.ACTIVE,
                NOW.minusSeconds(6), "ACTIVATE");
        return session;
    }
}
