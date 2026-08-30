package com.ciaac.minecraft.minigames.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ciaac.minecraft.minigames.retention.Entitlement.EntitlementState;
import com.ciaac.minecraft.minigames.retention.PassportService.ClaimOutcome;
import com.ciaac.minecraft.minigames.retention.PassportService.Outcome;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PassportServiceTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID CONNECTION = UUID.fromString("00000000-0000-0000-0000-000000000102");

    @Test void joinStreakUsesTheOneSeasonFreezeAndAwardsPointsOnce() {
        PassportService service = service(List.of(), List.of());
        for (int day = 15; day <= 17; day++) creditActiveDay(service, LocalDate.of(2026, 9, day));
        creditJoin(service, LocalDate.of(2026, 9, 18));
        creditJoin(service, LocalDate.of(2026, 9, 19));
        creditJoin(service, LocalDate.of(2026, 9, 21));
        PassportService.PassportSnapshot state = service.passport(PLAYER, at(LocalDate.of(2026, 9, 21), 12));
        assertEquals(4, state.currentJoinStreak());
        assertFalse(state.joinFreezeAvailable());
        assertEquals(58, state.points());
        creditJoin(service, LocalDate.of(2026, 9, 23));
        assertEquals(1, service.passport(PLAYER, at(LocalDate.of(2026, 9, 23), 12)).currentJoinStreak());
    }

    @Test void activeMinutesCreditAnActiveDayAndThreeDaysCompleteWeeklyObjective() {
        PassportService service = service(List.of(), List.of());
        for (int day = 15; day <= 17; day++) {
            LocalDate date = LocalDate.of(2026, 9, day);
            creditActiveDay(service, date);
        }
        PassportService.PassportSnapshot state = service.passport(PLAYER, at(LocalDate.of(2026, 9, 17), 12));
        assertEquals(3, state.activeDays());
        assertEquals(1, state.weeklyObjectives());
        assertEquals(55, state.points());
        assertTrue(state.joinFreezeAvailable());
    }

    @Test void internalRewardsAreGrantedAndExternalRewardsAreClaimedExactlyOnce() {
        RewardDescriptor internal = new RewardDescriptor("streak-two", "Duas entradas", RewardDescriptor.RewardMetric.CURRENT_JOIN_STREAK,
                2, RewardDescriptor.DeliveryKind.INTERNAL, "internal");
        RewardDescriptor external = new RewardDescriptor("pontos-um", "Música", RewardDescriptor.RewardMetric.PASSPORT_POINTS,
                1, RewardDescriptor.DeliveryKind.EXTERNAL_MANUAL_CLAIM, "gmusic");
        RewardDeliveryPort provider = new RewardDeliveryPort() {
            @Override public String id() { return "gmusic"; }
            @Override public DeliveryResult deliver(UUID operationId, UUID playerId, SeasonWindow season, RewardDescriptor reward) { return DeliveryResult.DELIVERED; }
        };
        PassportService service = service(List.of(internal, external), List.of(provider));
        creditJoin(service, LocalDate.of(2026, 9, 15)); creditJoin(service, LocalDate.of(2026, 9, 16));
        PassportService.PassportSnapshot state = service.passport(PLAYER, at(LocalDate.of(2026, 9, 16), 12));
        assertEquals(EntitlementState.GRANTED, state.entitlements().get("passport-2026-09-15:streak-two").state());
        assertEquals(ClaimOutcome.GRANTED, service.claim(PLAYER, "pontos-um", at(LocalDate.of(2026, 9, 16), 12)).outcome());
        assertEquals(ClaimOutcome.REFUSED, service.claim(PLAYER, "pontos-um", at(LocalDate.of(2026, 9, 16), 12)).outcome());
    }

    @Test void qualificationRefusesAConnectionThatHasNotLastedTenMinutes() {
        PassportService service = service(List.of(), List.of());
        Instant now = at(LocalDate.of(2026, 9, 15), 12);
        assertEquals(Outcome.NOT_QUALIFIED, service.qualifyJoin(PLAYER, CONNECTION, now.minus(Duration.ofMinutes(9)), now).outcome());
        assertThrows(IllegalStateException.class, () -> new PassportService(RetentionConfiguration.disabledDefaults(), new InMemoryRetentionRepository(), List.of(), List.of())
                .qualifyJoin(PLAYER, CONNECTION, now.minus(Duration.ofMinutes(10)), now));
    }

    @Test void priorSeasonExternalRewardCanBeClaimedDuringItsFourteenDayGrace() {
        RewardDescriptor external = new RewardDescriptor("passaporte-antigo", "Música", RewardDescriptor.RewardMetric.PASSPORT_POINTS,
                1, RewardDescriptor.DeliveryKind.EXTERNAL_MANUAL_CLAIM, "test-provider");
        RewardDeliveryPort provider = new RewardDeliveryPort() {
            @Override public String id() { return "test-provider"; }
            @Override public DeliveryResult deliver(UUID operationId, UUID playerId, SeasonWindow season, RewardDescriptor reward) { return DeliveryResult.DELIVERED; }
        };
        PassportService service = service(List.of(external), List.of(provider));
        creditJoin(service, LocalDate.of(2026, 9, 15));
        assertEquals(ClaimOutcome.GRANTED, service.claim(PLAYER, "passaporte-antigo", at(LocalDate.of(2026, 12, 16), 12)).outcome());
    }

    private static PassportService service(List<RewardDescriptor> rewards, List<RewardDeliveryPort> providers) {
        return new PassportService(new RetentionConfiguration(true, LisbonSeasonCalendar.LISBON, LisbonSeasonCalendar.ANCHOR,
                3, 14, Duration.ofMinutes(10), 15, 3, 12, false, false, false, rewards), new InMemoryRetentionRepository(), rewards, providers);
    }
    private static void creditJoin(PassportService service, LocalDate date) {
        Instant now = at(date, 12);
        assertFalse(service.qualifyJoin(PLAYER, CONNECTION, now.minus(Duration.ofMinutes(10)), now).outcome() == Outcome.NOT_QUALIFIED);
    }
    private static void creditActiveDay(PassportService service, LocalDate date) {
        for (int minute = 0; minute < 15; minute++) {
            service.sampleActiveMinute(PLAYER, CONNECTION, at(date, 10).plusSeconds(minute * 60L));
        }
    }
    private static Instant at(LocalDate date, int hour) { return date.atTime(hour, 0).atOffset(ZoneOffset.UTC).toInstant(); }
}
