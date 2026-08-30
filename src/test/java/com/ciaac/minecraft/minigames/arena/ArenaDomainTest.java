package com.ciaac.minecraft.minigames.arena;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ArenaDomainTest {
    private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");

    @Test
    void parsesSupportedStandardFormatsAndPolicyLimits() {
        assertTrue(ArenaFormat.parse("1v1", false).equals(ArenaFormat.standard(1)));
        assertTrue(ArenaFormat.parse(" 2V2 ", false).equals(ArenaFormat.standard(2)));
        assertTrue(ArenaFormat.parse("3v3", false).equals(ArenaFormat.standard(3)));
        assertThrows(IllegalArgumentException.class, () -> ArenaFormat.parse("4v4", false));
        assertThrows(IllegalArgumentException.class, () -> ArenaFormat.parse("duelo", false));
        var asymmetric = new ArenaFormatPolicy(3, true,
                Set.of(ArenaFormat.asymmetric(2, 3, true)));
        assertTrue(ArenaFormat.parse("2v3", asymmetric).isAsymmetric());
        assertThrows(IllegalArgumentException.class, () -> ArenaFormat.parse("1v2", asymmetric));
        assertThrows(IllegalArgumentException.class,
                () -> ArenaFormat.parse("2v3", ArenaFormatPolicy.defaultPolicy()));
    }

    @Test
    void asymmetricFormatsRequireExplicitOptIn() {
        assertThrows(IllegalArgumentException.class, () -> ArenaFormat.parse("2v3", false));
        var format = ArenaFormat.parse("2v3", true);
        assertTrue(format.isAsymmetric());
        assertTrue(format.notation().equals("2v3"));
        assertThrows(IllegalArgumentException.class, () -> ArenaFormat.asymmetric(0, 3, true));
        assertThrows(IllegalArgumentException.class, () -> new ArenaFormatPolicy(4, true, Set.of()));
    }

    @Test
    void queueAndChallengesUseTheSameAllowlistedFormatPolicy() {
        var policy = new ArenaFormatPolicy(3, true, Set.of(ArenaFormat.asymmetric(2, 3, true)));
        var format = ArenaFormat.parse("2v3", policy);
        var queue = new ArenaQueue(policy);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        queue.enqueue(QueueTicket.enqueue(first, format, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new DirectChallenge(UUID.randomUUID(), first, second, format, ArenaKitMode.FIXED,
                        TeamRoster.of(first, UUID.randomUUID()),
                        TeamRoster.of(second, UUID.randomUUID(), UUID.randomUUID()), NOW.plusSeconds(5)));
        assertDoesNotThrow(() -> new DirectChallenge(UUID.randomUUID(), first, second, format,
                ArenaKitMode.FIXED, TeamRoster.of(first, UUID.randomUUID()),
                TeamRoster.of(second, UUID.randomUUID(), UUID.randomUUID()), NOW.plusSeconds(5), policy));
    }

    @Test
    void rostersAreImmutableAndCannotOverlap() {
        UUID player = UUID.randomUUID();
        var roster = TeamRoster.of(player);
        assertThrows(UnsupportedOperationException.class, () -> roster.players().add(UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,
                () -> ArenaMatch.create(UUID.randomUUID(), ArenaFormat.standard(1), ArenaKitMode.FIXED,
                        roster, TeamRoster.of(player)));
    }

    @Test
    void guardsRosterReadySnapshotAndRestoreTransitions() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var match = ArenaMatch.create(UUID.randomUUID(), ArenaFormat.standard(1), ArenaKitMode.FIXED,
                TeamRoster.of(first), TeamRoster.of(second));
        assertThrows(IllegalStateException.class, () -> match.transitionTo(ArenaPhase.ACTIVE));
        match.transitionTo(ArenaPhase.WAITING);
        match.transitionTo(ArenaPhase.RESERVED_READY);
        assertThrows(IllegalStateException.class, () -> match.transitionTo(ArenaPhase.ADMITTING));
        match.markReady(first);
        match.recordSnapshot(new PlayerStateOperation(first, UUID.randomUUID(), NOW));
        assertThrows(IllegalStateException.class, () -> match.transitionTo(ArenaPhase.ADMITTING));
        match.markReady(second);
        match.recordSnapshot(new PlayerStateOperation(second, UUID.randomUUID(), NOW));
        assertDoesNotThrow(() -> match.transitionTo(ArenaPhase.ADMITTING));
        match.transitionTo(ArenaPhase.ACTIVE);
        match.transitionTo(ArenaPhase.FINISHING);
        match.finalizeOnce(UUID.randomUUID());
        match.transitionTo(ArenaPhase.RESTORING);
        assertThrows(IllegalStateException.class, () -> match.completeRestore());
        match.recordRestore(new PlayerStateOperation(first, UUID.randomUUID(), NOW.plusSeconds(1)));
        assertThrows(IllegalStateException.class, () -> match.completeRestore());
        match.recordRestore(new PlayerStateOperation(second, UUID.randomUUID(), NOW.plusSeconds(1)));
        match.completeRestore();
        assertTrue(match.phase() == ArenaPhase.CLOSED);
        assertThrows(IllegalStateException.class, () -> match.transitionTo(ArenaPhase.IDLE));
    }

    @Test
    void snapshotEvidenceIsIdempotentButConflictsAreRejected() {
        UUID player = UUID.randomUUID();
        var match = ArenaMatch.create(UUID.randomUUID(), ArenaFormat.standard(1), ArenaKitMode.FIXED,
                TeamRoster.of(player), TeamRoster.of(UUID.randomUUID()));
        match.transitionTo(ArenaPhase.WAITING);
        match.transitionTo(ArenaPhase.RESERVED_READY);
        var operation = new PlayerStateOperation(player, UUID.randomUUID(), NOW);
        assertTrue(match.recordSnapshot(operation));
        assertFalse(match.recordSnapshot(operation));
        assertThrows(IllegalStateException.class, () -> match.recordSnapshot(
                new PlayerStateOperation(player, UUID.randomUUID(), NOW)));
    }

    @Test
    void partialReadySnapshotFailureCanRecoverAndReleaseTheSingleArena() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var registry = new ArenaMatchRegistry();
        var match = registry.reserve(UUID.randomUUID(), ArenaFormat.standard(1), ArenaKitMode.FIXED,
                TeamRoster.of(first), TeamRoster.of(second));
        match.markReady(first);
        match.recordSnapshot(new PlayerStateOperation(first, UUID.randomUUID(), NOW));

        match.transitionTo(ArenaPhase.RECOVERING);
        UUID noContestResult = UUID.randomUUID();
        match.finalizeOnce(noContestResult);
        match.transitionTo(ArenaPhase.RESTORING);
        match.recordRestore(new PlayerStateOperation(first, UUID.randomUUID(), NOW.plusSeconds(1)));
        match.completeRestore();
        registry.release(match);

        assertTrue(registry.current().isEmpty());
        assertTrue(match.restoredPlayers().equals(Set.of(first)));
    }

    @Test
    void queueTicketsKeepPartyAndModePoolsSeparateWithoutRequiringAFullTeam() {
        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID partyId = UUID.randomUUID();
        var party = TeamRoster.of(leader, member);
        var ticket = QueueTicket.enqueue(leader, partyId, party, ArenaFormat.standard(3),
                ArenaKitMode.MIRRORED_SURVIVAL, NOW);
        assertTrue(ticket.partyRoster().equals(party));
        assertTrue(ticket.kitMode() == ArenaKitMode.MIRRORED_SURVIVAL);

        assertThrows(IllegalArgumentException.class, () -> QueueTicket.enqueue(
                leader, partyId, party, ArenaFormat.standard(2), ArenaKitMode.STAKED_SURVIVAL, NOW));
    }

    @Test
    void challengeExpiresAndRequiresTheInvitedActor() {
        UUID target = UUID.randomUUID();
        var challenge = new DirectChallenge(UUID.randomUUID(), UUID.randomUUID(), target,
                ArenaFormat.standard(1), NOW.plusSeconds(30));
        assertFalse(challenge.accept(UUID.randomUUID(), NOW.plusSeconds(1)));
        assertTrue(challenge.accept(target, NOW.plusSeconds(1)));
        assertFalse(challenge.accept(target, NOW.plusSeconds(2)));
        var expired = new DirectChallenge(UUID.randomUUID(), UUID.randomUUID(), target,
                ArenaFormat.standard(1), NOW.plusSeconds(1));
        assertFalse(expired.accept(target, NOW.plusSeconds(1)));
        assertTrue(expired.isExpired(NOW.plusSeconds(1)));
    }

    @Test
    void teamChallengesFreezeDistinctRostersAndMode() {
        UUID challenger = UUID.randomUUID();
        UUID ally = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        UUID opponent = UUID.randomUUID();
        var challenge = new DirectChallenge(
                UUID.randomUUID(), challenger, target, ArenaFormat.standard(2), ArenaKitMode.FIXED,
                TeamRoster.of(challenger, ally), TeamRoster.of(target, opponent), NOW.plusSeconds(30));
        assertTrue(challenge.challengerRoster().players().size() == 2);
        assertTrue(challenge.kitMode() == ArenaKitMode.FIXED);
        assertThrows(IllegalArgumentException.class, () -> new DirectChallenge(
                UUID.randomUUID(), challenger, target, ArenaFormat.standard(2), ArenaKitMode.FIXED,
                TeamRoster.of(challenger, ally), TeamRoster.of(target, ally), NOW.plusSeconds(30)));
    }

    @Test
    void admissionTokensAreBoundToMatchPlayerAndExpiry() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var match = ArenaMatch.create(UUID.randomUUID(), ArenaFormat.standard(1), ArenaKitMode.FIXED,
                TeamRoster.of(first), TeamRoster.of(second));
        match.transitionTo(ArenaPhase.WAITING);
        match.transitionTo(ArenaPhase.RESERVED_READY);
        match.markReady(first);
        match.markReady(second);
        match.recordSnapshot(new PlayerStateOperation(first, UUID.randomUUID(), NOW));
        match.recordSnapshot(new PlayerStateOperation(second, UUID.randomUUID(), NOW));
        match.transitionTo(ArenaPhase.ADMITTING);
        var token = match.issueAdmissionToken(first, UUID.randomUUID(), NOW, NOW.plusSeconds(10));
        assertTrue(match.mayEnterFloor(token, NOW.plusSeconds(1)));
        assertFalse(match.mayEnterFloor(new ArenaAdmissionToken(token.tokenId(), token.matchId(), second,
                NOW, NOW.plusSeconds(10)), NOW.plusSeconds(1)));
        assertFalse(match.mayEnterFloor(new ArenaAdmissionToken(token.tokenId(), UUID.randomUUID(), first,
                NOW, NOW.plusSeconds(10)), NOW.plusSeconds(1)));
        assertFalse(match.mayEnterFloor(token, NOW.plusSeconds(10)));
    }

    @Test
    void onlyOneMatchCanReserveAndReleaseRequiresClosed() {
        var registry = new ArenaMatchRegistry();
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();
        var first = registry.reserve(UUID.randomUUID(), ArenaFormat.standard(1), ArenaKitMode.FIXED,
                TeamRoster.of(firstPlayer), TeamRoster.of(secondPlayer));
        assertThrows(IllegalStateException.class, () -> registry.release(first));
        assertThrows(IllegalStateException.class,
                () -> registry.reserve(UUID.randomUUID(), ArenaFormat.standard(1), ArenaKitMode.MIRRORED_SURVIVAL,
                        TeamRoster.of(UUID.randomUUID()), TeamRoster.of(UUID.randomUUID())));
        first.close();
        registry.release(first);
        assertDoesNotThrow(() -> registry.reserve(UUID.randomUUID(), ArenaFormat.standard(1), ArenaKitMode.FIXED,
                TeamRoster.of(UUID.randomUUID()), TeamRoster.of(UUID.randomUUID())));
    }

    @Test
    void queueComposesPartialPartiesWithoutSplittingOrMixingModes() {
        var queue = new ArenaQueue();
        var format = ArenaFormat.standard(2);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        UUID e = UUID.randomUUID();
        queue.enqueue(QueueTicket.enqueue(a, UUID.randomUUID(), TeamRoster.of(a, b), format,
                ArenaKitMode.FIXED, NOW));
        queue.enqueue(QueueTicket.enqueue(c, UUID.randomUUID(), TeamRoster.of(c), format,
                ArenaKitMode.FIXED, NOW.plusSeconds(1)));
        queue.enqueue(QueueTicket.enqueue(d, UUID.randomUUID(), TeamRoster.of(d), format,
                ArenaKitMode.MIRRORED_SURVIVAL, NOW.plusSeconds(2)));
        queue.enqueue(QueueTicket.enqueue(e, UUID.randomUUID(), TeamRoster.of(e), format,
                ArenaKitMode.FIXED, NOW.plusSeconds(3)));

        var proposal = queue.tryMatch(format, ArenaKitMode.FIXED).orElseThrow();
        assertTrue(proposal.teamA().players().equals(List.of(a, b)));
        assertTrue(proposal.teamB().players().equals(List.of(c, e)));
        assertTrue(queue.tickets().size() == 1);
    }

    @Test
    void locationAndEquipmentContractsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> new ArenaLocationPolicy(
                "world", "__SET_ME__", "spectators", "a", "b", "fallback"));
        assertDoesNotThrow(() -> new ArenaLocationPolicy(
                "world", "floor", "spectators", "a", "b", "fallback"));
        assertThrows(IllegalArgumentException.class,
                () -> ArenaEquipmentContract.fixed(""));
        assertThrows(IllegalArgumentException.class,
                () -> new ArenaEquipmentContract(ArenaKitMode.FIXED, "kit", "loadout"));
        assertThrows(IllegalArgumentException.class,
                () -> new ArenaEquipmentContract(ArenaKitMode.MIRRORED_SURVIVAL, "kit", "loadout"));
        var staked = new ArenaEquipmentContract(ArenaKitMode.STAKED_SURVIVAL, " ", " ");
        assertTrue(staked.fixedKitId() == null && staked.protectedLoadoutDigest() == null);
        assertDoesNotThrow(() -> ArenaEquipmentContract.protectedCopy("sha256:loadout"));
        assertDoesNotThrow(ArenaEquipmentContract::staked);
    }

    @Test
    void disconnectDuringCombatIsAnExactlyOnceForfeit() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var match = ArenaMatch.create(UUID.randomUUID(), ArenaFormat.standard(1), ArenaKitMode.FIXED,
                TeamRoster.of(first), TeamRoster.of(second));
        match.transitionTo(ArenaPhase.WAITING);
        match.transitionTo(ArenaPhase.RESERVED_READY);
        match.markReady(first);
        match.markReady(second);
        match.recordSnapshot(new PlayerStateOperation(first, UUID.randomUUID(), NOW));
        match.recordSnapshot(new PlayerStateOperation(second, UUID.randomUUID(), NOW));
        match.transitionTo(ArenaPhase.ADMITTING);
        match.transitionTo(ArenaPhase.ACTIVE);
        assertTrue(match.handleDisconnect(first) == ArenaDisconnectDisposition.ACTIVE_FORFEIT);
        assertFalse(match.forfeit(first, "DISCONNECT"));
        assertTrue(match.phase() == ArenaPhase.FINISHING);
        assertThrows(IllegalArgumentException.class, () -> match.forfeit(second, "bad reason"));
    }
}
