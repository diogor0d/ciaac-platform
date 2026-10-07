package com.ciaac.minecraft.minigames.retention;

import com.ciaac.minecraft.minigames.retention.Entitlement.EntitlementState;
import com.ciaac.minecraft.minigames.retention.PlayerRetentionLedger.DailyActivity;
import com.ciaac.minecraft.minigames.retention.PlayerRetentionLedger.SeasonProgress;
import com.ciaac.minecraft.minigames.retention.RewardDeliveryPort.DeliveryResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Commands-facing Passport service. Callers must establish current nLogin authentication before invoking it.
 * It deliberately has no Bukkit, nLogin, Vault, LuckPerms, UltraCosmetics, or GMusic dependency.
 */
public final class PassportService {
    public static final int JOIN_DAY_POINTS = 1;
    public static final int ACTIVE_DAY_POINTS = 10;
    public static final int WEEKLY_OBJECTIVE_POINTS = 25;
    private final RetentionConfiguration configuration;
    private final LisbonSeasonCalendar calendar;
    private final RetentionRepository repository;
    private final LocalDate scoringStartsOn;
    private final Map<String, RewardDescriptor> rewards;
    private final Map<String, RewardDeliveryPort> providers;

    public PassportService(RetentionConfiguration configuration, RetentionRepository repository,
                           List<RewardDescriptor> rewards, List<RewardDeliveryPort> providers) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.calendar = new LisbonSeasonCalendar(configuration.timezone(), configuration.seasonAnchor(), configuration.claimGraceDays());
        this.repository = Objects.requireNonNull(repository, "repository");
        this.scoringStartsOn = LisbonSeasonCalendar.ANCHOR;
        this.rewards = indexRewards(rewards);
        this.providers = indexProviders(providers);
    }

    public PassportService(RetentionConfiguration configuration, RetentionRepository repository,
                           List<RewardDescriptor> rewards, List<RewardDeliveryPort> providers,
                           LocalDate scoringStartsOn) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.calendar = new LisbonSeasonCalendar(configuration.timezone(), configuration.seasonAnchor(), configuration.claimGraceDays());
        this.repository = Objects.requireNonNull(repository, "repository");
        this.scoringStartsOn = Objects.requireNonNull(scoringStartsOn, "scoringStartsOn");
        this.rewards = indexRewards(rewards); this.providers = indexProviders(providers);
    }

    public RetentionConfiguration configuration() { return configuration; }
    public LisbonSeasonCalendar calendar() { return calendar; }
    public void purgeDetailedCredits(Instant now) {
        repository.purgeDetailedBefore(new RetentionPrivacyPolicy(configuration.detailRetentionMonths()).detailCutoff(now));
    }

    /** Invoked only after ten minutes of continuously current, post-nLogin authentication. */
    public QualificationResult qualifyJoin(UUID playerId, UUID connectionId, Instant authenticatedAt, Instant now) {
        requireEnabled();
        Objects.requireNonNull(playerId, "playerId"); Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt"); Objects.requireNonNull(now, "now");
        if (now.isBefore(authenticatedAt.plus(configuration.joinQualification()))) {
            return QualificationResult.notQualified("Ainda não cumpriste os 10 minutos de ligação autenticada.");
        }
        return repository.transaction(playerId, ledger -> {
            LocalDate date = calendar.localDate(now);
            if (date.isBefore(scoringStartsOn)) return QualificationResult.notQualified(
                    "A próxima época do Passaporte começa em " + scoringStartsOn + ".");
            SeasonWindow season = calendar.seasonContaining(date);
            if (season.stateOn(date) != SeasonWindow.SeasonState.ACTIVE) return QualificationResult.notQualified("A época do Passaporte não está ativa.");
            if (ledger.dayView(date).joinQualified()) return QualificationResult.alreadyCredited("A entrada de hoje já está registada.", snapshot(ledger, season));
            UUID sourceId = deterministic("join", playerId, "daily-credit", date.toString());
            RetentionEvent event = event(sourceId, playerId, now, date, RetentionEvent.Kind.JOIN_QUALIFIED, season.id(), JOIN_DAY_POINTS);
            if (!ledger.append(event)) return QualificationResult.alreadyCredited("A entrada de hoje já está registada.", snapshot(ledger, season));
            DailyActivity day = ledger.day(date);
            boolean freezeBefore = ledger.season(season.id()).joinFreezeAvailable();
            ledger.joinQualified(date, season);
            ledger.season(season.id()).addPoints(JOIN_DAY_POINTS);
            if (freezeBefore && !ledger.season(season.id()).joinFreezeAvailable()) {
                ledger.append(event(deterministic("freeze", playerId, season.id(), date.toString()), playerId, now, date,
                        RetentionEvent.Kind.JOIN_FREEZE_USED, season.id(), 0));
            }
            evaluateRewards(ledger, season, now);
            return QualificationResult.credited("Entrada diária registada no Passaporte.", snapshot(ledger, season));
        });
    }

    /** At most one distinct minute is accepted. The Paper listener must call this only after a valid activity sample. */
    public QualificationResult sampleActiveMinute(UUID playerId, UUID connectionId, Instant minute) {
        requireEnabled();
        Objects.requireNonNull(playerId, "playerId"); Objects.requireNonNull(connectionId, "connectionId"); Objects.requireNonNull(minute, "minute");
        Instant truncated = minute.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        return repository.transaction(playerId, ledger -> {
            LocalDate date = calendar.localDate(truncated);
            if (date.isBefore(scoringStartsOn)) return QualificationResult.notQualified(
                    "A próxima época do Passaporte começa em " + scoringStartsOn + ".");
            SeasonWindow season = calendar.seasonContaining(date);
            if (season.stateOn(date) != SeasonWindow.SeasonState.ACTIVE) return QualificationResult.notQualified("A época do Passaporte não está ativa.");
            DailyActivity day = ledger.day(date);
            if (day.activeQualified()) return QualificationResult.alreadyCredited("A atividade de hoje já está registada.", snapshot(ledger, season));
            UUID minuteSource = deterministic("active-minute", playerId, "sample", truncated.toString());
            if (!ledger.append(event(minuteSource, playerId, truncated, date, RetentionEvent.Kind.ACTIVE_MINUTE, season.id(), 0))) {
                return QualificationResult.alreadyCredited("Este minuto de atividade já está registado.", snapshot(ledger, season));
            }
            if (!day.addMinute(truncated) || day.sampledMinutes() < configuration.activeSampleMinutes()) {
                return QualificationResult.progress("Atividade registada: " + day.sampledMinutes() + "/" + configuration.activeSampleMinutes() + " minutos.", snapshot(ledger, season));
            }
            day.markActiveQualified();
            ledger.append(event(deterministic("active-day", playerId, season.id(), date.toString()), playerId, truncated, date,
                    RetentionEvent.Kind.ACTIVE_DAY, season.id(), ACTIVE_DAY_POINTS));
            SeasonProgress progress = ledger.season(season.id());
            progress.activeDay();
            progress.addPoints(ACTIVE_DAY_POINTS);
            awardWeeklyObjectiveIfReady(ledger, season, date, truncated);
            evaluateRewards(ledger, season, truncated);
            return QualificationResult.credited("Dia de atividade registado no Passaporte.", snapshot(ledger, season));
        });
    }

    /** Reserved for a future reviewed criterion; no minigame or external source calls it implicitly. */
    public QualificationResult awardSeasonMilestone(UUID playerId, String milestoneId, Instant now) {
        requireEnabled();
        if (milestoneId == null || !milestoneId.matches("[a-z0-9][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("milestone id is invalid");
        return repository.transaction(playerId, ledger -> {
            LocalDate date = calendar.localDate(now); SeasonWindow season = calendar.seasonContaining(date);
            if (season.stateOn(date) != SeasonWindow.SeasonState.ACTIVE) return QualificationResult.notQualified("A época do Passaporte não está ativa.");
            UUID source = deterministic("milestone", playerId, season.id(), milestoneId);
            if (ledger.hasEvent(source)) return QualificationResult.alreadyCredited("Esta conquista sazonal já está registada.", snapshot(ledger, season));
            if (!ledger.append(event(source, playerId, now, date, RetentionEvent.Kind.SEASON_MILESTONE, season.id(), WEEKLY_OBJECTIVE_POINTS))) {
                return QualificationResult.alreadyCredited("Esta conquista sazonal já está registada.", snapshot(ledger, season));
            }
            ledger.season(season.id()).milestone(); ledger.season(season.id()).addPoints(WEEKLY_OBJECTIVE_POINTS);
            evaluateRewards(ledger, season, now);
            return QualificationResult.credited("Conquista sazonal registada no Passaporte.", snapshot(ledger, season));
        });
    }

    /** Claims a configured external reward exactly once. The caller must also verify authenticated/current/no-minigame state. */
    public ClaimResult claim(UUID playerId, String rewardId, Instant now) {
        requireEnabled();
        RewardDescriptor reward = Optional.ofNullable(rewards.get(rewardId)).orElseThrow(() -> new IllegalArgumentException("reward is unknown"));
        if (reward.deliveryKind() != RewardDescriptor.DeliveryKind.EXTERNAL_MANUAL_CLAIM) return ClaimResult.refused("Esta recompensa é aplicada automaticamente.");
        SeasonWindow season = claimSeason(playerId, reward.id(), now);
        ClaimPreparation preparation = repository.transaction(playerId, ledger -> prepareClaim(ledger, playerId, season, reward, now));
        if (!preparation.ready()) return preparation.result();
        DeliveryResult delivery = deliver(playerId, season, reward, preparation.operationId());
        return repository.transaction(playerId, ledger -> completeClaim(ledger, season, reward, preparation.operationId(), now, delivery));
    }

    public PassportSnapshot passport(UUID playerId, Instant now) {
        Objects.requireNonNull(playerId, "playerId"); Objects.requireNonNull(now, "now");
        SeasonWindow season = calendar.seasonAt(now);
        return repository.findLedger(playerId)
                .map(ledger -> snapshot(ledger, season))
                .orElseGet(() -> snapshot(new PlayerRetentionLedger(playerId), season));
    }

    public String personalize(UUID playerId, String kind, String rewardId, Instant now) {
        requireEnabled();
        String normalizedKind = Objects.requireNonNull(kind, "kind").toLowerCase(java.util.Locale.ROOT);
        if (!List.of("titulo", "distintivo", "particulas").contains(normalizedKind)) {
            throw new IllegalArgumentException("Tipo de personalização desconhecido.");
        }
        return repository.transaction(playerId, ledger -> {
            if (rewardId.equalsIgnoreCase("nenhum")) {
                ledger.select(normalizedKind, null);
                return "Personalização removida.";
            }
            Entitlement unlocked = ledger.entitlements().values().stream()
                    .filter(value -> value.rewardId().equals(rewardId)
                            && value.state() == EntitlementState.GRANTED).findFirst().orElse(null);
            if (unlocked == null || !compatible(normalizedKind, rewardId)) {
                return "Essa personalização ainda não está disponível para ti.";
            }
            ledger.select(normalizedKind, rewardId);
            return "Personalização selecionada: " + rewardId + ".";
        });
    }

    private static boolean compatible(String kind, String rewardId) {
        return switch (kind) {
            case "titulo" -> rewardId.startsWith("sequencia-");
            case "distintivo" -> rewardId.startsWith("semanas-") || rewardId.startsWith("pontos-");
            case "particulas" -> rewardId.startsWith("pontos-");
            default -> false;
        };
    }

    public List<LeaderboardEntry> leaderboard(LeaderboardMetric metric, Instant now, int limit) {
        Objects.requireNonNull(metric, "metric"); Objects.requireNonNull(now, "now");
        return leaderboard(metric, calendar.seasonAt(now), limit);
    }

    public List<LeaderboardEntry> leaderboard(LeaderboardMetric metric, SeasonWindow season, int limit) {
        Objects.requireNonNull(metric, "metric"); Objects.requireNonNull(season, "season");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit is invalid");
        List<LeaderboardEntry> rows = new ArrayList<>();
        for (PlayerRetentionLedger ledger : repository.allLedgers()) rows.add(new LeaderboardEntry(ledger.playerId(), metric.value(ledger, season.id())));
        rows.sort(Comparator.comparingInt(LeaderboardEntry::value).reversed().thenComparing(entry -> entry.playerId().toString()));
        List<LeaderboardEntry> ranked = new ArrayList<>(); int previous = Integer.MIN_VALUE; int rank = 0;
        for (int index = 0; index < rows.size() && ranked.size() < limit; index++) {
            LeaderboardEntry row = rows.get(index); if (row.value() != previous) { rank = index + 1; previous = row.value(); }
            if (row.value() > 0) ranked.add(row.withRank(rank));
        }
        return List.copyOf(ranked);
    }

    public void finalizePreviousSeason(Instant now) {
        LocalDate today = calendar.localDate(now);
        SeasonWindow current = calendar.seasonContaining(today);
        SeasonWindow previous = calendar.seasonContaining(current.startsOn().minusDays(1));
        if (today.isBefore(previous.endsOnExclusive())) return;
        List<LeaderboardEntry> top = leaderboard(LeaderboardMetric.PASSPORT_POINTS, previous, 3);
        String[] metals = {"ouro", "prata", "bronze"};
        for (int index = 0; index < top.size(); index++) {
            int placement = index + 1;
            LeaderboardEntry row = top.get(index); String reward = "laurel-" + metals[index] + "-" + previous.id();
            repository.transaction(row.playerId(), ledger -> {
                if (ledger.entitlement(previous.id(), reward) == null) ledger.entitlement(new Entitlement(
                        previous.id(), reward, EntitlementState.GRANTED, now, now, null, "SEASON_TOP_" + placement));
                return null;
            });
        }
    }

    public int rank(UUID playerId, LeaderboardMetric metric, Instant now) {
        return leaderboard(metric, now, 100).stream().filter(row -> row.playerId().equals(playerId))
                .mapToInt(LeaderboardEntry::rank).findFirst().orElse(0);
    }

    private ClaimPreparation prepareClaim(PlayerRetentionLedger ledger, UUID playerId, SeasonWindow season, RewardDescriptor reward, Instant now) {
        if (season.stateOn(calendar.localDate(now)) == SeasonWindow.SeasonState.ARCHIVED || season.stateOn(calendar.localDate(now)) == SeasonWindow.SeasonState.FUTURE) {
            return ClaimPreparation.refused("Esta recompensa já não pode ser reclamada.");
        }
        Entitlement entitlement = ledger.entitlement(season.id(), reward.id());
        if (entitlement == null) return ClaimPreparation.refused("Ainda não alcançaste esta recompensa.");
        if (entitlement.state() == EntitlementState.GRANTED) return ClaimPreparation.refused("Esta recompensa já foi atribuída.");
        if (entitlement.state() == EntitlementState.GRANTING || entitlement.state() == EntitlementState.DELIVERY_PENDING || entitlement.state() == EntitlementState.MANUAL_REVIEW) {
            return ClaimPreparation.refused("A entrega desta recompensa precisa de revisão; não tentes novamente.");
        }
        UUID operation = deterministic("reward", playerId, season.id(), reward.id());
        ledger.entitlement(new Entitlement(season.id(), reward.id(), EntitlementState.GRANTING, entitlement.earnedAt(), now, operation, "DELIVERY_STARTED"));
        return ClaimPreparation.ready(operation);
    }

    private SeasonWindow claimSeason(UUID playerId, String rewardId, Instant now) {
        LocalDate date = calendar.localDate(now);
        SeasonWindow current = calendar.seasonContaining(date);
        SeasonWindow previous = calendar.seasonContaining(current.startsOn().minusDays(1));
        return repository.findLedger(playerId).map(ledger -> {
            Entitlement currentReward = ledger.entitlement(current.id(), rewardId);
            if (currentReward != null && currentReward.state() == EntitlementState.EARNED
                    && current.stateOn(date) != SeasonWindow.SeasonState.FUTURE) return current;
            Entitlement oldReward = ledger.entitlement(previous.id(), rewardId);
            if (oldReward != null && oldReward.state() == EntitlementState.EARNED
                    && previous.stateOn(date) == SeasonWindow.SeasonState.CLAIM_GRACE) return previous;
            return current;
        }).orElse(current);
    }

    private DeliveryResult deliver(UUID playerId, SeasonWindow season, RewardDescriptor reward, UUID operationId) {
        RewardDeliveryPort provider = providers.get(reward.providerId());
        try {
            return provider == null ? DeliveryResult.UNAVAILABLE : provider.deliver(operationId, playerId, season, reward);
        } catch (RuntimeException | LinkageError failure) {
            return DeliveryResult.MANUAL_REVIEW;
        }
    }

    private ClaimResult completeClaim(PlayerRetentionLedger ledger, SeasonWindow season, RewardDescriptor reward, UUID operation, Instant now, DeliveryResult delivery) {
        Entitlement current = ledger.entitlement(season.id(), reward.id());
        if (current == null || current.state() != EntitlementState.GRANTING || !operation.equals(current.operationId())) return ClaimResult.refused("O estado da recompensa mudou e precisa de revisão.");
        EntitlementState state = switch (delivery) {
            case DELIVERED -> EntitlementState.GRANTED;
            case DELIVERY_PENDING, UNAVAILABLE -> EntitlementState.DELIVERY_PENDING;
            case MANUAL_REVIEW -> EntitlementState.MANUAL_REVIEW;
        };
        String code = switch (state) {
            case GRANTED -> "DELIVERED";
            case DELIVERY_PENDING -> "DELIVERY_PENDING";
            case MANUAL_REVIEW -> "MANUAL_REVIEW";
            default -> throw new IllegalStateException("unexpected claim state");
        };
        ledger.entitlement(new Entitlement(season.id(), reward.id(), state, current.earnedAt(), now, operation, code));
        return state == EntitlementState.GRANTED ? ClaimResult.granted("Recompensa atribuída.")
                : ClaimResult.pending("A entrega foi colocada em revisão; não tentes novamente.");
    }

    private void awardWeeklyObjectiveIfReady(PlayerRetentionLedger ledger, SeasonWindow season, LocalDate date, Instant now) {
        LocalDate week = date.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        int activeDays = 0;
        for (int offset = 0; offset < 7; offset++) if (ledger.dayView(week.plusDays(offset)).activeQualified()) activeDays++;
        SeasonProgress progress = ledger.season(season.id());
        if (activeDays >= configuration.weeklyActiveDays() && progress.addWeeklyObjective(week)) {
            progress.addPoints(WEEKLY_OBJECTIVE_POINTS);
            progress.awardJoinFreeze();
            ledger.append(event(deterministic("weekly", ledger.playerId(), season.id(), week.toString()), ledger.playerId(), now, date,
                    RetentionEvent.Kind.WEEKLY_OBJECTIVE, season.id(), WEEKLY_OBJECTIVE_POINTS));
        }
    }

    private void evaluateRewards(PlayerRetentionLedger ledger, SeasonWindow season, Instant now) {
        for (RewardDescriptor reward : rewards.values()) {
            if (ledger.entitlement(season.id(), reward.id()) != null || rewardMetric(ledger, season, reward.metric()) < reward.threshold()) continue;
            EntitlementState state = reward.deliveryKind() == RewardDescriptor.DeliveryKind.INTERNAL ? EntitlementState.GRANTED : EntitlementState.EARNED;
            ledger.entitlement(new Entitlement(season.id(), reward.id(), state, now, now, null,
                    state == EntitlementState.GRANTED ? "INTERNAL_GRANTED" : "EARNED"));
        }
    }

    private int rewardMetric(PlayerRetentionLedger ledger, SeasonWindow season, RewardDescriptor.RewardMetric metric) {
        SeasonProgress progress = ledger.seasonView(season.id());
        return switch (metric) {
            case CURRENT_JOIN_STREAK -> ledger.currentJoinStreak(); case LONGEST_JOIN_STREAK -> ledger.longestJoinStreak();
            case ACTIVE_DAYS -> progress.activeDays(); case WEEKLY_OBJECTIVES -> progress.weeklyObjectives();
            case PASSPORT_POINTS -> progress.points(); case SEASON_MILESTONES -> progress.seasonMilestones();
        };
    }

    private PassportSnapshot snapshot(PlayerRetentionLedger ledger, SeasonWindow season) {
        SeasonProgress progress = ledger.seasonView(season.id());
        return new PassportSnapshot(ledger.playerId(), season, ledger.currentJoinStreak(), ledger.longestJoinStreak(), progress.activeDays(),
                progress.weeklyObjectives(), progress.points(), progress.joinFreezeAvailable(), ledger.entitlements(), ledger.selections());
    }
    private void requireEnabled() { if (!configuration.enabled()) throw new IllegalStateException("O Passaporte CIAAC está desativado."); }
    private static RetentionEvent event(UUID source, UUID player, Instant now, LocalDate date, RetentionEvent.Kind kind, String season, int points) {
        UUID eventId = UUID.nameUUIDFromBytes(("retention-event:" + source).getBytes(StandardCharsets.UTF_8));
        return new RetentionEvent(eventId, source, player, now, date, kind, season, points);
    }
    private static UUID deterministic(String domain, UUID player, String a, String b) {
        return UUID.nameUUIDFromBytes((domain + ":" + player + ":" + a + ":" + b).getBytes(StandardCharsets.UTF_8));
    }
    private static Map<String, RewardDescriptor> indexRewards(List<RewardDescriptor> values) {
        Map<String, RewardDescriptor> output = new HashMap<>(); for (RewardDescriptor reward : Objects.requireNonNull(values, "rewards")) {
            if (output.putIfAbsent(reward.id(), reward) != null) throw new IllegalArgumentException("reward ids are duplicated"); }
        return Map.copyOf(output);
    }
    private static Map<String, RewardDeliveryPort> indexProviders(List<RewardDeliveryPort> values) {
        Map<String, RewardDeliveryPort> output = new HashMap<>(); for (RewardDeliveryPort port : Objects.requireNonNull(values, "providers")) {
            if (output.putIfAbsent(port.id(), port) != null) throw new IllegalArgumentException("provider ids are duplicated"); }
        return Map.copyOf(output);
    }

    private record ClaimPreparation(UUID operationId, ClaimResult result) {
        static ClaimPreparation ready(UUID operationId) { return new ClaimPreparation(operationId, null); }
        static ClaimPreparation refused(String message) { return new ClaimPreparation(null, ClaimResult.refused(message)); }
        boolean ready() { return operationId != null; }
    }
    public record QualificationResult(Outcome outcome, String messagePtPt, PassportSnapshot snapshot) {
        static QualificationResult credited(String message, PassportSnapshot snapshot) { return new QualificationResult(Outcome.CREDITED, message, snapshot); }
        static QualificationResult progress(String message, PassportSnapshot snapshot) { return new QualificationResult(Outcome.PROGRESS, message, snapshot); }
        static QualificationResult alreadyCredited(String message, PassportSnapshot snapshot) { return new QualificationResult(Outcome.ALREADY_CREDITED, message, snapshot); }
        static QualificationResult notQualified(String message) { return new QualificationResult(Outcome.NOT_QUALIFIED, message, null); }
    }
    public enum Outcome { CREDITED, PROGRESS, ALREADY_CREDITED, NOT_QUALIFIED }
    public record ClaimResult(ClaimOutcome outcome, String messagePtPt) {
        static ClaimResult granted(String message) { return new ClaimResult(ClaimOutcome.GRANTED, message); }
        static ClaimResult pending(String message) { return new ClaimResult(ClaimOutcome.PENDING_REVIEW, message); }
        static ClaimResult refused(String message) { return new ClaimResult(ClaimOutcome.REFUSED, message); }
    }
    public enum ClaimOutcome { GRANTED, PENDING_REVIEW, REFUSED }
    public record PassportSnapshot(UUID playerId, SeasonWindow season, int currentJoinStreak, int longestJoinStreak,
                                   int activeDays, int weeklyObjectives, int points, boolean joinFreezeAvailable,
                                   Map<String, Entitlement> entitlements, Map<String, String> selections) {
        public PassportSnapshot { entitlements = Map.copyOf(entitlements); selections = Map.copyOf(selections); }
    }
    public record LeaderboardEntry(UUID playerId, int value, int rank) {
        LeaderboardEntry(UUID playerId, int value) { this(playerId, value, 0); }
        LeaderboardEntry withRank(int value) { return new LeaderboardEntry(playerId, this.value, value); }
    }
    public enum LeaderboardMetric {
        CURRENT_JOIN_STREAK, LONGEST_JOIN_STREAK, ACTIVE_DAYS, WEEKLY_OBJECTIVES, PASSPORT_POINTS;
        int value(PlayerRetentionLedger ledger, String seasonId) {
            PlayerRetentionLedger.SeasonProgress season = ledger.seasonView(seasonId);
            return switch (this) { case CURRENT_JOIN_STREAK -> ledger.currentJoinStreak(); case LONGEST_JOIN_STREAK -> ledger.longestJoinStreak();
                case ACTIVE_DAYS -> season.activeDays(); case WEEKLY_OBJECTIVES -> season.weeklyObjectives(); case PASSPORT_POINTS -> season.points(); };
        }
    }
}
