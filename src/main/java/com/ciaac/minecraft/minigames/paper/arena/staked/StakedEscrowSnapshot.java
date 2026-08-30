package com.ciaac.minecraft.minigames.paper.arena.staked;

import com.ciaac.minecraft.minigames.arena.StakedItem;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Complete durable metadata view; payload bytes are exposed only at delivery start. */
public record StakedEscrowSnapshot(UUID escrowId, UUID matchId, String rulesetDigest,
                                   String manifestDigest, Instant preparedAt,
                                   StakedEscrowState state, Set<UUID> participants,
                                   Map<UUID, List<StakedItem>> manifest,
                                   Set<UUID> consentedParticipants,
                                   Optional<UUID> resultId, Optional<UUID> winnerId,
                                   Optional<String> refundReason, Optional<String> quarantineReason,
                                   Instant updatedAt) {
    public StakedEscrowSnapshot {
        Objects.requireNonNull(escrowId, "escrowId");
        Objects.requireNonNull(matchId, "matchId");
        if (Objects.requireNonNull(rulesetDigest, "rulesetDigest").isBlank()) {
            throw new IllegalArgumentException("rulesetDigest cannot be blank");
        }
        if (Objects.requireNonNull(manifestDigest, "manifestDigest").isBlank()) {
            throw new IllegalArgumentException("manifestDigest cannot be blank");
        }
        Objects.requireNonNull(preparedAt, "preparedAt");
        Objects.requireNonNull(state, "state");
        participants = Set.copyOf(Objects.requireNonNull(participants, "participants"));
        var copiedManifest = new java.util.LinkedHashMap<UUID, List<StakedItem>>();
        Objects.requireNonNull(manifest, "manifest").forEach((owner, items) ->
                copiedManifest.put(Objects.requireNonNull(owner, "manifest owner"),
                        List.copyOf(Objects.requireNonNull(items, "manifest items"))));
        manifest = Map.copyOf(copiedManifest);
        consentedParticipants = Set.copyOf(Objects.requireNonNull(consentedParticipants, "consentedParticipants"));
        resultId = resultId == null ? Optional.empty() : resultId;
        winnerId = winnerId == null ? Optional.empty() : winnerId;
        refundReason = refundReason == null ? Optional.empty() : refundReason;
        quarantineReason = quarantineReason == null ? Optional.empty() : quarantineReason;
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
