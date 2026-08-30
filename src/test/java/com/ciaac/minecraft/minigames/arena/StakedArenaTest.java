package com.ciaac.minecraft.minigames.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StakedArenaTest {
    private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");

    @Test
    void requiresFreshMutualConsentBoundToMatchRulesAndManifest() {
        var players = players(2);
        UUID matchId = UUID.randomUUID();
        var escrow = escrow(matchId, players, Map.of(), Set.of());
        assertFalse(escrow.canAdmit());
        assertThrows(IllegalArgumentException.class, () -> escrow.consent(
                new StakedConsent(players.get(0), UUID.randomUUID(), escrow.rulesetDigest(),
                        escrow.manifestDigest(), NOW.plusSeconds(1))));
        assertThrows(IllegalArgumentException.class, () -> escrow.consent(
                new StakedConsent(players.get(0), matchId, escrow.rulesetDigest(),
                        escrow.manifestDigest(), NOW.minusSeconds(1))));
        assertTrue(escrow.consent(consent(escrow, players.get(0))));
        assertFalse(escrow.canAdmit());
        assertTrue(escrow.consent(consent(escrow, players.get(1))));
        assertTrue(escrow.canAdmit());
    }

    @Test
    void manifestRetainsEveryOfferedItemWhileBlacklistedItemsBlockAdmission() {
        var players = players(2);
        UUID matchId = UUID.randomUUID();
        var offered = new LinkedHashMap<UUID, List<StakedItem>>();
        var firstItems = new ArrayList<>(List.of(new StakedItem("a", "DIAMOND", 1, "fp-a")));
        offered.put(players.get(0), firstItems);
        offered.put(players.get(1), new ArrayList<>());
        var escrow = escrow(matchId, players, offered, Set.of("DIAMOND"));
        firstItems.clear();
        assertEquals(1, escrow.quarantined().size());
        assertEquals("a", escrow.quarantined().get(0).item().itemId());
        assertEquals(1, escrow.manifest().get(players.get(0)).size());
        assertThrows(UnsupportedOperationException.class,
                () -> escrow.manifest().get(players.get(0)).add(new StakedItem("b", "STONE", 1, "fp-b")));
        assertFalse(escrow.canAdmit());
        assertThrows(IllegalStateException.class,
                () -> escrow.settleToWinnerOnce(UUID.randomUUID(), players.get(0)));
    }

    @Test
    void settlementIsExactlyOnceAndBoundToResultAndWinner() {
        var players = players(2);
        UUID matchId = UUID.randomUUID();
        var escrow = escrow(matchId, players,
                Map.of(players.get(0), List.of(new StakedItem("a", "DIAMOND", 1, "fp-a")),
                        players.get(1), List.of(new StakedItem("b", "IRON_INGOT", 2, "fp-b"))), Set.of());
        players.forEach(player -> escrow.consent(consent(escrow, player)));
        UUID resultId = UUID.randomUUID();
        assertTrue(escrow.settleToWinnerOnce(resultId, players.get(0)));
        assertFalse(escrow.settleToWinnerOnce(resultId, players.get(0)));
        assertThrows(IllegalStateException.class,
                () -> escrow.settleToWinnerOnce(resultId, players.get(1)));
        assertThrows(IllegalStateException.class,
                () -> escrow.settleToWinnerOnce(UUID.randomUUID(), players.get(1)));
        assertEquals(StakedEscrow.SettlementState.SETTLED, escrow.state());
        assertEquals(players.get(0), escrow.winner().orElseThrow());
    }

    @Test
    void cancellationRefundIsExactlyOnceAndReasonIsBounded() {
        var players = players(2);
        var escrow = escrow(UUID.randomUUID(), players, Map.of(), Set.of());
        UUID resultId = UUID.randomUUID();
        assertTrue(escrow.refundOnce(resultId, "cancelled_by_timeout"));
        assertFalse(escrow.refundOnce(resultId, "cancelled_by_timeout"));
        assertThrows(IllegalStateException.class,
                () -> escrow.refundOnce(resultId, "different"));
        assertThrows(IllegalStateException.class,
                () -> escrow.refundOnce(UUID.randomUUID(), "other"));
        assertThrows(IllegalArgumentException.class,
                () -> escrow.refundOnce(UUID.randomUUID(), "not machine readable"));
        assertEquals("CANCELLED_BY_TIMEOUT", escrow.refundReason().orElseThrow());
    }

    @Test
    void stakedTeamMatchesRemainClosedUntilAnAllocationPolicyIsDesigned() {
        var players = players(4);
        var participants = new LinkedHashSet<>(players);
        var offered = new LinkedHashMap<UUID, List<StakedItem>>();
        players.forEach(player -> offered.put(player, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> StakedEscrow.prepare(UUID.randomUUID(), UUID.randomUUID(), "rules-v1", NOW,
                        ArenaFormat.standard(2), participants, offered, Set.of()));
    }

    @Test
    void matchRequiresEscrowIdentityAndMatchingTerminalResultBeforeClose() {
        var players = players(2);
        UUID matchId = UUID.randomUUID();
        var escrow = escrow(matchId, players, Map.of(), Set.of());
        assertThrows(IllegalArgumentException.class,
                () -> ArenaMatch.create(UUID.randomUUID(), ArenaFormat.standard(1), ArenaKitMode.STAKED_SURVIVAL,
                        TeamRoster.of(players.get(0)), TeamRoster.of(players.get(1)), escrow));
        var match = ArenaMatch.create(matchId, ArenaFormat.standard(1), ArenaKitMode.STAKED_SURVIVAL,
                TeamRoster.of(players.get(0)), TeamRoster.of(players.get(1)), escrow);
        prepareAdmission(match, players);
        assertThrows(IllegalStateException.class, () -> match.transitionTo(ArenaPhase.ADMITTING));
        players.forEach(player -> escrow.consent(consent(escrow, player)));
        match.transitionTo(ArenaPhase.ADMITTING);
        match.transitionTo(ArenaPhase.ACTIVE);
        match.transitionTo(ArenaPhase.FINISHING);
        UUID resultId = UUID.randomUUID();
        match.finalizeOnce(resultId);
        match.transitionTo(ArenaPhase.RESTORING);
        players.forEach(player -> match.recordRestore(
                new PlayerStateOperation(player, UUID.randomUUID(), NOW.plusSeconds(2))));
        assertThrows(IllegalStateException.class, match::completeRestore);
        escrow.refundOnce(resultId, "NO_CONTEST");
        match.completeRestore();
        assertEquals(ArenaPhase.CLOSED, match.phase());
    }

    @Test
    void unusedStakedReservationStillRequiresResultBoundRefundRecovery() {
        var players = players(2);
        UUID matchId = UUID.randomUUID();
        var escrow = escrow(matchId, players, Map.of(), Set.of());
        var match = ArenaMatch.create(matchId, ArenaFormat.standard(1), ArenaKitMode.STAKED_SURVIVAL,
                TeamRoster.of(players.get(0)), TeamRoster.of(players.get(1)), escrow);
        match.transitionTo(ArenaPhase.WAITING);
        match.transitionTo(ArenaPhase.RESERVED_READY);
        assertThrows(IllegalStateException.class, match::close);

        match.transitionTo(ArenaPhase.RECOVERING);
        UUID noContestResult = UUID.randomUUID();
        match.finalizeOnce(noContestResult);
        escrow.refundOnce(noContestResult, "READY_CHECK_CANCELLED");
        match.transitionTo(ArenaPhase.RESTORING);
        match.completeRestore();
        assertEquals(ArenaPhase.CLOSED, match.phase());
    }

    @Test
    void nonStakedModesRequireSnapshotsAndHaveNoExternalEscape() {
        var players = players(2);
        var match = ArenaMatch.create(UUID.randomUUID(), ArenaFormat.standard(1), ArenaKitMode.MIRRORED_SURVIVAL,
                TeamRoster.of(players.get(0)), TeamRoster.of(players.get(1)));
        prepareAdmission(match, players);
        match.transitionTo(ArenaPhase.ADMITTING);
        var token = match.issueAdmissionToken(players.get(0), UUID.randomUUID(), NOW, NOW.plusSeconds(30));
        assertTrue(match.mayEnterFloor(token, NOW.plusSeconds(1)));
        assertFalse(match.allowsExternalEscape());
        match.transitionTo(ArenaPhase.ACTIVE);
        match.transitionTo(ArenaPhase.FINISHING);
        assertFalse(match.allowsExternalEscape());
        match.finalizeOnce(UUID.randomUUID());
        match.transitionTo(ArenaPhase.RESTORING);
        assertFalse(match.allowsExternalEscape());
        players.forEach(player -> match.recordRestore(
                new PlayerStateOperation(player, UUID.randomUUID(), NOW.plusSeconds(2))));
        match.completeRestore();
        assertTrue(match.allowsExternalEscape());
    }

    private static void prepareAdmission(ArenaMatch match, List<UUID> players) {
        match.transitionTo(ArenaPhase.WAITING);
        match.transitionTo(ArenaPhase.RESERVED_READY);
        players.forEach(player -> {
            match.markReady(player);
            match.recordSnapshot(new PlayerStateOperation(player, UUID.randomUUID(), NOW));
        });
    }

    private static StakedConsent consent(StakedEscrow escrow, UUID player) {
        return new StakedConsent(player, escrow.matchId(), escrow.rulesetDigest(),
                escrow.manifestDigest(), NOW.plusSeconds(1));
    }

    private static StakedEscrow escrow(UUID matchId, List<UUID> players,
                                       Map<UUID, List<StakedItem>> offered, Set<String> blacklist) {
        var normalized = new LinkedHashMap<UUID, List<StakedItem>>();
        players.forEach(player -> normalized.put(player, offered.getOrDefault(player, List.of())));
        return StakedEscrow.prepare(UUID.randomUUID(), matchId, "rules-v1", NOW,
                ArenaFormat.standard(1), new LinkedHashSet<>(players), normalized, blacklist);
    }

    private static List<UUID> players(int count) {
        var result = new ArrayList<UUID>();
        for (int index = 0; index < count; index++) result.add(UUID.randomUUID());
        return result;
    }
}
