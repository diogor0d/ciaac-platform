package com.ciaac.minecraft.minigames.arena;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import java.util.Locale;
import java.util.stream.Collectors;

/** Consent-bound, match-specific, platform-neutral staked equipment escrow. */
public final class StakedEscrow {
    private final UUID escrowId;
    private final UUID matchId;
    private final String rulesetDigest;
    private final Instant preparedAt;
    private final Set<UUID> participants;
    private final Map<UUID, List<StakedItem>> manifest;
    private final List<QuarantinedItem> quarantined;
    private final String manifestDigest;
    private final Map<UUID, StakedConsent> consents = new LinkedHashMap<>();
    private SettlementState state = SettlementState.OPEN;
    private UUID finalizationId;
    private UUID winner;
    private String refundReason;

    private StakedEscrow(UUID escrowId, UUID matchId, String rulesetDigest, Instant preparedAt, Set<UUID> participants,
                         Map<UUID, List<StakedItem>> manifest, List<QuarantinedItem> quarantined) {
        this.escrowId = escrowId;
        this.matchId = matchId;
        this.rulesetDigest = rulesetDigest;
        this.preparedAt = preparedAt;
        this.participants = Set.copyOf(participants);
        var copiedManifest = new LinkedHashMap<UUID, List<StakedItem>>();
        manifest.forEach((owner, items) -> copiedManifest.put(owner, List.copyOf(items)));
        this.manifest = Collections.unmodifiableMap(copiedManifest);
        this.quarantined = List.copyOf(quarantined);
        this.manifestDigest = digest(this.manifest);
    }

    public static StakedEscrow prepare(UUID escrowId, UUID matchId, String rulesetDigest,
                                       ArenaFormat format, Set<UUID> participants,
                                       Map<UUID, List<StakedItem>> offered, Set<String> blacklistedMaterials) {
        return prepare(escrowId, matchId, rulesetDigest, Instant.now(), format, participants, offered,
                blacklistedMaterials);
    }

    public static StakedEscrow prepare(UUID escrowId, UUID matchId, String rulesetDigest, Instant preparedAt,
                                       ArenaFormat format, Set<UUID> participants,
                                       Map<UUID, List<StakedItem>> offered, Set<String> blacklistedMaterials) {
        Objects.requireNonNull(escrowId, "escrowId");
        Objects.requireNonNull(matchId, "matchId");
        Objects.requireNonNull(preparedAt, "preparedAt");
        if (Objects.requireNonNull(rulesetDigest, "rulesetDigest").isBlank()) {
            throw new IllegalArgumentException("rulesetDigest cannot be blank");
        }
        Objects.requireNonNull(format, "format");
        if (!format.equals(ArenaFormat.standard(1))) {
            throw new IllegalArgumentException("The foundation permits staked equipment only in 1v1 matches");
        }
        Objects.requireNonNull(participants, "participants");
        Objects.requireNonNull(offered, "offered");
        Objects.requireNonNull(blacklistedMaterials, "blacklistedMaterials");
        if (participants.size() != 2 || participants.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("1v1 escrow needs exactly two participants");
        }
        if (!offered.keySet().equals(participants)) {
            throw new IllegalArgumentException("Every participant needs an explicit escrow manifest");
        }

        var blacklist = blacklistedMaterials.stream()
                .map(material -> Objects.requireNonNull(material, "material"))
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        var fullManifest = new LinkedHashMap<UUID, List<StakedItem>>();
        var quarantine = new ArrayList<QuarantinedItem>();
        for (UUID participant : participants) {
            var items = List.copyOf(Objects.requireNonNull(offered.get(participant), "manifest items"));
            var itemIds = new LinkedHashSet<String>();
            for (StakedItem item : items) {
                Objects.requireNonNull(item, "manifest item");
                if (!itemIds.add(item.itemId())) {
                    throw new IllegalArgumentException("Manifest item ids must be unique per owner");
                }
                if (blacklist.contains(item.material().toUpperCase(Locale.ROOT))) {
                    quarantine.add(new QuarantinedItem(participant, item, "BLACKLISTED_MATERIAL"));
                }
            }
            fullManifest.put(participant, items);
        }
        return new StakedEscrow(escrowId, matchId, rulesetDigest, preparedAt, participants, fullManifest, quarantine);
    }

    public synchronized boolean consent(StakedConsent consent) {
        Objects.requireNonNull(consent, "consent");
        requireParticipant(consent.playerId());
        if (state != SettlementState.OPEN || hasQuarantine()) return false;
        if (!matchId.equals(consent.matchId()) || !rulesetDigest.equals(consent.rulesetDigest())) {
            throw new IllegalArgumentException("Consent is bound to another match or ruleset");
        }
        if (consent.consentedAt().isBefore(preparedAt)) {
            throw new IllegalArgumentException("Consent must be fresh after escrow preparation");
        }
        if (!manifestDigest.equals(consent.manifestDigest())) {
            throw new IllegalArgumentException("Consent does not cover the current manifest");
        }
        return consents.putIfAbsent(consent.playerId(), consent) == null;
    }

    public synchronized boolean canAdmit() {
        return state == SettlementState.OPEN && !hasQuarantine() && consents.keySet().containsAll(participants);
    }

    /** Transfers the complete reviewed escrow to one participant exactly once. */
    public synchronized boolean settleToWinnerOnce(UUID resultId, UUID winner) {
        Objects.requireNonNull(resultId, "resultId");
        requireParticipant(winner);
        if (finalizationId != null) {
            if (!finalizationId.equals(resultId)
                    || state != SettlementState.SETTLED
                    || !Objects.equals(this.winner, winner)) {
                throw new IllegalStateException("Conflicting replay of an escrow settlement");
            }
            return false;
        }
        if (!canAdmit()) {
            throw new IllegalStateException("Escrow requires mutual consent and no quarantine");
        }
        finalizationId = resultId;
        this.winner = winner;
        state = SettlementState.SETTLED;
        return true;
    }

    /** Returns the complete immutable manifest exactly once for cancellation/no-contest. */
    public synchronized boolean refundOnce(UUID resultId, String reason) {
        Objects.requireNonNull(resultId, "resultId");
        reason = Objects.requireNonNull(reason, "reason").trim().toUpperCase(Locale.ROOT);
        if (!reason.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Refund reason must be a bounded machine-readable code");
        }
        if (finalizationId != null) {
            if (!finalizationId.equals(resultId)
                    || state != SettlementState.REFUNDED
                    || !Objects.equals(refundReason, reason)) {
                throw new IllegalStateException("Conflicting replay of an escrow refund");
            }
            return false;
        }
        finalizationId = resultId;
        refundReason = reason;
        state = SettlementState.REFUNDED;
        return true;
    }

    private static String digest(Map<UUID, List<StakedItem>> manifest) {
        String canonical = manifest.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(entry -> entry.getValue().stream()
                        .sorted(java.util.Comparator.comparing(StakedItem::itemId))
                        .map(item -> entry.getKey() + "|" + item.itemId() + "|" + item.material()
                                + "|" + item.amount() + "|" + item.canonicalFingerprint()))
                .collect(Collectors.joining("\n"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("JRE must provide SHA-256", exception);
        }
    }

    private void requireParticipant(UUID participant) {
        if (!participants.contains(Objects.requireNonNull(participant, "participant"))) {
            throw new IllegalArgumentException("Player is not part of this escrow");
        }
    }

    private boolean hasQuarantine() { return !quarantined.isEmpty(); }
    public UUID escrowId() { return escrowId; }
    public UUID matchId() { return matchId; }
    public String rulesetDigest() { return rulesetDigest; }
    public Instant preparedAt() { return preparedAt; }
    public Set<UUID> participants() { return participants; }
    public Map<UUID, List<StakedItem>> manifest() { return manifest; }
    public List<QuarantinedItem> quarantined() { return quarantined; }
    public String manifestDigest() { return manifestDigest; }
    public synchronized Map<UUID, StakedConsent> consents() { return Map.copyOf(consents); }
    public synchronized SettlementState state() { return state; }
    public synchronized Optional<UUID> finalizationId() { return Optional.ofNullable(finalizationId); }
    public synchronized Optional<UUID> winner() { return Optional.ofNullable(winner); }
    public synchronized Optional<String> refundReason() { return Optional.ofNullable(refundReason); }

    public enum SettlementState { OPEN, SETTLED, REFUNDED }
}
