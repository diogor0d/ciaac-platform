package com.ciaac.minecraft.minigames.paper.arena.staked;

import com.ciaac.minecraft.minigames.arena.ArenaFormat;
import com.ciaac.minecraft.minigames.arena.StakedConsent;
import com.ciaac.minecraft.minigames.arena.StakedEscrow;
import com.ciaac.minecraft.minigames.arena.StakedItem;
import com.ciaac.minecraft.minigames.persistence.PersistenceFailure;
import com.ciaac.minecraft.minigames.persistence.SqliteDatabase;
import com.ciaac.minecraft.minigames.paper.arena.StakedEscrowPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SQLite-backed staked escrow boundary.  Live inventory mutation is purposely
 * absent: callers must perform it between the durable begin and commit calls.
 */
public final class SqliteStakedEscrowRepository implements StakedEscrowPort {
    private static final String RECOVERY_REASON = "UNCOMMITTED_OPERATION_AFTER_RESTART";

    private final SqliteDatabase database;

    public SqliteStakedEscrowRepository(SqliteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
        // A process restart cannot distinguish "mutation happened" from
        // "mutation did not happen".  Preserve evidence and block recovery.
        database.transaction(connection -> {
            quarantineInFlight(connection, Instant.now());
            return null;
        });
    }

    @Override
    public Optional<StakedEscrow> find(UUID escrowId) {
        Objects.requireNonNull(escrowId, "escrowId");
        return snapshot(escrowId)
                .filter(snapshot -> snapshot.state() == StakedEscrowState.PREPARED)
                .flatMap(this::rebuildPreparedDomainEscrow);
    }

    @Override
    public Optional<StakedEscrowSnapshot> snapshot(UUID escrowId) {
        Objects.requireNonNull(escrowId, "escrowId");
        return database.read(connection -> loadSnapshot(connection, escrowId));
    }

    @Override
    public StakedOperationResult prepare(PrepareRequest request) {
        Objects.requireNonNull(request, "request");
        StakedEscrow escrow = request.escrow();
        UUID operationId = request.operationId();
        UUID escrowId = escrow.escrowId();
        try {
            validateOffer(request);
        } catch (IllegalArgumentException exception) {
            String message = exception.getMessage();
            String code = "BLACKLISTED_MATERIAL".equals(message) ? "BLACKLISTED_MATERIAL"
                    : "PAYLOAD_BINDING_INVALID".equals(message) ? "PAYLOAD_BINDING_INVALID" : "OFFER_INVALID";
            // Deliberately do not journal or persist a rejected offer.  The
            // complete offer must be reviewed before any durable reservation.
            return rejected(operationId, escrowId, null, StakedEscrowState.PREPARED, code);
        }
        String digest = requestDigest("PREPARE", escrowId, escrow.matchId(), escrow.rulesetDigest(),
                escrow.manifestDigest(), request.payloads().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> entry.getKey() + ":" + entry.getValue().payloadSha256())
                        .toList(), normalizeMaterials(request.blacklistedMaterials()).stream().sorted().toList());
        return database.transaction(connection -> {
            Optional<StakedOperationResult> replay = replay(connection, operationId, StakedOperationKind.PREPARE,
                    escrowId, digest, null);
            if (replay.isPresent()) return replay.get();
            if (exists(connection, escrowId)) {
                return rejected(operationId, escrowId, null, StakedEscrowState.PREPARED, "ESCROW_EXISTS");
            }
            if (matchExists(connection, escrow.matchId())) {
                return rejected(operationId, escrowId, null, StakedEscrowState.PREPARED, "MATCH_ESCROW_EXISTS");
            }
            String now = Instant.now().toString();
            insertEscrow(connection, escrow, now);
            for (UUID participant : sorted(escrow.participants())) {
                execute(connection, """
                        INSERT INTO mg_staked_participant
                            (escrow_id, player_id, withdrawal_state)
                        VALUES (?, ?, 'PENDING')
                        """, statement -> {
                    statement.setString(1, escrowId.toString());
                    statement.setString(2, participant.toString());
                });
                for (StakedItem item : escrow.manifest().get(participant)) {
                    execute(connection, """
                            INSERT INTO mg_staked_manifest_item
                                (escrow_id, player_id, item_id, material, amount, canonical_fingerprint)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """, statement -> {
                        statement.setString(1, escrowId.toString());
                        statement.setString(2, participant.toString());
                        statement.setString(3, item.itemId());
                        statement.setString(4, item.material());
                        statement.setInt(5, item.amount());
                        statement.setString(6, item.canonicalFingerprint());
                    });
                }
                StakedInventoryPayload payload = request.payloads().get(participant);
                insertPayload(connection, escrowId, payload);
            }
            StakedOperationResult result = accepted(operationId, escrowId, null,
                    StakedEscrowState.PREPARED, "PREPARED");
            insertJournal(connection, operationId, escrowId, null, null, StakedOperationKind.PREPARE,
                    digest, null, result);
            return result;
        });
    }

    @Override
    public StakedOperationResult verifyInventory(InventoryVerificationRequest request) {
        Objects.requireNonNull(request, "request");
        String digest = requestDigest("VERIFY_INVENTORY", request.escrowId(), request.playerId(),
                request.payload().payloadSha256());
        return database.transaction(connection -> {
            Optional<StakedOperationResult> replay = replay(connection, request.operationId(),
                    StakedOperationKind.VERIFY_INVENTORY, request.escrowId(), digest, request.playerId());
            if (replay.isPresent()) return replay.get();
            Optional<EscrowRow> escrow = loadEscrow(connection, request.escrowId());
            if (escrow.isEmpty()) {
                return rejected(request.operationId(), request.escrowId(), request.playerId(),
                        StakedEscrowState.QUARANTINED, "ESCROW_NOT_FOUND");
            }
            EscrowRow row = escrow.get();
            if (row.state().recoveryBlocking()
                    || (row.state() != StakedEscrowState.PREPARED && row.state() != StakedEscrowState.CONSENTED)) {
                return recordRejected(connection, request.operationId(), request.escrowId(), request.playerId(),
                        StakedOperationKind.VERIFY_INVENTORY, digest, row.state(),
                        row.state().recoveryBlocking() ? "RECOVERY_REQUIRED" : "INVALID_STATE");
            }
            Optional<ParticipantRow> participant = participant(connection, row.escrowId(), request.playerId());
            Optional<StakedInventoryPayload> persisted = payload(connection, row, request.playerId());
            if (participant.isEmpty() || persisted.isEmpty()) {
                quarantine(connection, row.escrowId(), "PAYLOAD_RECORD_MISSING", Instant.now());
                return recordRejected(connection, request.operationId(), row.escrowId(), request.playerId(),
                        StakedOperationKind.VERIFY_INVENTORY, digest, StakedEscrowState.QUARANTINED,
                        "PAYLOAD_RECORD_MISSING");
            }
            if (!request.playerId().equals(request.payload().playerId())
                    || !persisted.get().exactlyMatches(request.payload())) {
                quarantine(connection, row.escrowId(), "INVENTORY_MISMATCH", Instant.now());
                return recordRejected(connection, request.operationId(), row.escrowId(), request.playerId(),
                        StakedOperationKind.VERIFY_INVENTORY, digest, StakedEscrowState.QUARANTINED,
                        "INVENTORY_MISMATCH");
            }
            StakedOperationResult result = accepted(request.operationId(), row.escrowId(), request.playerId(),
                    row.state(), "INVENTORY_VERIFIED");
            insertJournal(connection, request.operationId(), row.escrowId(), request.playerId(), null,
                    StakedOperationKind.VERIFY_INVENTORY, digest, row.state(), result);
            return result;
        });
    }

    @Override
    public StakedOperationResult consent(ConsentRequest request) {
        Objects.requireNonNull(request, "request");
        StakedConsent consent = request.consent();
        String digest = requestDigest("CONSENT", request.escrowId(), consent.playerId(), consent.matchId(),
                consent.rulesetDigest(), consent.manifestDigest(), consent.consentedAt());
        return database.transaction(connection -> {
            Optional<StakedOperationResult> replay = replay(connection, request.operationId(),
                    StakedOperationKind.CONSENT, request.escrowId(), digest, consent.playerId());
            if (replay.isPresent()) return replay.get();
            Optional<EscrowRow> escrow = loadEscrow(connection, request.escrowId());
            if (escrow.isEmpty()) {
                return rejected(request.operationId(), request.escrowId(), consent.playerId(),
                        StakedEscrowState.QUARANTINED, "ESCROW_NOT_FOUND");
            }
            EscrowRow row = escrow.get();
            if (row.state() != StakedEscrowState.PREPARED && row.state() != StakedEscrowState.CONSENTED) {
                return recordRejected(connection, request.operationId(), row.escrowId(), consent.playerId(),
                        StakedOperationKind.CONSENT, digest, row.state(),
                        row.state().recoveryBlocking() ? "RECOVERY_REQUIRED" : "INVALID_STATE");
            }
            Optional<ParticipantRow> participant = participant(connection, row.escrowId(), consent.playerId());
            if (participant.isEmpty()) {
                return recordRejected(connection, request.operationId(), row.escrowId(), consent.playerId(),
                        StakedOperationKind.CONSENT, digest, row.state(), "NOT_A_PARTICIPANT");
            }
            if (!row.matchId().equals(consent.matchId()) || !row.rulesetDigest().equals(consent.rulesetDigest())
                    || !row.manifestDigest().equals(consent.manifestDigest())
                    || consent.consentedAt().isBefore(row.preparedAt())) {
                quarantine(connection, row.escrowId(), "CONSENT_BINDING_INVALID", Instant.now());
                return recordRejected(connection, request.operationId(), row.escrowId(), consent.playerId(),
                        StakedOperationKind.CONSENT,
                        digest, StakedEscrowState.QUARANTINED, "CONSENT_BINDING_INVALID");
            }
            ParticipantRow existing = participant.get();
            if (existing.consentedAt() != null) {
                boolean same = existing.consentMatchId().equals(consent.matchId())
                        && existing.consentRulesetDigest().equals(consent.rulesetDigest())
                        && existing.consentManifestDigest().equals(consent.manifestDigest())
                        && existing.consentedAt().equals(consent.consentedAt());
                if (!same) {
                    quarantine(connection, row.escrowId(), "CONSENT_REPLAY_CONFLICT", Instant.now());
                    return recordRejected(connection, request.operationId(), row.escrowId(), consent.playerId(),
                            StakedOperationKind.CONSENT,
                            digest, StakedEscrowState.QUARANTINED, "CONSENT_REPLAY_CONFLICT");
                }
                StakedOperationResult result = accepted(request.operationId(), row.escrowId(), consent.playerId(),
                        row.state(), "CONSENT_ALREADY_RECORDED");
                insertJournal(connection, request.operationId(), row.escrowId(), consent.playerId(), null,
                        StakedOperationKind.CONSENT, digest, row.state(), result);
                return result;
            }
            execute(connection, """
                    UPDATE mg_staked_participant
                    SET consented_at = ?, consent_match_id = ?, consent_ruleset_digest = ?,
                        consent_manifest_digest = ?
                    WHERE escrow_id = ? AND player_id = ?
                    """, statement -> {
                statement.setString(1, consent.consentedAt().toString());
                statement.setString(2, consent.matchId().toString());
                statement.setString(3, consent.rulesetDigest());
                statement.setString(4, consent.manifestDigest());
                statement.setString(5, row.escrowId().toString());
                statement.setString(6, consent.playerId().toString());
            });
            StakedEscrowState next = allConsented(connection, row.escrowId())
                    ? StakedEscrowState.CONSENTED : StakedEscrowState.PREPARED;
            if (!row.state().canTransitionTo(next)) {
                throw new PersistenceFailure("Invalid durable consent transition");
            }
            if (next != row.state()) updateEscrowState(connection, row, next, Instant.now(), null);
            StakedOperationResult result = accepted(request.operationId(), row.escrowId(), consent.playerId(),
                    next, "CONSENT_RECORDED");
            insertJournal(connection, request.operationId(), row.escrowId(), consent.playerId(), null,
                    StakedOperationKind.CONSENT, digest, row.state(), result);
            return result;
        });
    }

    @Override
    public StakedOperationResult beginWithdrawal(WithdrawalRequest request) {
        Objects.requireNonNull(request, "request");
        String digest = requestDigest("BEGIN_WITHDRAWAL", request.escrowId(), request.playerId(),
                request.payloadDigest());
        return database.transaction(connection -> {
            Optional<StakedOperationResult> replay = replay(connection, request.operationId(),
                    StakedOperationKind.BEGIN_WITHDRAWAL, request.escrowId(), digest, request.playerId());
            if (replay.isPresent()) return replay.get();
            Optional<EscrowRow> escrow = loadEscrow(connection, request.escrowId());
            if (escrow.isEmpty()) return rejected(request.operationId(), request.escrowId(), request.playerId(),
                    StakedEscrowState.QUARANTINED, "ESCROW_NOT_FOUND");
            EscrowRow row = escrow.get();
            Optional<ParticipantRow> participant = participant(connection, row.escrowId(), request.playerId());
            Optional<StakedInventoryPayload> payload = payload(connection, row, request.playerId());
            if (participant.isEmpty() || payload.isEmpty()) {
                quarantine(connection, row.escrowId(), "WITHDRAWAL_RECORD_MISSING", Instant.now());
                return recordRejected(connection, request.operationId(), row.escrowId(), request.playerId(),
                        StakedOperationKind.BEGIN_WITHDRAWAL, digest, StakedEscrowState.QUARANTINED,
                        "WITHDRAWAL_RECORD_MISSING");
            }
            if (row.state() != StakedEscrowState.CONSENTED) {
                String code = row.state().recoveryBlocking() ? "RECOVERY_REQUIRED" : "CONSENT_REQUIRED";
                return recordRejected(connection, request.operationId(), row.escrowId(), request.playerId(),
                        StakedOperationKind.BEGIN_WITHDRAWAL, digest, row.state(), code);
            }
            ParticipantRow current = participant.get();
            if (current.withdrawalState() == ParticipantWithdrawalState.WITHDRAWN) {
                return recordRejected(connection, request.operationId(), row.escrowId(), request.playerId(),
                        StakedOperationKind.BEGIN_WITHDRAWAL, digest, row.state(), "ALREADY_WITHDRAWN");
            }
            if (current.withdrawalState() != ParticipantWithdrawalState.PENDING) {
                return recordRejected(connection, request.operationId(), row.escrowId(), request.playerId(),
                        StakedOperationKind.BEGIN_WITHDRAWAL, digest, StakedEscrowState.QUARANTINED,
                        "RECOVERY_REQUIRED");
            }
            if (!payload.get().payloadSha256().equals(request.payloadDigest())) {
                quarantine(connection, row.escrowId(), "PAYLOAD_DIGEST_MISMATCH", Instant.now());
                return recordRejected(connection, request.operationId(), row.escrowId(), request.playerId(),
                        StakedOperationKind.BEGIN_WITHDRAWAL, digest, StakedEscrowState.QUARANTINED,
                        "PAYLOAD_DIGEST_MISMATCH");
            }
            if (!inventoryVerified(connection, row.escrowId(), request.playerId(), request.payloadDigest())) {
                return recordRejected(connection, request.operationId(), row.escrowId(), request.playerId(),
                        StakedOperationKind.BEGIN_WITHDRAWAL, digest, row.state(),
                        "INVENTORY_VERIFICATION_REQUIRED");
            }
            execute(connection, """
                    UPDATE mg_staked_participant
                    SET withdrawal_state = 'WITHDRAWING', withdrawal_operation_id = ?
                    WHERE escrow_id = ? AND player_id = ?
                    """, statement -> {
                statement.setString(1, request.operationId().toString());
                statement.setString(2, row.escrowId().toString());
                statement.setString(3, request.playerId().toString());
            });
            updateEscrowState(connection, row, StakedEscrowState.WITHDRAWING, Instant.now(), null);
            StakedOperationResult result = accepted(request.operationId(), row.escrowId(), request.playerId(),
                    StakedEscrowState.WITHDRAWING, "WITHDRAWAL_STARTED");
            insertJournal(connection, request.operationId(), row.escrowId(), request.playerId(), null,
                    StakedOperationKind.BEGIN_WITHDRAWAL, digest, row.state(), result);
            return result;
        });
    }

    @Override
    public StakedOperationResult commitWithdrawal(WithdrawalCommitRequest request) {
        Objects.requireNonNull(request, "request");
        String digest = requestDigest("COMMIT_WITHDRAWAL", request.escrowId(), request.playerId(),
                request.beginOperationId(), request.payloadDigest());
        return database.transaction(connection -> {
            Optional<StakedOperationResult> replay = replay(connection, request.operationId(),
                    StakedOperationKind.COMMIT_WITHDRAWAL, request.escrowId(), digest, request.playerId());
            if (replay.isPresent()) return replay.get();
            Optional<EscrowRow> escrow = loadEscrow(connection, request.escrowId());
            if (escrow.isEmpty()) return rejected(request.operationId(), request.escrowId(), request.playerId(),
                    StakedEscrowState.QUARANTINED, "ESCROW_NOT_FOUND");
            EscrowRow row = escrow.get();
            Optional<ParticipantRow> participant = participant(connection, row.escrowId(), request.playerId());
            Optional<StakedInventoryPayload> payload = payload(connection, row, request.playerId());
            if (row.state() != StakedEscrowState.WITHDRAWING || participant.isEmpty() || payload.isEmpty()) {
                return recordRejected(connection, request.operationId(), row.escrowId(), request.playerId(),
                        StakedOperationKind.COMMIT_WITHDRAWAL, digest, row.state(),
                        row.state().recoveryBlocking() ? "RECOVERY_REQUIRED" : "INVALID_STATE");
            }
            ParticipantRow current = participant.get();
            if (current.withdrawalState() != ParticipantWithdrawalState.WITHDRAWING
                    || !request.beginOperationId().toString().equals(current.withdrawalOperationId())
                    || !payload.get().payloadSha256().equals(request.payloadDigest())) {
                quarantine(connection, row.escrowId(), "WITHDRAWAL_COMMIT_CONFLICT", Instant.now());
                return recordRejected(connection, request.operationId(), row.escrowId(), request.playerId(),
                        StakedOperationKind.COMMIT_WITHDRAWAL, digest, StakedEscrowState.QUARANTINED,
                        "WITHDRAWAL_COMMIT_CONFLICT");
            }
            execute(connection, """
                    UPDATE mg_staked_participant
                    SET withdrawal_state = 'WITHDRAWN', withdrawal_operation_id = NULL
                    WHERE escrow_id = ? AND player_id = ?
                    """, statement -> {
                statement.setString(1, row.escrowId().toString());
                statement.setString(2, request.playerId().toString());
            });
            StakedEscrowState next = allWithdrawn(connection, row.escrowId())
                    ? StakedEscrowState.WITHDRAWN : StakedEscrowState.CONSENTED;
            updateEscrowState(connection, row, next, Instant.now(), null);
            StakedOperationResult result = accepted(request.operationId(), row.escrowId(), request.playerId(),
                    next, "WITHDRAWAL_COMMITTED");
            insertJournal(connection, request.operationId(), row.escrowId(), request.playerId(), null,
                    StakedOperationKind.COMMIT_WITHDRAWAL, digest, row.state(), result);
            return result;
        });
    }

    @Override
    public StakedOperationResult settle(SettlementRequest request) {
        Objects.requireNonNull(request, "request");
        String digest = requestDigest("SETTLE", request.escrowId(), request.resultId(), request.winnerId());
        return database.transaction(connection -> {
            Optional<StakedOperationResult> replay = replay(connection, request.operationId(),
                    StakedOperationKind.SETTLE, request.escrowId(), digest, request.winnerId());
            if (replay.isPresent()) return replay.get();
            Optional<EscrowRow> escrow = loadEscrow(connection, request.escrowId());
            if (escrow.isEmpty()) return rejected(request.operationId(), request.escrowId(), request.winnerId(),
                    StakedEscrowState.QUARANTINED, "ESCROW_NOT_FOUND");
            EscrowRow row = escrow.get();
            if (row.state() != StakedEscrowState.WITHDRAWN) {
                return recordRejected(connection, request.operationId(), row.escrowId(), request.winnerId(),
                        StakedOperationKind.SETTLE, digest, row.state(),
                        row.state().recoveryBlocking() ? "RECOVERY_REQUIRED" : "WITHDRAWAL_REQUIRED");
            }
            if (!participant(connection, row.escrowId(), request.winnerId()).isPresent()
                    || !allWithdrawn(connection, row.escrowId())) {
                return recordRejected(connection, request.operationId(), row.escrowId(), request.winnerId(),
                        StakedOperationKind.SETTLE, digest, row.state(), "INVALID_PARTICIPANT_STATE");
            }
            if (row.resultId() != null) {
                return recordRejected(connection, request.operationId(), row.escrowId(), request.winnerId(),
                        StakedOperationKind.SETTLE, digest, row.state(), "ALREADY_FINALIZED");
            }
            for (UUID source : sorted(participants(connection, row.escrowId()))) {
                UUID claimId = UUID.nameUUIDFromBytes(("settlement:" + request.resultId() + ":" + source)
                        .getBytes(StandardCharsets.UTF_8));
                Optional<StakedInventoryPayload> payload = payload(connection, row, source);
                if (payload.isEmpty()) {
                    quarantine(connection, row.escrowId(), "SETTLEMENT_PAYLOAD_CORRUPT", Instant.now());
                    return recordRejected(connection, request.operationId(), row.escrowId(), request.winnerId(),
                            StakedOperationKind.SETTLE, digest, StakedEscrowState.QUARANTINED,
                            "SETTLEMENT_PAYLOAD_CORRUPT");
                }
                insertClaim(connection, claimId, row.escrowId(), source, request.winnerId(), request.resultId(),
                        StakedClaimKind.SETTLEMENT, payload.get().payloadSha256(), StakedClaimState.PENDING, null,
                        Instant.now());
            }
            updateEscrowFinalization(connection, row, StakedEscrowState.SETTLED, request.resultId(),
                    request.winnerId(), null, Instant.now());
            StakedOperationResult result = accepted(request.operationId(), row.escrowId(), request.winnerId(),
                    StakedEscrowState.SETTLED, "SETTLED");
            insertJournal(connection, request.operationId(), row.escrowId(), request.winnerId(), null,
                    StakedOperationKind.SETTLE, digest, row.state(), result);
            return result;
        });
    }

    @Override
    public StakedOperationResult refund(RefundRequest request) {
        Objects.requireNonNull(request, "request");
        String digest = requestDigest("REFUND", request.escrowId(), request.resultId(), request.reason(),
                request.beneficiaries().entrySet().stream().sorted(Map.Entry.comparingByKey())
                        .map(entry -> entry.getKey() + ":" + entry.getValue()).toList());
        return database.transaction(connection -> {
            Optional<StakedOperationResult> replay = replay(connection, request.operationId(),
                    StakedOperationKind.REFUND, request.escrowId(), digest, null);
            if (replay.isPresent()) return replay.get();
            Optional<EscrowRow> escrow = loadEscrow(connection, request.escrowId());
            if (escrow.isEmpty()) return rejected(request.operationId(), request.escrowId(), null,
                    StakedEscrowState.QUARANTINED, "ESCROW_NOT_FOUND");
            EscrowRow row = escrow.get();
            Set<UUID> participants = participants(connection, row.escrowId());
            if (!request.beneficiaries().keySet().equals(participants)
                    || request.beneficiaries().values().stream().anyMatch(Objects::isNull)) {
                return recordRejected(connection, request.operationId(), row.escrowId(), null,
                        StakedOperationKind.REFUND, digest, row.state(), "BENEFICIARY_SET_INVALID");
            }
            if (row.state() == StakedEscrowState.WITHDRAWING || row.state() == StakedEscrowState.DELIVERING
                    || row.state() == StakedEscrowState.QUARANTINED) {
                return recordRejected(connection, request.operationId(), row.escrowId(), null,
                        StakedOperationKind.REFUND, digest, row.state(), "RECOVERY_REQUIRED");
            }
            if (row.resultId() != null || row.state() == StakedEscrowState.SETTLED
                    || row.state() == StakedEscrowState.DELIVERY_PENDING
                    || row.state() == StakedEscrowState.DELIVERED) {
                return recordRejected(connection, request.operationId(), row.escrowId(), null,
                        StakedOperationKind.REFUND, digest, row.state(), "ALREADY_FINALIZED");
            }
            StakedEscrowState from = row.state();
            if (!from.canTransitionTo(StakedEscrowState.REFUNDING)) {
                return recordRejected(connection, request.operationId(), row.escrowId(), null,
                        StakedOperationKind.REFUND, digest, from, "INVALID_STATE");
            }
            updateEscrowState(connection, row, StakedEscrowState.REFUNDING, Instant.now(), null);
            int claims = 0;
            for (UUID source : sorted(participants)) {
                ParticipantRow participant = participant(connection, row.escrowId(), source).orElseThrow(
                        () -> new PersistenceFailure("Refund participant is missing"));
                if (participant.withdrawalState() != ParticipantWithdrawalState.WITHDRAWN) continue;
                Optional<StakedInventoryPayload> payload = payload(connection, row, source);
                if (payload.isEmpty()) {
                    quarantine(connection, row.escrowId(), "REFUND_PAYLOAD_CORRUPT", Instant.now());
                    return recordRejected(connection, request.operationId(), row.escrowId(), null,
                            StakedOperationKind.REFUND, digest, StakedEscrowState.QUARANTINED,
                            "REFUND_PAYLOAD_CORRUPT");
                }
                UUID claimId = UUID.nameUUIDFromBytes(("refund:" + request.resultId() + ":" + source)
                        .getBytes(StandardCharsets.UTF_8));
                insertClaim(connection, claimId, row.escrowId(), source, request.beneficiaries().get(source),
                        request.resultId(), StakedClaimKind.REFUND, payload.get().payloadSha256(), StakedClaimState.PENDING,
                        null, Instant.now());
                claims++;
            }
            StakedEscrowState next = claims == 0 ? StakedEscrowState.DELIVERED : StakedEscrowState.DELIVERY_PENDING;
            EscrowRow refunding = rowWithState(row, StakedEscrowState.REFUNDING);
            updateEscrowFinalization(connection, refunding, next, request.resultId(), null, request.reason(), Instant.now());
            StakedOperationResult result = accepted(request.operationId(), row.escrowId(), null, next,
                    claims == 0 ? "REFUNDED_NO_WITHDRAWAL" : "REFUND_RECORDED");
            insertJournal(connection, request.operationId(), row.escrowId(), null, null,
                    StakedOperationKind.REFUND, digest, from, result);
            return result;
        });
    }

    @Override
    public List<StakedClaim> pendingClaims(UUID escrowId) {
        Objects.requireNonNull(escrowId, "escrowId");
        return database.read(connection -> {
            List<StakedClaim> claims = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT claim_id, escrow_id, source_player_id, beneficiary_id, result_id, kind,
                           payload_digest, state, begin_operation_id, updated_at
                    FROM mg_staked_claim
                    WHERE escrow_id = ? AND state IN ('PENDING', 'DELIVERING', 'QUARANTINED')
                    ORDER BY claim_id
                    """)) {
                statement.setString(1, escrowId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) claims.add(readClaim(result));
                }
            }
            return List.copyOf(claims);
        });
    }

    @Override
    public List<StakedClaim> pendingClaimsFor(UUID beneficiaryId) {
        Objects.requireNonNull(beneficiaryId, "beneficiaryId");
        return database.read(connection -> {
            List<StakedClaim> claims = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT claim_id, escrow_id, source_player_id, beneficiary_id, result_id, kind,
                           payload_digest, state, begin_operation_id, updated_at
                    FROM mg_staked_claim
                    WHERE beneficiary_id = ? AND state = 'PENDING'
                    ORDER BY CASE WHEN source_player_id = beneficiary_id THEN 0 ELSE 1 END,
                             updated_at, claim_id
                    """)) {
                statement.setString(1, beneficiaryId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) claims.add(readClaim(result));
                }
            }
            return List.copyOf(claims);
        });
    }

    @Override
    public Optional<StakedInventoryPayload> previewClaim(UUID escrowId, UUID claimId,
                                                         UUID beneficiaryId) {
        Objects.requireNonNull(escrowId, "escrowId");
        Objects.requireNonNull(claimId, "claimId");
        Objects.requireNonNull(beneficiaryId, "beneficiaryId");
        return database.read(connection -> {
            Optional<EscrowRow> escrow = loadEscrow(connection, escrowId);
            Optional<ClaimRow> claim = claim(connection, claimId);
            if (escrow.isEmpty() || claim.isEmpty()) return Optional.empty();
            StakedClaim value = claim.get().claim();
            if (!value.escrowId().equals(escrowId)
                    || !value.beneficiaryId().equals(beneficiaryId)
                    || value.state() != StakedClaimState.PENDING) return Optional.empty();
            Optional<StakedInventoryPayload> payload = payload(
                    connection, escrow.get(), value.sourcePlayerId());
            return payload.filter(candidate -> candidate.payloadSha256().equals(value.payloadDigest()));
        });
    }

    @Override
    public List<StakedEscrowSnapshot> outstandingEscrows() {
        return database.read(connection -> {
            List<UUID> ids = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT escrow_id FROM mg_staked_escrow
                    WHERE state <> 'DELIVERED' ORDER BY prepared_at, escrow_id
                    """)) {
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) ids.add(UUID.fromString(result.getString(1)));
                }
            }
            List<StakedEscrowSnapshot> snapshots = new ArrayList<>();
            for (UUID id : ids) loadSnapshot(connection, id).ifPresent(snapshots::add);
            return List.copyOf(snapshots);
        });
    }

    @Override
    public StakedDeliveryStart beginDelivery(DeliveryBeginRequest request) {
        Objects.requireNonNull(request, "request");
        String digest = requestDigest("BEGIN_DELIVERY", request.escrowId(), request.claimId(),
                request.beneficiaryId(), request.payloadDigest());
        return database.transaction(connection -> {
            Optional<StakedOperationResult> replay = replay(connection, request.operationId(),
                    StakedOperationKind.BEGIN_DELIVERY, request.escrowId(), digest, null);
            if (replay.isPresent()) return replayDelivery(connection, replay.get(), request.claimId());
            Optional<EscrowRow> escrow = loadEscrow(connection, request.escrowId());
            if (escrow.isEmpty()) {
                return deliveryRejected(rejected(request.operationId(), request.escrowId(), null,
                        StakedEscrowState.QUARANTINED, "ESCROW_NOT_FOUND"));
            }
            EscrowRow row = escrow.get();
            Optional<ClaimRow> claim = claim(connection, request.claimId());
            if (claim.isEmpty() || !claim.get().claim().escrowId().equals(row.escrowId())) {
                return deliveryRejected(recordRejected(connection, request.operationId(), row.escrowId(), null,
                        StakedOperationKind.BEGIN_DELIVERY, digest, row.state(), "CLAIM_NOT_FOUND"));
            }
            ClaimRow current = claim.get();
            if (current.claim().state() == StakedClaimState.DELIVERING
                    || current.claim().state() == StakedClaimState.QUARANTINED
                    || row.state() == StakedEscrowState.DELIVERING
                    || row.state() == StakedEscrowState.QUARANTINED) {
                return deliveryRejected(recordRejected(connection, request.operationId(), row.escrowId(), null,
                        StakedOperationKind.BEGIN_DELIVERY, digest, row.state(), "RECOVERY_REQUIRED"));
            }
            if (current.claim().state() == StakedClaimState.DELIVERED) {
                return deliveryRejected(recordRejected(connection, request.operationId(), row.escrowId(), null,
                        StakedOperationKind.BEGIN_DELIVERY, digest, row.state(), "ALREADY_DELIVERED"));
            }
            if (!current.claim().beneficiaryId().equals(request.beneficiaryId())
                    || !current.claim().payloadDigest().equals(request.payloadDigest())
                    || current.claim().state() != StakedClaimState.PENDING) {
                quarantine(connection, row.escrowId(), "DELIVERY_BINDING_INVALID", Instant.now());
                return deliveryRejected(recordRejected(connection, request.operationId(), row.escrowId(), null,
                        StakedOperationKind.BEGIN_DELIVERY, digest, StakedEscrowState.QUARANTINED,
                        "DELIVERY_BINDING_INVALID"));
            }
            if (row.state() != StakedEscrowState.SETTLED && row.state() != StakedEscrowState.DELIVERY_PENDING) {
                return deliveryRejected(recordRejected(connection, request.operationId(), row.escrowId(), null,
                        StakedOperationKind.BEGIN_DELIVERY, digest, row.state(), "DELIVERY_NOT_PENDING"));
            }
            if (hasActiveDelivery(connection, row.escrowId())) {
                return deliveryRejected(recordRejected(connection, request.operationId(), row.escrowId(), null,
                        StakedOperationKind.BEGIN_DELIVERY, digest, row.state(), "RECOVERY_REQUIRED"));
            }
            Optional<StakedInventoryPayload> payload = payload(connection, row, current.claim().sourcePlayerId());
            if (payload.isEmpty() || !payload.get().payloadSha256().equals(current.claim().payloadDigest())) {
                quarantine(connection, row.escrowId(), "DELIVERY_PAYLOAD_CORRUPT", Instant.now());
                return deliveryRejected(recordRejected(connection, request.operationId(), row.escrowId(), null,
                        StakedOperationKind.BEGIN_DELIVERY, digest, StakedEscrowState.QUARANTINED,
                        "DELIVERY_PAYLOAD_CORRUPT"));
            }
            execute(connection, """
                    UPDATE mg_staked_claim
                    SET state = 'DELIVERING', begin_operation_id = ?, updated_at = ?
                    WHERE claim_id = ? AND state = 'PENDING'
                    """, statement -> {
                statement.setString(1, request.operationId().toString());
                statement.setString(2, Instant.now().toString());
                statement.setString(3, request.claimId().toString());
            });
            updateEscrowState(connection, row, StakedEscrowState.DELIVERING, Instant.now(), null);
            StakedOperationResult result = accepted(request.operationId(), row.escrowId(), null,
                    StakedEscrowState.DELIVERING, "DELIVERY_STARTED");
            insertJournal(connection, request.operationId(), row.escrowId(), null, request.claimId(),
                    StakedOperationKind.BEGIN_DELIVERY, digest, row.state(), result);
            return new StakedDeliveryStart(result,
                    Optional.of(readClaim(connection, request.claimId())), Optional.of(payload.get()));
        });
    }

    @Override
    public StakedOperationResult commitDelivery(DeliveryCommitRequest request) {
        Objects.requireNonNull(request, "request");
        String digest = requestDigest("COMMIT_DELIVERY", request.escrowId(), request.claimId(),
                request.beginOperationId(), request.payloadDigest());
        return database.transaction(connection -> {
            Optional<StakedOperationResult> replay = replay(connection, request.operationId(),
                    StakedOperationKind.COMMIT_DELIVERY, request.escrowId(), digest, null);
            if (replay.isPresent()) return replay.get();
            Optional<EscrowRow> escrow = loadEscrow(connection, request.escrowId());
            if (escrow.isEmpty()) return rejected(request.operationId(), request.escrowId(), null,
                    StakedEscrowState.QUARANTINED, "ESCROW_NOT_FOUND");
            EscrowRow row = escrow.get();
            Optional<ClaimRow> claim = claim(connection, request.claimId());
            if (claim.isEmpty() || !claim.get().claim().escrowId().equals(row.escrowId())) {
                return recordRejected(connection, request.operationId(), row.escrowId(), null,
                        StakedOperationKind.COMMIT_DELIVERY, digest, row.state(), "CLAIM_NOT_FOUND");
            }
            StakedClaim current = claim.get().claim();
            if (row.state() != StakedEscrowState.DELIVERING || current.state() != StakedClaimState.DELIVERING
                    || current.beginOperationId().isEmpty()
                    || !current.beginOperationId().get().equals(request.beginOperationId())
                    || !current.payloadDigest().equals(request.payloadDigest())) {
                quarantine(connection, row.escrowId(), "DELIVERY_COMMIT_CONFLICT", Instant.now());
                return recordRejected(connection, request.operationId(), row.escrowId(), null,
                        StakedOperationKind.COMMIT_DELIVERY, digest, StakedEscrowState.QUARANTINED,
                        "DELIVERY_COMMIT_CONFLICT");
            }
            execute(connection, """
                    UPDATE mg_staked_claim
                    SET state = 'DELIVERED', begin_operation_id = NULL, updated_at = ?
                    WHERE claim_id = ? AND state = 'DELIVERING'
                    """, statement -> {
                statement.setString(1, Instant.now().toString());
                statement.setString(2, request.claimId().toString());
            });
            StakedEscrowState next = hasPendingClaim(connection, row.escrowId())
                    ? StakedEscrowState.DELIVERY_PENDING : StakedEscrowState.DELIVERED;
            updateEscrowState(connection, row, next, Instant.now(), null);
            StakedOperationResult result = accepted(request.operationId(), row.escrowId(), null, next,
                    "DELIVERY_COMMITTED");
            insertJournal(connection, request.operationId(), row.escrowId(), null, request.claimId(),
                    StakedOperationKind.COMMIT_DELIVERY, digest, row.state(), result);
            return result;
        });
    }

    @Override
    public Optional<StakedRecoveryInspection> blockingRecovery(UUID escrowId) {
        Objects.requireNonNull(escrowId, "escrowId");
        return database.read(connection -> {
            Optional<EscrowRow> escrow = loadEscrow(connection, escrowId);
            if (escrow.isEmpty()) return Optional.empty();
            EscrowRow row = escrow.get();
            Optional<UUID> participant = inFlightParticipant(connection, escrowId);
            Optional<UUID> claim = inFlightClaim(connection, escrowId);
            boolean blocked = row.state().recoveryBlocking() || participant.isPresent() || claim.isPresent();
            String code = row.state() == StakedEscrowState.QUARANTINED ? "QUARANTINED_RECOVERY_REQUIRED"
                    : row.state() == StakedEscrowState.WITHDRAWING || participant.isPresent()
                    ? "WITHDRAWAL_AMBIGUOUS"
                    : row.state() == StakedEscrowState.DELIVERING || claim.isPresent()
                    ? "DELIVERY_AMBIGUOUS"
                    : row.state() == StakedEscrowState.REFUNDING
                    ? "REFUND_AMBIGUOUS" : "NO_BLOCKING_RECOVERY";
            return Optional.of(new StakedRecoveryInspection(escrowId, row.state(), blocked, code, participant,
                    claim, operationKinds(connection, escrowId)));
        });
    }

    private static void validateOffer(PrepareRequest request) {
        StakedEscrow escrow = request.escrow();
        if (escrow.state() != StakedEscrow.SettlementState.OPEN || !escrow.quarantined().isEmpty()) {
            throw new IllegalArgumentException("Staked offer is not cleanly prepared");
        }
        if (!request.payloads().keySet().equals(escrow.participants())) {
            throw new IllegalArgumentException("Every staked participant needs an exact payload");
        }
        Set<String> blacklist = normalizeMaterials(request.blacklistedMaterials());
        for (Map.Entry<UUID, List<StakedItem>> entry : escrow.manifest().entrySet()) {
            for (StakedItem item : entry.getValue()) {
                if (blacklist.contains(normalizeMaterial(item.material()))
                        || blacklist.contains(shortMaterial(item.material()))) {
                    throw new IllegalArgumentException("BLACKLISTED_MATERIAL");
                }
            }
            StakedInventoryPayload payload = request.payloads().get(entry.getKey());
            if (!entry.getKey().equals(payload.playerId())
                    || !escrow.manifestDigest().equals(payload.manifestDigest())) {
                throw new IllegalArgumentException("PAYLOAD_BINDING_INVALID");
            }
        }
    }

    private static Set<String> normalizeMaterials(Set<String> values) {
        return values.stream().map(value -> Objects.requireNonNull(value, "blacklisted material")
                .trim().toUpperCase(Locale.ROOT)).filter(value -> !value.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String normalizeMaterial(String material) { return material.trim().toUpperCase(Locale.ROOT); }

    private static String shortMaterial(String material) {
        String normalized = normalizeMaterial(material);
        return normalized.startsWith("MINECRAFT:") ? normalized.substring("MINECRAFT:".length()) : normalized;
    }

    private static String requestDigest(String operation, Object... values) {
        String canonical = operation + "|" + java.util.Arrays.stream(values)
                .map(value -> value instanceof List<?> list ? list.stream().map(String::valueOf).sorted()
                        .collect(java.util.stream.Collectors.joining(",")) : String.valueOf(value))
                .collect(java.util.stream.Collectors.joining("|"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("JRE must provide SHA-256", exception);
        }
    }

    private static StakedOperationResult accepted(UUID operationId, UUID escrowId, UUID subjectId,
                                                  StakedEscrowState state, String code) {
        return StakedOperationResult.accepted(operationId, escrowId, subjectId, state, code);
    }

    private static StakedOperationResult rejected(UUID operationId, UUID escrowId, UUID subjectId,
                                                  StakedEscrowState state, String code) {
        return StakedOperationResult.rejected(operationId, escrowId, subjectId, state, code);
    }

    private static StakedDeliveryStart deliveryRejected(StakedOperationResult result) {
        return new StakedDeliveryStart(result, Optional.empty(), Optional.empty());
    }

    private static List<UUID> sorted(Set<UUID> values) {
        return values.stream().sorted().toList();
    }

    private record EscrowRow(UUID escrowId, UUID matchId, String rulesetDigest, String manifestDigest,
                             Instant preparedAt, StakedEscrowState state, UUID resultId, UUID winnerId,
                             String refundReason, String quarantineReason, Instant updatedAt) {}

    private enum ParticipantWithdrawalState { PENDING, WITHDRAWING, WITHDRAWN, QUARANTINED }

    private record ParticipantRow(UUID playerId, Instant consentedAt, UUID consentMatchId,
                                  String consentRulesetDigest, String consentManifestDigest,
                                  ParticipantWithdrawalState withdrawalState, String withdrawalOperationId) {}

    private record ClaimRow(StakedClaim claim) {}

    private record StoredOperation(StakedOperationKind kind, UUID escrowId, UUID subjectId,
                                  UUID claimId, String requestDigest, StakedOperationResult result) {}

    private static EscrowRow rowWithState(EscrowRow row, StakedEscrowState state) {
        return new EscrowRow(row.escrowId(), row.matchId(), row.rulesetDigest(), row.manifestDigest(),
                row.preparedAt(), state, row.resultId(), row.winnerId(), row.refundReason(),
                row.quarantineReason(), row.updatedAt());
    }

    private Optional<StakedEscrowSnapshot> loadSnapshot(Connection connection, UUID escrowId) throws SQLException {
        Optional<EscrowRow> escrow = loadEscrow(connection, escrowId);
        if (escrow.isEmpty()) return Optional.empty();
        EscrowRow row = escrow.get();
        Set<UUID> participants = participants(connection, escrowId);
        Map<UUID, List<StakedItem>> manifest = new LinkedHashMap<>();
        for (UUID participant : sorted(participants)) manifest.put(participant, manifest(connection, escrowId, participant));
        Set<UUID> consented = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id FROM mg_staked_participant WHERE escrow_id = ? AND consented_at IS NOT NULL
                """)) {
            statement.setString(1, escrowId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) consented.add(UUID.fromString(result.getString(1)));
            }
        }
        return Optional.of(new StakedEscrowSnapshot(row.escrowId(), row.matchId(), row.rulesetDigest(),
                row.manifestDigest(), row.preparedAt(), row.state(), participants, manifest, consented,
                Optional.ofNullable(row.resultId()), Optional.ofNullable(row.winnerId()),
                Optional.ofNullable(row.refundReason()), Optional.ofNullable(row.quarantineReason()), row.updatedAt()));
    }

    private Optional<StakedEscrow> rebuildPreparedDomainEscrow(StakedEscrowSnapshot snapshot) {
        try {
            return Optional.of(StakedEscrow.prepare(snapshot.escrowId(), snapshot.matchId(), snapshot.rulesetDigest(),
                    snapshot.preparedAt(), ArenaFormat.standard(1), snapshot.participants(), snapshot.manifest(), Set.of()));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static void insertEscrow(Connection connection, StakedEscrow escrow, String now) throws SQLException {
        execute(connection, """
                INSERT INTO mg_staked_escrow
                    (escrow_id, match_id, ruleset_digest, manifest_digest, prepared_at, state, updated_at)
                VALUES (?, ?, ?, ?, ?, 'PREPARED', ?)
                """, statement -> {
            statement.setString(1, escrow.escrowId().toString());
            statement.setString(2, escrow.matchId().toString());
            statement.setString(3, escrow.rulesetDigest());
            statement.setString(4, escrow.manifestDigest());
            statement.setString(5, escrow.preparedAt().toString());
            statement.setString(6, now);
        });
    }

    private static void insertPayload(Connection connection, UUID escrowId, StakedInventoryPayload payload)
            throws SQLException {
        execute(connection, """
                INSERT INTO mg_staked_payload
                    (escrow_id, player_id, manifest_digest, storage_payload, armor_payload, extra_payload,
                     storage_sha256, armor_sha256, extra_sha256, payload_sha256)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, statement -> {
            statement.setString(1, escrowId.toString());
            statement.setString(2, payload.playerId().toString());
            statement.setString(3, payload.manifestDigest());
            statement.setBytes(4, payload.storageBytes());
            statement.setBytes(5, payload.armorBytes());
            statement.setBytes(6, payload.extraBytes());
            statement.setString(7, payload.storageSha256());
            statement.setString(8, payload.armorSha256());
            statement.setString(9, payload.extraSha256());
            statement.setString(10, payload.payloadSha256());
        });
    }

    private static void insertClaim(Connection connection, UUID claimId, UUID escrowId, UUID source,
                                    UUID beneficiary, UUID resultId, StakedClaimKind kind, String payloadDigest,
                                    StakedClaimState state, UUID beginOperationId, Instant updatedAt)
            throws SQLException {
        execute(connection, """
                INSERT INTO mg_staked_claim
                    (claim_id, escrow_id, source_player_id, beneficiary_id, result_id, kind,
                     payload_digest, state, begin_operation_id, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, statement -> {
            statement.setString(1, claimId.toString());
            statement.setString(2, escrowId.toString());
            statement.setString(3, source.toString());
            statement.setString(4, beneficiary.toString());
            statement.setString(5, resultId.toString());
            statement.setString(6, kind.name());
            statement.setString(7, payloadDigest);
            statement.setString(8, state.name());
            if (beginOperationId == null) statement.setNull(9, java.sql.Types.VARCHAR);
            else statement.setString(9, beginOperationId.toString());
            statement.setString(10, updatedAt.toString());
        });
    }

    private static Optional<EscrowRow> loadEscrow(Connection connection, UUID escrowId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT escrow_id, match_id, ruleset_digest, manifest_digest, prepared_at, state,
                       result_id, winner_id, refund_reason, quarantine_reason, updated_at
                FROM mg_staked_escrow WHERE escrow_id = ?
                """)) {
            statement.setString(1, escrowId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(readEscrow(result)) : Optional.empty();
            }
        }
    }

    private static EscrowRow readEscrow(ResultSet result) throws SQLException {
        try {
            return new EscrowRow(UUID.fromString(result.getString("escrow_id")),
                    UUID.fromString(result.getString("match_id")), result.getString("ruleset_digest"),
                    result.getString("manifest_digest"), Instant.parse(result.getString("prepared_at")),
                    StakedEscrowState.valueOf(result.getString("state")), nullableUuid(result.getString("result_id")),
                    nullableUuid(result.getString("winner_id")), result.getString("refund_reason"),
                    result.getString("quarantine_reason"), Instant.parse(result.getString("updated_at")));
        } catch (RuntimeException exception) {
            throw new PersistenceFailure("Staked escrow row is corrupt", exception);
        }
    }

    private static Optional<ParticipantRow> participant(Connection connection, UUID escrowId, UUID playerId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id, consented_at, consent_match_id, consent_ruleset_digest,
                       consent_manifest_digest, withdrawal_state, withdrawal_operation_id
                FROM mg_staked_participant WHERE escrow_id = ? AND player_id = ?
                """)) {
            statement.setString(1, escrowId.toString());
            statement.setString(2, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new ParticipantRow(UUID.fromString(result.getString("player_id")),
                        nullableInstant(result.getString("consented_at")), nullableUuid(result.getString("consent_match_id")),
                        result.getString("consent_ruleset_digest"), result.getString("consent_manifest_digest"),
                        ParticipantWithdrawalState.valueOf(result.getString("withdrawal_state")),
                        result.getString("withdrawal_operation_id")));
            }
        }
    }

    private static Optional<StakedInventoryPayload> payload(Connection connection, EscrowRow escrow, UUID playerId)
            throws SQLException {
        try {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT manifest_digest, storage_payload, armor_payload, extra_payload,
                           storage_sha256, armor_sha256, extra_sha256, payload_sha256
                    FROM mg_staked_payload WHERE escrow_id = ? AND player_id = ?
                    """)) {
                statement.setString(1, escrow.escrowId().toString());
                statement.setString(2, playerId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) return Optional.empty();
                    StakedInventoryPayload value = StakedInventoryPayload.fromSerialized(playerId,
                            result.getString("manifest_digest"), result.getBytes("storage_payload"),
                            result.getBytes("armor_payload"), result.getBytes("extra_payload"));
                    if (!value.storageSha256().equals(result.getString("storage_sha256"))
                            || !value.armorSha256().equals(result.getString("armor_sha256"))
                            || !value.extraSha256().equals(result.getString("extra_sha256"))
                            || !value.payloadSha256().equals(result.getString("payload_sha256"))
                            || !value.manifestDigest().equals(escrow.manifestDigest())) {
                        return Optional.empty();
                    }
                    return Optional.of(value);
                }
            }
        } catch (RuntimeException exception) {
            // Treat malformed or oversized durable bytes as corruption.  The
            // caller performs the enclosing transaction's quarantine write.
            return Optional.empty();
        }
    }

    private static List<StakedItem> manifest(Connection connection, UUID escrowId, UUID playerId)
            throws SQLException {
        List<StakedItem> items = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT item_id, material, amount, canonical_fingerprint
                FROM mg_staked_manifest_item WHERE escrow_id = ? AND player_id = ? ORDER BY item_id
                """)) {
            statement.setString(1, escrowId.toString());
            statement.setString(2, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) items.add(new StakedItem(result.getString("item_id"),
                        result.getString("material"), result.getInt("amount"), result.getString("canonical_fingerprint")));
            }
        }
        return List.copyOf(items);
    }

    private static Set<UUID> participants(Connection connection, UUID escrowId) throws SQLException {
        Set<UUID> participants = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id FROM mg_staked_participant WHERE escrow_id = ? ORDER BY player_id
                """)) {
            statement.setString(1, escrowId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) participants.add(UUID.fromString(result.getString(1)));
            }
        }
        return Set.copyOf(participants);
    }

    private static Optional<ClaimRow> claim(Connection connection, UUID claimId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT claim_id, escrow_id, source_player_id, beneficiary_id, result_id, kind,
                       payload_digest, state, begin_operation_id, updated_at
                FROM mg_staked_claim WHERE claim_id = ?
                """)) {
            statement.setString(1, claimId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new ClaimRow(readClaim(result))) : Optional.empty();
            }
        }
    }

    private static StakedClaim readClaim(ResultSet result) throws SQLException {
        try {
            return new StakedClaim(UUID.fromString(result.getString("claim_id")),
                    UUID.fromString(result.getString("escrow_id")), UUID.fromString(result.getString("source_player_id")),
                    UUID.fromString(result.getString("beneficiary_id")), UUID.fromString(result.getString("result_id")),
                    StakedClaimKind.valueOf(result.getString("kind")), result.getString("payload_digest"),
                    StakedClaimState.valueOf(result.getString("state")),
                    Optional.ofNullable(nullableUuid(result.getString("begin_operation_id"))),
                    Instant.parse(result.getString("updated_at")));
        } catch (RuntimeException exception) {
            throw new PersistenceFailure("Staked claim row is corrupt", exception);
        }
    }

    private static StakedClaim readClaim(Connection connection, UUID claimId) throws SQLException {
        return claim(connection, claimId).orElseThrow(() -> new PersistenceFailure("Staked claim disappeared"))
                .claim();
    }

    private static boolean exists(Connection connection, UUID escrowId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM mg_staked_escrow WHERE escrow_id = ?")) {
            statement.setString(1, escrowId.toString());
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private static boolean matchExists(Connection connection, UUID matchId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM mg_staked_escrow WHERE match_id = ?")) {
            statement.setString(1, matchId.toString());
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private static boolean allConsented(Connection connection, UUID escrowId) throws SQLException {
        return count(connection, "SELECT COUNT(*) FROM mg_staked_participant WHERE escrow_id = ? AND consented_at IS NULL",
                escrowId) == 0;
    }

    private static boolean allWithdrawn(Connection connection, UUID escrowId) throws SQLException {
        return count(connection, "SELECT COUNT(*) FROM mg_staked_participant WHERE escrow_id = ? AND withdrawal_state <> 'WITHDRAWN'",
                escrowId) == 0;
    }

    private static boolean hasPendingClaim(Connection connection, UUID escrowId) throws SQLException {
        return count(connection, "SELECT COUNT(*) FROM mg_staked_claim WHERE escrow_id = ? AND state <> 'DELIVERED'",
                escrowId) > 0;
    }

    private static boolean hasActiveDelivery(Connection connection, UUID escrowId) throws SQLException {
        return count(connection, "SELECT COUNT(*) FROM mg_staked_claim WHERE escrow_id = ? AND state = 'DELIVERING'",
                escrowId) > 0;
    }

    private static boolean inventoryVerified(Connection connection, UUID escrowId, UUID playerId,
                                             String payloadDigest) throws SQLException {
        String verificationDigest = requestDigest("VERIFY_INVENTORY", escrowId, playerId, payloadDigest);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM mg_staked_operation
                WHERE escrow_id = ? AND player_id = ? AND kind = 'VERIFY_INVENTORY'
                  AND accepted = 1 AND request_digest = ?
                ORDER BY created_at DESC LIMIT 1
                """)) {
            statement.setString(1, escrowId.toString());
            statement.setString(2, playerId.toString());
            statement.setString(3, verificationDigest);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private static int count(Connection connection, String sql, UUID escrowId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, escrowId.toString());
            try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getInt(1) : 0; }
        }
    }

    private static void updateEscrowState(Connection connection, EscrowRow row, StakedEscrowState next,
                                          Instant updatedAt, String quarantineReason) throws SQLException {
        if (!row.state().canTransitionTo(next)) {
            throw new PersistenceFailure("Invalid staked escrow state transition: " + row.state() + " -> " + next);
        }
        execute(connection, """
                UPDATE mg_staked_escrow SET state = ?, quarantine_reason = COALESCE(?, quarantine_reason), updated_at = ?
                WHERE escrow_id = ?
                """, statement -> {
            statement.setString(1, next.name());
            if (quarantineReason == null) statement.setNull(2, java.sql.Types.VARCHAR);
            else statement.setString(2, quarantineReason);
            statement.setString(3, updatedAt.toString());
            statement.setString(4, row.escrowId().toString());
        });
    }

    private static void updateEscrowFinalization(Connection connection, EscrowRow row, StakedEscrowState next,
                                                 UUID resultId, UUID winnerId, String refundReason,
                                                 Instant updatedAt) throws SQLException {
        if (!row.state().canTransitionTo(next)) {
            throw new PersistenceFailure("Invalid staked finalization transition: " + row.state() + " -> " + next);
        }
        execute(connection, """
                UPDATE mg_staked_escrow
                SET state = ?, result_id = ?, winner_id = ?, refund_reason = ?, updated_at = ?
                WHERE escrow_id = ?
                """, statement -> {
            statement.setString(1, next.name());
            statement.setString(2, resultId.toString());
            if (winnerId == null) statement.setNull(3, java.sql.Types.VARCHAR);
            else statement.setString(3, winnerId.toString());
            if (refundReason == null) statement.setNull(4, java.sql.Types.VARCHAR);
            else statement.setString(4, refundReason);
            statement.setString(5, updatedAt.toString());
            statement.setString(6, row.escrowId().toString());
        });
    }

    private static void quarantine(Connection connection, UUID escrowId, String reason, Instant now)
            throws SQLException {
        execute(connection, """
                UPDATE mg_staked_escrow
                SET state = 'QUARANTINED', quarantine_reason = ?, updated_at = ?
                WHERE escrow_id = ? AND state <> 'QUARANTINED'
                """, statement -> {
            statement.setString(1, reason);
            statement.setString(2, now.toString());
            statement.setString(3, escrowId.toString());
        });
        execute(connection, """
                UPDATE mg_staked_participant SET withdrawal_state = 'QUARANTINED'
                WHERE escrow_id = ? AND withdrawal_state = 'WITHDRAWING'
                """, statement -> statement.setString(1, escrowId.toString()));
        execute(connection, """
                UPDATE mg_staked_claim SET state = 'QUARANTINED', updated_at = ?
                WHERE escrow_id = ? AND state = 'DELIVERING'
                """, statement -> {
            statement.setString(1, now.toString());
            statement.setString(2, escrowId.toString());
        });
    }

    private static void quarantineInFlight(Connection connection, Instant now) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT escrow_id FROM mg_staked_escrow
                WHERE state IN ('WITHDRAWING', 'DELIVERING', 'REFUNDING')
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) ids.add(UUID.fromString(result.getString(1)));
            }
        }
        for (UUID id : ids) quarantine(connection, id, RECOVERY_REASON, now);
    }

    private static Optional<StakedOperationResult> replay(Connection connection, UUID operationId,
                                                           StakedOperationKind kind, UUID escrowId,
                                                           String requestDigest, UUID subjectId) throws SQLException {
        Optional<StoredOperation> prior = operation(connection, operationId);
        if (prior.isEmpty()) return Optional.empty();
        StoredOperation stored = prior.get();
        if (stored.kind() == kind && stored.escrowId().equals(escrowId)
                && Objects.equals(stored.subjectId(), subjectId)
                && stored.requestDigest().equals(requestDigest)) {
            return Optional.of(stored.result().asIdempotentReplay());
        }
        quarantine(connection, stored.escrowId(), "IDEMPOTENCY_CONFLICT", Instant.now());
        if (!stored.escrowId().equals(escrowId)) quarantine(connection, escrowId, "IDEMPOTENCY_CONFLICT", Instant.now());
        return Optional.of(rejected(operationId, escrowId, subjectId, StakedEscrowState.QUARANTINED,
                "IDEMPOTENCY_CONFLICT"));
    }

    private static Optional<StoredOperation> operation(Connection connection, UUID operationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_id, escrow_id, player_id, claim_id, kind, request_digest,
                       to_state, accepted, code
                FROM mg_staked_operation WHERE operation_id = ?
                """)) {
            statement.setString(1, operationId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                UUID escrowId = UUID.fromString(result.getString("escrow_id"));
                UUID subjectId = nullableUuid(result.getString("player_id"));
                StakedEscrowState state = StakedEscrowState.valueOf(result.getString("to_state"));
                StakedOperationResult outcome = new StakedOperationResult(operationId, escrowId,
                        Optional.ofNullable(subjectId), state, result.getInt("accepted") == 1, false,
                        result.getString("code"));
                return Optional.of(new StoredOperation(StakedOperationKind.valueOf(result.getString("kind")),
                        escrowId, subjectId, nullableUuid(result.getString("claim_id")),
                        result.getString("request_digest"), outcome));
            }
        }
    }

    private static void insertJournal(Connection connection, UUID operationId, UUID escrowId, UUID subjectId,
                                     UUID claimId, StakedOperationKind kind, String requestDigest,
                                     StakedEscrowState fromState, StakedOperationResult result) throws SQLException {
        execute(connection, """
                INSERT INTO mg_staked_operation
                    (operation_id, escrow_id, player_id, claim_id, kind, request_digest,
                     from_state, to_state, accepted, code, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, statement -> {
            statement.setString(1, operationId.toString());
            statement.setString(2, escrowId.toString());
            setUuid(statement, 3, subjectId);
            setUuid(statement, 4, claimId);
            statement.setString(5, kind.name());
            statement.setString(6, requestDigest);
            if (fromState == null) statement.setNull(7, java.sql.Types.VARCHAR);
            else statement.setString(7, fromState.name());
            statement.setString(8, result.state().name());
            statement.setInt(9, result.accepted() ? 1 : 0);
            statement.setString(10, result.code());
            statement.setString(11, Instant.now().toString());
        });
    }

    private static StakedOperationResult recordRejected(Connection connection, UUID operationId, UUID escrowId,
                                                        UUID subjectId, StakedOperationKind kind,
                                                        String digest, StakedEscrowState state, String code)
            throws SQLException {
        StakedOperationResult result = rejected(operationId, escrowId, subjectId, state, code);
        insertJournal(connection, operationId, escrowId, subjectId, null, kind, digest, state, result);
        return result;
    }

    private static StakedDeliveryStart replayDelivery(Connection connection, StakedOperationResult result,
                                                      UUID claimId) throws SQLException {
        if (!result.accepted()) return deliveryRejected(result);
        Optional<ClaimRow> claim = claim(connection, claimId);
        if (claim.isEmpty()) throw new PersistenceFailure("Accepted delivery journal has no claim");
        Optional<EscrowRow> escrow = loadEscrow(connection, result.escrowId());
        if (escrow.isEmpty()) throw new PersistenceFailure("Accepted delivery journal has no escrow");
        Optional<StakedInventoryPayload> payload = payload(connection, escrow.get(), claim.get().claim().sourcePlayerId());
        if (payload.isEmpty()) {
            quarantine(connection, result.escrowId(), "DELIVERY_PAYLOAD_CORRUPT", Instant.now());
            StakedOperationResult blocked = rejected(result.operationId(), result.escrowId(), null,
                    StakedEscrowState.QUARANTINED, "DELIVERY_PAYLOAD_CORRUPT");
            return new StakedDeliveryStart(blocked, Optional.empty(), Optional.empty());
        }
        return new StakedDeliveryStart(result, Optional.of(claim.get().claim()), payload);
    }

    private static Optional<UUID> inFlightParticipant(Connection connection, UUID escrowId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT player_id FROM mg_staked_participant
                WHERE escrow_id = ? AND withdrawal_state = 'WITHDRAWING' ORDER BY player_id LIMIT 1
                """)) {
            statement.setString(1, escrowId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(UUID.fromString(result.getString(1))) : Optional.empty();
            }
        }
    }

    private static Optional<UUID> inFlightClaim(Connection connection, UUID escrowId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT claim_id FROM mg_staked_claim
                WHERE escrow_id = ? AND state = 'DELIVERING' ORDER BY claim_id LIMIT 1
                """)) {
            statement.setString(1, escrowId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(UUID.fromString(result.getString(1))) : Optional.empty();
            }
        }
    }

    private static List<String> operationKinds(Connection connection, UUID escrowId) throws SQLException {
        List<String> kinds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT DISTINCT kind FROM mg_staked_operation WHERE escrow_id = ? ORDER BY kind
                """)) {
            statement.setString(1, escrowId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) kinds.add(result.getString(1));
            }
        }
        return List.copyOf(kinds);
    }

    private static UUID nullableUuid(String value) { return value == null ? null : UUID.fromString(value); }
    private static Instant nullableInstant(String value) { return value == null ? null : Instant.parse(value); }

    @FunctionalInterface
    private interface Binder { void bind(PreparedStatement statement) throws SQLException; }

    private static void execute(Connection connection, String sql, Binder binder) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            statement.executeUpdate();
        }
    }

    private static void setUuid(PreparedStatement statement, int index, UUID value) throws SQLException {
        if (value == null) statement.setNull(index, java.sql.Types.VARCHAR);
        else statement.setString(index, value.toString());
    }
}
