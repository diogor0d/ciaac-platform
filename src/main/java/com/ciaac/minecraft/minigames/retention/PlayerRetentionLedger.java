package com.ciaac.minecraft.minigames.retention;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Mutable only inside {@link RetentionRepository#transaction}; projections are rebuildable from events. */
public final class PlayerRetentionLedger {
    private final UUID playerId;
    private final Map<UUID, RetentionEvent> eventsBySource = new LinkedHashMap<>();
    private final Map<LocalDate, DailyActivity> daily = new HashMap<>();
    private final Map<String, SeasonProgress> seasons = new HashMap<>();
    private final Map<String, Entitlement> entitlements = new HashMap<>();
    private final Map<String, String> selections = new HashMap<>();
    private LocalDate lastJoinDate;
    private int currentJoinStreak;
    private int longestJoinStreak;

    public PlayerRetentionLedger(UUID playerId) { this.playerId = Objects.requireNonNull(playerId, "playerId"); }

    /** Returns a detached copy, including every nested mutable projection. */
    public PlayerRetentionLedger copy() {
        PlayerRetentionLedger copy = new PlayerRetentionLedger(playerId);
        copy.eventsBySource.putAll(eventsBySource);
        daily.forEach((date, activity) -> copy.daily.put(date, activity.copy()));
        seasons.forEach((id, progress) -> copy.seasons.put(id, progress.copy()));
        copy.entitlements.putAll(entitlements);
        copy.selections.putAll(selections);
        copy.lastJoinDate = lastJoinDate;
        copy.currentJoinStreak = currentJoinStreak;
        copy.longestJoinStreak = longestJoinStreak;
        return copy;
    }

    public UUID playerId() { return playerId; }
    public int currentJoinStreak() { return currentJoinStreak; }
    public int longestJoinStreak() { return longestJoinStreak; }
    public LocalDate lastJoinDate() { return lastJoinDate; }
    public Map<String, Entitlement> entitlements() { return Map.copyOf(entitlements); }
    public Map<String, String> selections() { return Map.copyOf(selections); }
    Map<UUID, RetentionEvent> events() { return Map.copyOf(eventsBySource); }
    Map<LocalDate, DailyActivity> dailyEntries() { return Map.copyOf(daily); }
    Map<String, SeasonProgress> seasonEntries() { return Map.copyOf(seasons); }
    public SeasonProgress season(String id) { return seasons.computeIfAbsent(id, ignored -> new SeasonProgress()); }
    public SeasonProgress seasonView(String id) { return seasons.getOrDefault(id, SeasonProgress.EMPTY); }
    public boolean hasEvent(UUID sourceId) { return eventsBySource.containsKey(sourceId); }
    public boolean append(RetentionEvent event) {
        if (!event.playerId().equals(playerId)) throw new IllegalArgumentException("event belongs to another player");
        RetentionEvent existing = eventsBySource.putIfAbsent(event.sourceId(), event);
        if (existing != null && !existing.equals(event)) throw new IllegalStateException("conflicting retention event replay");
        return existing == null;
    }
    public DailyActivity day(LocalDate date) { return daily.computeIfAbsent(date, ignored -> new DailyActivity()); }
    public DailyActivity dayView(LocalDate date) { return daily.getOrDefault(date, DailyActivity.EMPTY); }
    public Entitlement entitlement(String seasonId, String rewardId) { return entitlements.get(seasonId + ":" + rewardId); }
    public void entitlement(Entitlement value) { entitlements.put(value.key(), value); }
    void select(String kind, String rewardId) {
        if (rewardId == null) selections.remove(kind); else selections.put(kind, rewardId);
    }

    void joinQualified(LocalDate date, SeasonWindow season) {
        DailyActivity day = day(date);
        if (day.joinQualified) return;
        day.joinQualified = true;
        SeasonProgress progress = season(season.id());
        if (lastJoinDate == null) {
            currentJoinStreak = 1;
            longestJoinStreak = 1;
            lastJoinDate = date;
            return;
        }
        if (date.equals(lastJoinDate)) return;
        if (date.equals(lastJoinDate.plusDays(1))) currentJoinStreak++;
        else if (date.equals(lastJoinDate.plusDays(2)) && progress.joinFreezeAvailable) {
            progress.joinFreezeAvailable = false;
            currentJoinStreak += 2;
        } else currentJoinStreak = 1;
        longestJoinStreak = Math.max(longestJoinStreak, currentJoinStreak);
        lastJoinDate = date;
    }

    void restoreJoinProjection(int current, int longest, LocalDate lastDate) {
        currentJoinStreak = current;
        longestJoinStreak = longest;
        lastJoinDate = lastDate;
    }

    void purgeDetailedBefore(Instant cutoff) {
        eventsBySource.values().removeIf(event -> event.occurredAt().isBefore(cutoff));
        LocalDate localCutoff = cutoff.atZone(LisbonSeasonCalendar.LISBON).toLocalDate();
        daily.keySet().removeIf(date -> date.isBefore(localCutoff));
        seasons.values().forEach(progress -> progress.purgeWeeksBefore(localCutoff));
    }

    public static final class DailyActivity {
        private static final DailyActivity EMPTY = new DailyActivity(true);
        private boolean joinQualified;
        private boolean activeQualified;
        private final Set<Instant> sampledMinutes = new HashSet<>();
        private DailyActivity() {}
        private DailyActivity(boolean ignored) { joinQualified = false; activeQualified = false; }
        public boolean joinQualified() { return joinQualified; }
        public boolean activeQualified() { return activeQualified; }
        public int sampledMinutes() { return sampledMinutes.size(); }
        Set<Instant> samples() { return Set.copyOf(sampledMinutes); }
        boolean addMinute(Instant minute) { return sampledMinutes.add(minute); }
        void markActiveQualified() { activeQualified = true; }
        private DailyActivity copy() {
            DailyActivity copy = new DailyActivity();
            copy.restore(joinQualified, activeQualified, sampledMinutes);
            return copy;
        }
        void restore(boolean join, boolean active, Set<Instant> samples) {
            joinQualified = join;
            activeQualified = active;
            sampledMinutes.clear();
            sampledMinutes.addAll(samples);
        }
    }

    public static final class SeasonProgress {
        private static final SeasonProgress EMPTY = new SeasonProgress(true);
        private int points;
        private int activeDays;
        private int weeklyObjectives;
        private int seasonMilestones;
        private boolean joinFreezeAvailable;
        private final Set<LocalDate> weeklyObjectiveWeeks = new HashSet<>();
        private SeasonProgress() {}
        private SeasonProgress(boolean ignored) {}
        public int points() { return points; }
        public int activeDays() { return activeDays; }
        public int weeklyObjectives() { return weeklyObjectives; }
        public int seasonMilestones() { return seasonMilestones; }
        public boolean joinFreezeAvailable() { return joinFreezeAvailable; }
        boolean addWeeklyObjective(LocalDate week) {
            if (!weeklyObjectiveWeeks.add(week)) return false;
            weeklyObjectives++;
            return true;
        }
        void addPoints(int value) { points += value; }
        void activeDay() { activeDays++; }
        void milestone() { seasonMilestones++; }
        void awardJoinFreeze() { joinFreezeAvailable = true; }
        void purgeWeeksBefore(LocalDate cutoff) { weeklyObjectiveWeeks.removeIf(week -> week.isBefore(cutoff)); }
        private SeasonProgress copy() {
            SeasonProgress copy = new SeasonProgress();
            copy.restore(points, activeDays, weeklyObjectives, seasonMilestones,
                    joinFreezeAvailable, weeklyObjectiveWeeks);
            return copy;
        }
        Set<LocalDate> weeklyWeeks() { return Set.copyOf(weeklyObjectiveWeeks); }
        void restore(int restoredPoints, int restoredActiveDays, int restoredWeeklyObjectives,
                     int restoredMilestones, boolean restoredFreezeAvailable, Set<LocalDate> weeks) {
            points = restoredPoints;
            activeDays = restoredActiveDays;
            weeklyObjectives = restoredWeeklyObjectives;
            seasonMilestones = restoredMilestones;
            joinFreezeAvailable = restoredFreezeAvailable;
            weeklyObjectiveWeeks.clear();
            weeklyObjectiveWeeks.addAll(weeks);
        }
    }
}
