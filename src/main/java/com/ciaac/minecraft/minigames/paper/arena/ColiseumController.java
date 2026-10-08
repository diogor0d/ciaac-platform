package com.ciaac.minecraft.minigames.paper.arena;

import com.ciaac.minecraft.minigames.arena.ArenaAdmissionToken;
import com.ciaac.minecraft.minigames.arena.ArenaDisconnectDisposition;
import com.ciaac.minecraft.minigames.arena.ArenaEquipmentContract;
import com.ciaac.minecraft.minigames.arena.ArenaFormat;
import com.ciaac.minecraft.minigames.arena.ArenaKitMode;
import com.ciaac.minecraft.minigames.arena.ArenaLocationPolicy;
import com.ciaac.minecraft.minigames.arena.ArenaMatch;
import com.ciaac.minecraft.minigames.arena.ArenaMatchRegistry;
import com.ciaac.minecraft.minigames.arena.ArenaPhase;
import com.ciaac.minecraft.minigames.arena.ArenaQueue;
import com.ciaac.minecraft.minigames.arena.DirectChallenge;
import com.ciaac.minecraft.minigames.arena.PlayerStateOperation;
import com.ciaac.minecraft.minigames.arena.QueueMatchProposal;
import com.ciaac.minecraft.minigames.arena.QueueTicket;
import com.ciaac.minecraft.minigames.arena.TeamRoster;
import com.ciaac.minecraft.minigames.arena.StakedConsent;
import com.ciaac.minecraft.minigames.arena.StakedEscrow;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.region.ProtectedRegion;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRegistry;
import com.ciaac.minecraft.minigames.region.ProtectedRegionRole;
import com.ciaac.minecraft.minigames.region.RegionAdmissionRegistry;
import com.ciaac.minecraft.minigames.region.RegionAdmissionToken;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequest;
import com.ciaac.minecraft.minigames.runtime.AdmissionResult;
import com.ciaac.minecraft.minigames.runtime.AdmissionStatus;
import com.ciaac.minecraft.minigames.runtime.AuthenticatedSession;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.ConnectionRegistry;
import com.ciaac.minecraft.minigames.runtime.CombatPolicy;
import com.ciaac.minecraft.minigames.runtime.CombatPolicyRegistry;
import com.ciaac.minecraft.minigames.runtime.SessionCoordinator;
import com.ciaac.minecraft.minigames.paper.TemporaryItemTagger;
import com.ciaac.minecraft.minigames.paper.arena.staked.StakedClaim;
import com.ciaac.minecraft.minigames.paper.arena.staked.StakedClaimState;
import com.ciaac.minecraft.minigames.paper.arena.staked.StakedEscrowState;
import com.ciaac.minecraft.minigames.paper.arena.staked.StakedInventoryAdapter;
import com.ciaac.minecraft.minigames.paper.arena.staked.StakedInventoryPayload;
import com.ciaac.minecraft.minigames.paper.arena.staked.StakedOperationResult;
import com.ciaac.minecraft.minigames.statistics.MatchOutcome;
import com.ciaac.minecraft.minigames.statistics.MatchResult;
import com.ciaac.minecraft.minigames.statistics.PlayerResult;
import com.ciaac.minecraft.minigames.statistics.recording.MatchResultFactory;
import com.ciaac.minecraft.minigames.statistics.recording.ResultIds;
import com.ciaac.minecraft.minigames.statistics.recording.ResultRecording;
import com.ciaac.minecraft.minigames.statistics.recording.StatisticsResultSink;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.bukkit.attribute.Attribute;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Disabled-by-default Paper coordinator for one physical Coliseum slot.
 *
 * <p>This class owns orchestration and live Player operations. Durable player
 * state remains exclusively in {@link SessionCoordinator}; staked play is
 * rejected until a durable {@link StakedEscrowPort} integration is supplied.
 */
public final class ColiseumController {
    private static final String CLOSED_CODE = "COLISEUM_CLOSED";
    private final ColiseumSettings settings;
    private final Server server;
    private final SessionCoordinator sessions;
    private final ArenaConnectionResolver connections;
    private final ArenaKitProvider kits;
    private final TemporaryItemTagger temporaryItems;
    private final ProtectedRegionRegistry regions;
    private final RegionAdmissionRegistry admissions;
    private final CombatPolicyRegistry combatPolicies;
    private final Optional<StakedEscrowPort> escrowPort;
    private final Clock clock;
    private final StatisticsResultSink statistics;
    private final ArenaQueue queue;
    private final ArenaMatchRegistry matches = new ArenaMatchRegistry();
    private final Map<UUID, ArenaEquipmentContract> contractByTicket = new HashMap<>();
    private final Map<UUID, UUID> ticketByPlayer = new HashMap<>();
    private final Map<UUID, UUID> connectionByPlayer = new HashMap<>();
    private final Map<UUID, ArenaEquipmentContract> contractByPlayer = new HashMap<>();
    private final Map<UUID, ArenaLoadoutSnapshot> loadouts = new HashMap<>();
    private final Map<UUID, Integer> protectedWithheldItems = new HashMap<>();
    private final Map<UUID, UUID> sessionByPlayer = new HashMap<>();
    private final Map<UUID, DirectChallenge> challenges = new LinkedHashMap<>();
    private final Map<UUID, ArenaEquipmentContract> contractByChallenge = new HashMap<>();
    private final ArenaItemManifestBuilder manifestBuilder = new ArenaItemManifestBuilder();
    private final StakedInventoryAdapter stakedInventories = new StakedInventoryAdapter();
    private final Map<UUID, StakedInventoryPayload> stakedPayloadByPlayer = new LinkedHashMap<>();
    private final Map<UUID, UUID> departingConnections = new HashMap<>();
    private final AuthenticationRegistry authentication;
    private final ConnectionRegistry connectionRegistry;
    private RestorationPlan restorationPlan;
    private boolean stakeFinalized;
    private Instant readyDeadline;
    private Instant stakeConsentDeadline;
    private Instant combatStartedAt;
    private Instant combatDeadline;

    public ColiseumController(
            ColiseumSettings settings,
            Server server,
            SessionCoordinator sessions,
            ArenaConnectionResolver connections,
            ArenaKitProvider kits,
            TemporaryItemTagger temporaryItems,
            ProtectedRegionRegistry regions,
            RegionAdmissionRegistry admissions,
            CombatPolicyRegistry combatPolicies,
            Optional<StakedEscrowPort> escrowPort,
            Clock clock) {
        this(settings, server, sessions, connections, kits, temporaryItems, regions, admissions,
                combatPolicies, escrowPort, clock, StatisticsResultSink.unavailable());
    }

    public ColiseumController(
            ColiseumSettings settings,
            Server server,
            SessionCoordinator sessions,
            ArenaConnectionResolver connections,
            ArenaKitProvider kits,
            TemporaryItemTagger temporaryItems,
            ProtectedRegionRegistry regions,
            RegionAdmissionRegistry admissions,
            CombatPolicyRegistry combatPolicies,
            Optional<StakedEscrowPort> escrowPort,
            Clock clock,
            StatisticsResultSink statistics) {
        this(settings, server, sessions, connections, kits, temporaryItems, regions, admissions,
                combatPolicies, escrowPort, clock, statistics, null, null);
    }

    public ColiseumController(
            ColiseumSettings settings, Server server, SessionCoordinator sessions,
            ArenaConnectionResolver connections, ArenaKitProvider kits, TemporaryItemTagger temporaryItems,
            ProtectedRegionRegistry regions, RegionAdmissionRegistry admissions,
            CombatPolicyRegistry combatPolicies, Optional<StakedEscrowPort> escrowPort, Clock clock,
            StatisticsResultSink statistics, AuthenticationRegistry authentication,
            ConnectionRegistry connectionRegistry) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.server = Objects.requireNonNull(server, "server");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.kits = Objects.requireNonNull(kits, "kits");
        this.temporaryItems = Objects.requireNonNull(temporaryItems, "temporaryItems");
        this.regions = Objects.requireNonNull(regions, "regions");
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.combatPolicies = Objects.requireNonNull(combatPolicies, "combatPolicies");
        this.escrowPort = Objects.requireNonNull(escrowPort, "escrowPort");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.statistics = Objects.requireNonNull(statistics, "statistics");
        this.authentication = authentication;
        this.connectionRegistry = connectionRegistry;
        this.queue = new ArenaQueue(settings.formatPolicy());
    }

    public synchronized ArenaPlayerResponse join(
            Player player, UUID connectionId, String formatNotation,
            ArenaEquipmentContract equipment, Instant requestedAt) {
        return join(player, connectionId, TeamRoster.of(player.getUniqueId()), formatNotation, equipment, requestedAt);
    }

    /** Joins a queue as a complete, unsplittable party. */
    public synchronized ArenaPlayerResponse join(
            Player leader, UUID connectionId, TeamRoster partyRoster, String formatNotation,
            ArenaEquipmentContract equipment, Instant requestedAt) {
        Objects.requireNonNull(leader, "leader");
        Objects.requireNonNull(partyRoster, "partyRoster");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(equipment, "equipment");
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (!settings.enabled()) return respond(CLOSED_CODE, "O Coliseu está fechado para manutenção.");
        if (!mainThread(leader)) return respond("MAIN_THREAD_REQUIRED", "Esta operação tem de ocorrer no servidor principal.");
        if (equipment.mode() == ArenaKitMode.STAKED_SURVIVAL) {
            return respond("STAKED_CHALLENGE_ONLY",
                    "As apostas usam desafios 1v1 para que ambos confirmem o inventário exato.");
        }
        ArenaFormat format;
        try {
            format = ArenaFormat.parse(formatNotation, settings.formatPolicy());
        } catch (RuntimeException invalid) {
            return respond("FORMAT_INVALID", "Esse formato não está disponível no Coliseu.");
        }
        if (!partyRoster.players().contains(leader.getUniqueId())) {
            return respond("PARTY_INVALID", "O líder tem de pertencer ao grupo escolhido.");
        }
        if (partyRoster.players().stream().anyMatch(ticketByPlayer::containsKey)) {
            return respond("ALREADY_QUEUED", "Já estás na fila do Coliseu.");
        }
        if (matches.current().isPresent() && partyRoster.players().stream()
                .anyMatch(matches.current().orElseThrow().participants()::contains)) {
            return respond("MATCH_ACTIVE", "Já estás numa partida do Coliseu.");
        }
        QueueTicket ticket = QueueTicket.enqueue(leader.getUniqueId(), UUID.randomUUID(), partyRoster,
                format, equipment.mode(), requestedAt);
        queue.enqueue(ticket);
        for (UUID partyPlayer : partyRoster.players()) ticketByPlayer.put(partyPlayer, ticket.ticketId());
        contractByTicket.put(ticket.ticketId(), equipment);
        connectionByPlayer.put(leader.getUniqueId(), connectionId);
        return respond("QUEUED", "Entraste na fila do Coliseu.");
    }

    public synchronized ArenaPlayerResponse leave(Player player) {
        Objects.requireNonNull(player, "player");
        UUID playerId = player.getUniqueId();
        UUID ticketId = ticketByPlayer.remove(playerId);
        if (ticketId != null) {
            Set<UUID> partyPlayers = queue.tickets().stream()
                    .filter(ticket -> ticket.ticketId().equals(ticketId))
                    .findFirst().map(ticket -> ticket.partyRoster().players().stream().collect(Collectors.toSet()))
                    .orElse(Set.of(playerId));
            queue.remove(ticketId);
            contractByTicket.remove(ticketId);
            partyPlayers.forEach(ticketByPlayer::remove);
            partyPlayers.forEach(connectionByPlayer::remove);
            return respond("LEFT_QUEUE", "Saíste da fila do Coliseu.");
        }
        ArenaMatch match = matches.current().orElse(null);
        if (match == null || !match.participants().contains(playerId)) {
            return respond("NOT_QUEUED", "Não estás na fila nem numa partida do Coliseu.");
        }
        if (match.phase() == ArenaPhase.ACTIVE || match.phase() == ArenaPhase.FINISHING) {
            match.forfeit(playerId, "PLAYER_LEFT");
            if (!markEliminated(player)) return recoverCurrent("SPECTATOR_TRANSFER_FAILED");
            return match.phase() == ArenaPhase.FINISHING
                    ? finishCurrent("PLAYER_LEFT")
                    : new ArenaPlayerResponse("TEAM_CONTINUES",
                            "Saíste deste combate; a tua equipa continua em jogo.", Optional.of(match.id()));
        }
        match.handleDisconnect(playerId);
        return recoverCurrent("PLAYER_LEFT");
    }

    public synchronized ArenaPlayerResponse ready(Player player) {
        Objects.requireNonNull(player, "player");
        if (!mainThread(player)) return respond("MAIN_THREAD_REQUIRED", "Esta operação tem de ocorrer no servidor principal.");
        ArenaMatch match = matches.current().orElse(null);
        if (match == null || !match.participants().contains(player.getUniqueId())) {
            return respond("NO_MATCH", "Ainda não há uma partida reservada para ti.");
        }
        if (match.kitMode() == ArenaKitMode.STAKED_SURVIVAL
                && match.stakedEscrow().filter(StakedEscrow::canAdmit).isEmpty()) {
            return new ArenaPlayerResponse("STAKE_CONSENT_REQUIRED",
                    "Confirma primeiro a aposta exata com /coliseu aposta confirmar.",
                    Optional.of(match.id()));
        }
        if (match.markReady(player.getUniqueId()) && match.allReady()) {
            return prepareAndActivate(match, clock.instant());
        }
        return respond("READY_RECORDED", "Pronto. Aguardamos os restantes jogadores.");
    }

    /** Fresh explicit consent over the currently displayed exact 1v1 stake. */
    public synchronized ArenaPlayerResponse consentStake(Player player) {
        Objects.requireNonNull(player, "player");
        if (!mainThread(player)) return respond("MAIN_THREAD_REQUIRED",
                "Esta operação tem de ocorrer no servidor principal.");
        ArenaMatch match = matches.current().orElse(null);
        if (match == null || match.kitMode() != ArenaKitMode.STAKED_SURVIVAL
                || !match.participants().contains(player.getUniqueId())) {
            return respond("STAKE_UNAVAILABLE", "Não tens uma aposta pendente no Coliseu.");
        }
        Instant now = clock.instant();
        if (stakeConsentDeadline == null || now.isAfter(stakeConsentDeadline)) {
            return recoverCurrent("STAKE_CONSENT_TIMEOUT");
        }
        StakedEscrow stake = match.stakedEscrow().orElseThrow();
        StakedEscrowPort port = escrowPort.orElseThrow();
        StakedInventoryPayload expected = stakedPayloadByPlayer.get(player.getUniqueId());
        if (expected == null) return recoverCurrent("STAKE_PAYLOAD_MISSING");
        StakedInventoryPayload live = stakedInventories.capture(player, stake.manifestDigest());
        StakedOperationResult verification = port.verifyInventory(
                new StakedEscrowPort.InventoryVerificationRequest(UUID.randomUUID(), stake.escrowId(),
                        player.getUniqueId(), live));
        if (!verification.accepted() || !expected.exactlyMatches(live)) {
            return recoverCurrent("STAKE_INVENTORY_CHANGED");
        }
        StakedConsent consent = new StakedConsent(player.getUniqueId(), match.id(),
                stake.rulesetDigest(), stake.manifestDigest(), now);
        if (!stake.consent(consent)) {
            return new ArenaPlayerResponse("STAKE_ALREADY_CONFIRMED",
                    "Já confirmaste esta aposta exata.", Optional.of(match.id()));
        }
        StakedOperationResult persisted = port.consent(new StakedEscrowPort.ConsentRequest(
                UUID.randomUUID(), stake.escrowId(), consent));
        if (!persisted.accepted()) return recoverCurrent("STAKE_CONSENT_PERSISTENCE_FAILED");
        if (stake.canAdmit()) {
            for (UUID participant : match.participants()) {
                Player online = server.getPlayer(participant);
                if (online != null) {
                    try { online.sendMessage("§aA aposta foi confirmada por ambos. Usa /coliseu pronto."); }
                    catch (RuntimeException ignored) {
                        // Informational delivery must not change persisted stake consent.
                    }
                }
            }
        }
        return new ArenaPlayerResponse("STAKE_CONFIRMED",
                stake.canAdmit() ? "Aposta confirmada por ambos. Agora confirma que estás pronto."
                        : "Confirmaste a aposta; falta a confirmação do adversário.",
                Optional.of(match.id()));
    }

    /** Delivers only complete durable claims that fit; excess loot remains claimable later. */
    public synchronized ArenaPlayerResponse claimStake(Player player) {
        Objects.requireNonNull(player, "player");
        if (!mainThread(player)) return respond("MAIN_THREAD_REQUIRED",
                "Esta operação tem de ocorrer no servidor principal.");
        if (escrowPort.isEmpty()) return stakedUnavailable();
        int delivered = deliverClaims(player);
        if (delivered > 0) {
            return respond("STAKE_CLAIMS_DELIVERED",
                    "Recebeste " + delivered + (delivered == 1 ? " lote" : " lotes") + " da aposta.");
        }
        boolean pending = !escrowPort.orElseThrow().pendingClaimsFor(player.getUniqueId()).isEmpty();
        return pending
                ? respond("STAKE_CLAIM_NEEDS_SPACE",
                "Ainda há loot por receber. Liberta espaço no inventário e usa /coliseu aposta reclamar.")
                : respond("STAKE_NO_CLAIMS", "Não tens loot de apostas por receber.");
    }

    public synchronized ArenaPlayerResponse challenge(
            Player challenger, Player target, String formatNotation,
            ArenaEquipmentContract equipment, Instant expiresAt) {
        return challenge(challenger, target, TeamRoster.of(challenger.getUniqueId()),
                TeamRoster.of(target.getUniqueId()), formatNotation, equipment, expiresAt);
    }

    public synchronized ArenaPlayerResponse challenge(
            Player challenger, Player target, TeamRoster challengerRoster, TeamRoster targetRoster,
            String formatNotation, ArenaEquipmentContract equipment, Instant expiresAt) {
        Objects.requireNonNull(challenger, "challenger");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(equipment, "equipment");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!settings.enabled()) return respond(CLOSED_CODE, "O Coliseu está fechado para manutenção.");
        try {
            ArenaFormat format = ArenaFormat.parse(formatNotation, settings.formatPolicy());
            if (equipment.mode() == ArenaKitMode.STAKED_SURVIVAL) {
                if (!stakedAvailable()) return stakedUnavailable();
                if (!settings.stakedFormats().contains(format)
                        || challengerRoster.players().size() != 1 || targetRoster.players().size() != 1) {
                    return respond("STAKED_1V1_ONLY", "As apostas estão limitadas a desafios 1v1.");
                }
            }
            if (containsQueuedPlayer(challengerRoster) || containsQueuedPlayer(targetRoster)) {
                return respond("CHALLENGE_PARTICIPANT_QUEUED",
                        "Todos os participantes têm de sair da fila antes de aceitarem um desafio.");
            }
            DirectChallenge challenge = new DirectChallenge(UUID.randomUUID(), challenger.getUniqueId(),
                    target.getUniqueId(), format, equipment.mode(), challengerRoster, targetRoster,
                    expiresAt, settings.formatPolicy());
            challenges.put(challenge.id(), challenge);
            contractByChallenge.put(challenge.id(), equipment);
            return new ArenaPlayerResponse("CHALLENGE_CREATED", "Desafio enviado.", Optional.of(challenge.id()));
        } catch (RuntimeException invalid) {
            return respond("CHALLENGE_INVALID", "Não foi possível criar esse desafio.");
        }
    }

    public synchronized ArenaPlayerResponse acceptChallenge(Player target, UUID challengeId, Instant now) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(challengeId, "challengeId");
        Objects.requireNonNull(now, "now");
        if (!mainThread(target)) return respond("MAIN_THREAD_REQUIRED", "Esta operação tem de ocorrer no servidor principal.");
        DirectChallenge challenge = challenges.get(challengeId);
        ArenaEquipmentContract equipment = contractByChallenge.get(challengeId);
        if (challenge == null || equipment == null || !challenge.target().equals(target.getUniqueId())
                || challenge.isExpired(now)) {
            return respond("CHALLENGE_INVALID", "Esse desafio já expirou ou não é para ti.");
        }
        if (containsQueuedPlayer(challenge.challengerRoster()) || containsQueuedPlayer(challenge.targetRoster())) {
            return respond("CHALLENGE_PARTICIPANT_QUEUED",
                    "Todos os participantes têm de sair da fila antes de aceitarem um desafio.");
        }
        if (!challenge.accept(target.getUniqueId(), now)) {
            return respond("CHALLENGE_INVALID", "Esse desafio já expirou ou não é para ti.");
        }
        if (matches.current().isPresent()) return respond("ARENA_BUSY", "O Coliseu está ocupado neste momento.");
        StakedEscrow preparedStake = null;
        try {
            ArenaMatch match;
            if (challenge.kitMode() == ArenaKitMode.STAKED_SURVIVAL) {
                StakedEscrow stake = prepareStake(challenge, now);
                preparedStake = stake;
                match = matches.reserve(challenge.id(), challenge.format(), challenge.kitMode(),
                        challenge.challengerRoster(), challenge.targetRoster(), stake,
                        settings.formatPolicy());
                stakeConsentDeadline = now.plus(settings.stakeConsentTimeout());
                sendStakeReview(match, stake);
            } else {
                match = matches.reserve(challenge.id(), challenge.format(), challenge.kitMode(),
                        challenge.challengerRoster(), challenge.targetRoster(), settings.formatPolicy());
            }
            bindMatchContracts(match, equipment);
            readyDeadline = now.plus(settings.readyTimeout());
            return new ArenaPlayerResponse("CHALLENGE_ACCEPTED", "Desafio aceite. Confirma quando estiveres pronto.",
                    Optional.of(match.id()));
        } catch (RuntimeException failure) {
            ArenaMatch reserved = matches.current().filter(match -> match.id().equals(challenge.id())).orElse(null);
            if (reserved != null) return recoverCurrent("CHALLENGE_RESERVATION_FAILED");
            if (preparedStake != null) {
                boolean released = refundPreparedStake(preparedStake);
                clearPreparedStake(preparedStake);
                if (!released) return stakedUnavailable();
            }
            return challengeFailure(failure);
        } finally {
            challenges.remove(challengeId);
            contractByChallenge.remove(challengeId);
        }
    }

    /** Attempts one queue composition per supported format and mode. */
    public synchronized ArenaPlayerResponse tick(Instant now) {
        Objects.requireNonNull(now, "now");
        if (!settings.enabled()) return respond(CLOSED_CODE, "O Coliseu está fechado para manutenção.");
        ArenaMatch current = matches.current().orElse(null);
        if (current != null) {
            if (current.kitMode() == ArenaKitMode.STAKED_SURVIVAL
                    && current.phase() == ArenaPhase.RESERVED_READY
                    && stakeConsentDeadline != null && !now.isBefore(stakeConsentDeadline)
                    && current.stakedEscrow().filter(StakedEscrow::canAdmit).isEmpty()) {
                return recoverCurrent("STAKE_CONSENT_TIMEOUT");
            }
            if (current.phase() == ArenaPhase.RESERVED_READY && readyDeadline != null && !now.isBefore(readyDeadline)) {
                current.handleDisconnect(current.participants().iterator().next());
                return recoverCurrent("READY_TIMEOUT");
            }
            if (current.phase() == ArenaPhase.ACTIVE && combatDeadline != null && !now.isBefore(combatDeadline)) {
                current.finishTimedDraw();
                return finishCurrent("ROUND_TIMEOUT");
            }
            return new ArenaPlayerResponse("NO_QUEUE_MATCH", "A arena já está reservada.", Optional.of(current.id()));
        }
        for (ArenaFormat format : supportedFormats()) {
            for (ArenaKitMode mode : List.of(ArenaKitMode.FIXED, ArenaKitMode.MIRRORED_SURVIVAL)) {
                Optional<QueueMatchProposal> proposal = queue.tryMatch(format, mode);
                if (proposal.isEmpty()) continue;
                if (!contractsCompatible(proposal.orElseThrow())) {
                    requeue(proposal.orElseThrow());
                    continue;
                }
                ArenaMatch match = reserveProposal(proposal.orElseThrow(), now);
                if (match != null) {
                    return new ArenaPlayerResponse("MATCH_RESERVED", "Partida encontrada. Confirma quando estiveres pronto.",
                            Optional.of(match.id()));
                }
            }
        }
        return new ArenaPlayerResponse("QUEUE_WAITING", "Ainda procuramos jogadores.", Optional.empty());
    }

    public synchronized ArenaPlayerResponse forfeit(Player player, String reason) {
        Objects.requireNonNull(player, "player");
        if (!mainThread(player)) return respond("MAIN_THREAD_REQUIRED", "Esta operação tem de ocorrer no servidor principal.");
        ArenaMatch match = matches.current().orElse(null);
        if (match == null || !match.participants().contains(player.getUniqueId())) {
            return respond("NO_MATCH", "Não estás numa partida do Coliseu.");
        }
        match.forfeit(player.getUniqueId(), reason);
        if (!markEliminated(player)) return recoverCurrent("SPECTATOR_TRANSFER_FAILED");
        return match.phase() == ArenaPhase.FINISHING
                ? finishCurrent(reason)
                : new ArenaPlayerResponse("TEAM_CONTINUES",
                        "Ficaste fora deste combate; a tua equipa continua em jogo.", Optional.of(match.id()));
    }

    /** Controlled defeat used by Paper damage/death routing; ordinary drops never run. */
    public synchronized ArenaPlayerResponse eliminate(Player player, String reason) {
        Objects.requireNonNull(player, "player");
        ArenaMatch match = matches.current().orElse(null);
        if (match == null || match.phase() != ArenaPhase.ACTIVE
                || !match.participants().contains(player.getUniqueId())) {
            return respond("NO_ACTIVE_COMBAT", "Não tens um combate ativo no Coliseu.");
        }
        if (!match.eliminate(player.getUniqueId(), reason)) {
            return new ArenaPlayerResponse("ALREADY_ELIMINATED", "Já estás fora deste combate.",
                    Optional.of(match.id()));
        }
        if (!markEliminated(player)) return recoverCurrent("SPECTATOR_TRANSFER_FAILED");
        return match.phase() == ArenaPhase.FINISHING
                ? finishCurrent(reason)
                : new ArenaPlayerResponse("ELIMINATED",
                        "Foste eliminado; a tua equipa continua em jogo.", Optional.of(match.id()));
    }

    public synchronized ArenaPlayerResponse disconnect(Player player) {
        Objects.requireNonNull(player, "player");
        ArenaMatch match = matches.current().orElse(null);
        if (match == null || !match.participants().contains(player.getUniqueId())) {
            return respond("NO_MATCH", "Não tens uma partida do Coliseu para recuperar.");
        }
        if (!match.restoredPlayers().contains(player.getUniqueId())) {
            departingConnections.putIfAbsent(player.getUniqueId(),
                    connections.connectionId(player).orElse(connectionByPlayer.get(player.getUniqueId())));
            UUID sessionId = sessionByPlayer.get(player.getUniqueId());
            if (sessionId != null) admissions.revokeSession(sessionId);
        }
        ArenaDisconnectDisposition disposition = match.handleDisconnect(player.getUniqueId());
        return switch (disposition) {
            case ACTIVE_FORFEIT -> match.phase() == ArenaPhase.FINISHING
                    ? finishCurrent("DISCONNECT")
                    : new ArenaPlayerResponse("TEAM_CONTINUES",
                            "A tua saída foi registada; a tua equipa continua em jogo.", Optional.of(match.id()));
            case READY_CHECK_RECOVERY, RESTORATION_RECOVERY -> recoverCurrent("DISCONNECT");
            case IGNORED_TERMINAL -> respond("ALREADY_CLOSED", "A partida do Coliseu já terminou.");
        };
    }

    /** Called only by provider-completed authentication recovery, never by join/quit routing. */
    public synchronized ArenaPlayerResponse resumeAfterAuthentication(Player player) {
        Objects.requireNonNull(player, "player");
        AuthenticatedSession evidence = authentication == null ? null
                : authentication.current(player.getUniqueId(), clock.instant()).orElse(null);
        if (!server.isPrimaryThread() || !player.isOnline() || !player.isValid() || evidence == null
                || connectionRegistry == null || !connectionRegistry.isCurrent(player, evidence.connectionId())) {
            return respond("AUTHENTICATION_REQUIRED", "Conclui primeiro a autenticação da tua sessão.");
        }
        ArenaMatch match = matches.current().orElse(null);
        if (match == null || !match.participants().contains(player.getUniqueId())) {
            return respond("NO_MATCH", "Não tens uma partida do Coliseu para recuperar.");
        }
        if (departingConnections.containsKey(player.getUniqueId())) {
            if (evidence.connectionId().equals(departingConnections.get(player.getUniqueId()))) {
                return pendingRestoration(match);
            }
            departingConnections.remove(player.getUniqueId());
        }
        return recoverCurrent("AUTHENTICATED_RECONNECT");
    }

    /** Stops the match without treating each still-connected actor as a departing connection. */
    public synchronized ArenaPlayerResponse recoverForShutdown() {
        return recoverCurrent("MODULE_SHUTDOWN");
    }

    public synchronized ArenaStatus status() {
        Optional<ArenaMatch> current = matches.current();
        String message = !settings.enabled() ? "O Coliseu está fechado para manutenção."
                : current.map(value -> "Coliseu: " + phasePtPt(value.phase()) + ".")
                .orElse("O Coliseu está à procura de jogadores.");
        int queuedPlayers = queue.tickets().stream()
                .mapToInt(ticket -> ticket.partyRoster().players().size()).sum();
        return new ArenaStatus(settings.enabled(), queuedPlayers, current.map(ArenaMatch::phase),
                current.map(ArenaMatch::id), message);
    }

    public synchronized Optional<ArenaMatch> currentMatch() { return matches.current(); }

    /** Exact members still waiting in queue; reserved participants remain in the match roster instead. */
    public synchronized Set<UUID> queuedPlayerIds() { return Set.copyOf(ticketByPlayer.keySet()); }

    private boolean containsQueuedPlayer(TeamRoster roster) {
        return roster.players().stream().anyMatch(ticketByPlayer::containsKey);
    }

    /**
     * Moves a safely authenticated non-participant away from the configured
     * combat floor. A false result means Paper rejected the required teleport.
     */
    public synchronized boolean relocateUnaffiliatedFloorOccupant(Player player) {
        Objects.requireNonNull(player, "player");
        if (!server.isPrimaryThread()) return false;
        if (!settings.enabled() || !player.isOnline() || !player.isValid()) return true;
        UUID playerId = player.getUniqueId();
        if (matches.current().filter(match -> match.participants().contains(playerId)).isPresent()) return true;
        Optional<ArenaLocationPolicy> locations = settings.locations();
        if (locations.isEmpty()) return true;
        Optional<ProtectedRegion> currentRegion = regions.at(player.getLocation());
        if (currentRegion.map(ProtectedRegion::id)
                .filter(locations.orElseThrow().combatFloorRegion()::equals).isEmpty()) return true;
        try {
            return player.teleport(settings.spectatorFallback().orElseThrow().clone(),
                    PlayerTeleportEvent.TeleportCause.PLUGIN);
        } catch (RuntimeException rejected) {
            return false;
        }
    }

    private ArenaMatch reserveProposal(QueueMatchProposal proposal, Instant now) {
        try {
            ArenaMatch match = matches.reserve(UUID.randomUUID(), proposal.format(), proposal.kitMode(),
                    proposal.teamA(), proposal.teamB(), settings.formatPolicy());
            for (QueueTicket ticket : proposal.sourceTickets()) {
                ArenaEquipmentContract contract = contractByTicket.remove(ticket.ticketId());
                ticket.partyRoster().players().forEach(ticketByPlayer::remove);
                for (UUID player : ticket.partyRoster().players()) {
                    contractByPlayer.put(player, contract);
                }
            }
            bindMatchContracts(match, proposal.sourceTickets().stream()
                    .map(contractByTicket::get).filter(Objects::nonNull).findFirst().orElse(null));
            readyDeadline = now.plus(settings.readyTimeout());
            return match;
        } catch (RuntimeException failure) {
            requeue(proposal);
            return null;
        }
    }

    private void bindMatchContracts(ArenaMatch match, ArenaEquipmentContract fallback) {
        for (UUID player : match.participants()) contractByPlayer.putIfAbsent(player, fallback);
        validateRegions();
    }

    private ArenaPlayerResponse prepareAndActivate(ArenaMatch match, Instant now) {
        if (match.phase() != ArenaPhase.RESERVED_READY || !match.allReady()) {
            return respond("READY_INCOMPLETE", "Todos os jogadores têm de confirmar primeiro.");
        }
        try {
            validateRegions();
            if (match.kitMode() == ArenaKitMode.STAKED_SURVIVAL) withdrawStake(match);
            for (UUID playerId : match.participants()) {
                Player player = requireOnline(playerId);
                ArenaEquipmentContract contract = Objects.requireNonNull(contractByPlayer.get(playerId), "equipment contract");
                if (contract.mode() == ArenaKitMode.MIRRORED_SURVIVAL) {
                    ArenaLoadoutSnapshot captured = ArenaLoadoutSnapshot.capture(player);
                    protectedWithheldItems.put(playerId,
                            captured.prohibitedItemCount(settings.prohibitedMaterials()));
                    loadouts.put(playerId, captured.withoutMaterials(settings.prohibitedMaterials()));
                }
                UUID requestId = operationId(match.id(), playerId, "ADMISSION");
                UUID sessionId = operationId(match.id(), playerId, "SESSION");
                UUID snapshotId = operationId(match.id(), playerId, "SNAPSHOT");
                UUID connectionId = connectionByPlayer.getOrDefault(playerId,
                        connections.connectionId(player).orElseThrow(() -> new IllegalStateException("AUTHENTICATION_REQUIRED")));
                connectionByPlayer.put(playerId, connectionId);
                AdmissionResult result = sessions.prepare(new AdmissionRequest(requestId, sessionId, snapshotId,
                        match.id(), playerId, connectionId, GameKey.ARENA, now));
                if (result.status() != AdmissionStatus.PREPARED) throw new IllegalStateException(result.code());
                sessionByPlayer.put(playerId, sessionId);
                match.recordSnapshot(new PlayerStateOperation(playerId, snapshotId, now));
            }
            match.transitionTo(ArenaPhase.ADMITTING);
            for (UUID playerId : match.participants()) {
                Player player = requireOnline(playerId);
                UUID sessionId = sessionByPlayer.get(playerId);
                Instant expires = now.plus(settings.admissionTokenLifetime());
                ArenaAdmissionToken floorToken = match.issueAdmissionToken(playerId, UUID.randomUUID(), now, expires);
                admissions.issue(new RegionAdmissionToken(UUID.randomUUID(), sessionId, playerId,
                        settings.locations().orElseThrow().combatFloorRegion(), now, expires));
                applyEquipment(player, contractByPlayer.get(playerId), sessionId);
                if (!player.teleport(spawnFor(match, playerId), PlayerTeleportEvent.TeleportCause.PLUGIN)) {
                    throw new IllegalStateException("TELEPORT_REJECTED");
                }
                if (!match.mayEnterFloor(floorToken, now)) throw new IllegalStateException("FLOOR_TOKEN_REJECTED");
            }
            combatPolicies.register(combatPolicy(match));
            for (UUID playerId : match.participants()) {
                sessions.activate(sessionByPlayer.get(playerId), operationId(match.id(), playerId, "ACTIVATE"), clock.instant());
            }
            match.transitionTo(ArenaPhase.ACTIVE);
            combatStartedAt = clock.instant();
            combatDeadline = combatStartedAt.plus(settings.roundDuration());
            // Entry tokens are short lived; an admitted fighter needs region access
            // for the full active round, including native projectile validation.
            for (UUID playerId : match.participants()) {
                admissions.issue(new RegionAdmissionToken(UUID.randomUUID(), sessionByPlayer.get(playerId), playerId,
                        settings.locations().orElseThrow().combatFloorRegion(), combatStartedAt, combatDeadline));
            }
            return new ArenaPlayerResponse("COMBAT_STARTED", "O combate começou.", Optional.of(match.id()));
        } catch (RuntimeException failure) {
            reportFailure(match, "PREPARATION_FAILED", failure);
            return recoverCurrent("PREPARATION_FAILED");
        }
    }

    private ArenaPlayerResponse finishCurrent(String reason) {
        ArenaMatch match = matches.current().orElse(null);
        if (match == null) return respond("NO_MATCH", "Não há uma partida ativa.");
        try {
            if (match.phase() == ArenaPhase.ACTIVE) match.transitionTo(ArenaPhase.FINISHING);
            if (match.phase() != ArenaPhase.FINISHING) return recoverCurrent(reason);
            beginRestoration(match, reason, false);
            match.transitionTo(ArenaPhase.RESTORING);
            teleportParticipantsToFallback(match);
            restoreSessions(match, restorationPlan.resultId(), false, restorationPlan.reason());
            if (!match.allRestored()) return pendingRestoration(match);
            match.completeRestore();
            deliverOnlineStakeClaims(match);
            ResultRecording recording = recordArenaResult(match, restorationPlan.reason());
            cleanup(match);
            return recording.committed()
                    ? new ArenaPlayerResponse("RESTORED", "O teu estado survival foi restaurado.", Optional.empty())
                    : new ArenaPlayerResponse("RESTORED_UNRANKED", "O teu estado foi restaurado, mas o resultado ficou fora do ranking.", Optional.empty());
        } catch (RuntimeException failure) {
            reportFailure(match, "RESTORE_FAILED", failure);
            return respond("RESTORE_QUARANTINED", "A recuperação precisa de revisão; a partida ficou fechada.");
        }
    }

    private ArenaPlayerResponse recoverCurrent(String reason) {
        ArenaMatch match = matches.current().orElse(null);
        if (match == null) return respond("NO_MATCH", "Não há uma partida reservada.");
        String finalReason = restorationPlan == null ? reason : restorationPlan.reason();
        boolean intentionalPrestartEnd = switch (finalReason) {
            case "PLAYER_LEFT", "READY_TIMEOUT", "DISCONNECT", "STAKE_CONSENT_TIMEOUT" -> true;
            default -> false;
        };
        boolean cancelledBeforeCombat = intentionalPrestartEnd
                && match.snapshottedPlayers().isEmpty() && combatStartedAt == null;
        try {
            if (match.phase() != ArenaPhase.RECOVERING) match.transitionTo(ArenaPhase.RECOVERING);
            beginRestoration(match, reason, true);
            teleportParticipantsToFallback(match);
            restoreSessions(match, restorationPlan.resultId(), true, restorationPlan.reason());
            if (!match.allRestored()) return pendingRestoration(match);
            match.transitionTo(ArenaPhase.RESTORING);
            match.completeRestore();
            deliverOnlineStakeClaims(match);
            if (restorationPlan.recovery()) recordNoContest(match, restorationPlan.reason());
            else recordArenaResult(match, restorationPlan.reason());
            cleanup(match);
            if (cancelledBeforeCombat) {
                String message = "READY_TIMEOUT".equals(finalReason)
                        ? "O tempo para confirmar terminou; a partida foi cancelada antes do combate."
                        : "A partida foi cancelada antes do combate.";
                return respond("PRESTART_CANCELLED", message);
            }
            return new ArenaPlayerResponse("RECOVERED", "A recuperação terminou com segurança.", Optional.empty());
        } catch (RuntimeException failure) {
            reportFailure(match, "RECOVERY_FAILED", failure);
            return respond("RECOVERY_QUARANTINED", "A recuperação precisa de revisão; a arena permanece fechada.");
        }
    }

    private record RestorationPlan(UUID resultId, String reason, boolean recovery) {}

    private void beginRestoration(ArenaMatch match, String reason, boolean recovery) {
        if (restorationPlan == null) {
            restorationPlan = new RestorationPlan(ResultIds.forMatch(match.id()), reason, recovery);
        }
        match.finalizeOnce(restorationPlan.resultId());
        if (!stakeFinalized) {
            finalizeStake(match, restorationPlan.resultId(), restorationPlan.reason(), restorationPlan.recovery());
            stakeFinalized = true;
        }
    }

    private ArenaPlayerResponse pendingRestoration(ArenaMatch match) {
        return new ArenaPlayerResponse("RECOVERY_PENDING",
                "A recuperação aguarda a autenticação dos jogadores que saíram; a arena permanece fechada.",
                Optional.of(match.id()));
    }

    private void reportFailure(ArenaMatch match, String code, RuntimeException failure) {
        // Messages can contain private provider state; retain only bounded source diagnostics.
        StringBuilder diagnostic = new StringBuilder("Falha do Coliseu [")
                .append(code).append("] partida=").append(match.id());
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 4; depth++, cause = cause.getCause()) {
            diagnostic.append("; tipo=").append(cause.getClass().getName());
            StackTraceElement[] frames = cause.getStackTrace();
            for (int i = 0; i < Math.min(frames.length, 4); i++) {
                diagnostic.append("; origem=").append(frames[i].getClassName())
                        .append('.').append(frames[i].getMethodName()).append(':').append(frames[i].getLineNumber());
            }
        }
        try { server.getLogger().warning(diagnostic.toString()); }
        catch (RuntimeException ignored) { /* Diagnostics cannot interrupt recovery. */ }
    }

    private void restoreSessions(ArenaMatch match, UUID resultId, boolean recovery, String reason) {
        for (UUID playerId : match.snapshottedPlayers()) {
            if (match.restoredPlayers().contains(playerId) || departingConnections.containsKey(playerId)) continue;
            Player player = server.getPlayer(playerId);
            if (player == null || !player.isOnline() || !player.isValid()) {
                departingConnections.putIfAbsent(playerId, connectionByPlayer.get(playerId));
                continue;
            }
            if (!mayRestoreOnline(player)) continue;
            UUID sessionId = sessionByPlayer.get(playerId);
            if (sessionId == null) continue;
            AdmissionResult result = recovery
                    ? sessions.recover(sessionId, resultId, reason)
                    : sessions.finishAndRestore(sessionId, resultId, reason);
            if (result.status() != AdmissionStatus.RECOVERED) throw new IllegalStateException(result.code());
            match.recordRestore(new PlayerStateOperation(playerId, operationId(match.id(), playerId, "RESTORE"), clock.instant()));
        }
    }

    private ResultRecording recordArenaResult(ArenaMatch match, String reason) {
        Instant finished = clock.instant();
        Map<UUID, PlayerResult> standings = new LinkedHashMap<>();
        Set<UUID> teamA = Set.copyOf(match.teamA().players());
        Set<UUID> teamB = Set.copyOf(match.teamB().players());
        Set<UUID> forfeiting = match.forfeits().keySet();
        Set<UUID> eliminated = match.eliminations().keySet();
        boolean teamALost = match.teamADefeated();
        boolean teamBLost = match.teamBDefeated();
        MatchOutcome outcome = teamALost ^ teamBLost ? MatchOutcome.VICTORY
                : "ROUND_TIMEOUT".equals(reason) || teamALost && teamBLost
                        ? MatchOutcome.DRAW : MatchOutcome.NO_CONTEST;
        Set<UUID> winners = teamALost ? teamB : teamBLost ? teamA : Set.of();
        for (UUID player : match.participants()) {
            boolean winner = outcome == MatchOutcome.VICTORY && winners.contains(player);
            int placement = outcome == MatchOutcome.NO_CONTEST ? 0
                    : outcome == MatchOutcome.DRAW || winner ? 1 : 2;
            String team = teamA.contains(player) ? "a" : "b";
            standings.put(player, new PlayerResult(placement, winner, forfeiting.contains(player),
                    Optional.of(team), Map.of("wins", winner ? 1L : 0L,
                            "forfeits", forfeiting.contains(player) ? 1L : 0L,
                            "survived", eliminated.contains(player) ? 0L : 1L)));
        }
        MatchResult result = MatchResultFactory.create(match.id(), GameKey.ARENA, settings.rulesetDigest(),
                statisticsMode(match), combatStartedAt == null ? finished : combatStartedAt,
                finished, outcome, outcome == MatchOutcome.NO_CONTEST ? "NO_CONTEST" : reason, standings);
        return statistics.record(result);
    }

    private ResultRecording recordNoContest(ArenaMatch match, String reason) {
        Instant finished = clock.instant();
        return statistics.record(MatchResultFactory.noContest(match.id(), GameKey.ARENA, settings.rulesetDigest(),
                statisticsMode(match), finished, finished, reason, match.participants()));
    }

    private StakedEscrow prepareStake(DirectChallenge challenge, Instant now) {
        if (!stakedAvailable()) throw new IllegalStateException("STAKED_ESCROW_UNAVAILABLE");
        Set<UUID> participants = new LinkedHashSet<>();
        participants.addAll(challenge.challengerRoster().players());
        participants.addAll(challenge.targetRoster().players());
        if (participants.size() != 2 || !settings.stakedFormats().contains(challenge.format())) {
            throw new IllegalStateException("STAKED_1V1_ONLY");
        }
        Map<UUID, ArenaLoadoutSnapshot> captured = new LinkedHashMap<>();
        Map<UUID, List<com.ciaac.minecraft.minigames.arena.StakedItem>> offered = new LinkedHashMap<>();
        for (UUID playerId : participants) {
            Player player = requireOnline(playerId);
            if (player.getItemOnCursor() != null && !player.getItemOnCursor().isEmpty()) {
                throw new IllegalStateException("STAKED_CURSOR_NOT_EMPTY");
            }
            ArenaLoadoutSnapshot loadout = ArenaLoadoutSnapshot.capture(player);
            ArenaItemManifest manifest = manifestBuilder.build(
                    playerId, loadout, settings.stakedProhibitedMaterials());
            if (!manifest.admissible()) throw new IllegalStateException("STAKED_PROHIBITED_ITEM");
            captured.put(playerId, loadout);
            offered.put(playerId, manifest.items());
        }
        UUID escrowId = operationId(challenge.id(), challenge.id(), "ESCROW");
        StakedEscrow stake = StakedEscrow.prepare(escrowId, challenge.id(), settings.rulesetDigest(), now,
                challenge.format(), participants, offered, settings.stakedProhibitedMaterials());
        if (!stake.quarantined().isEmpty()) throw new IllegalStateException("STAKED_PROHIBITED_ITEM");
        Map<UUID, StakedInventoryPayload> payloads = new LinkedHashMap<>();
        captured.forEach((playerId, loadout) -> payloads.put(playerId,
                StakedInventoryPayload.capture(playerId, stake.manifestDigest(), loadout)));
        StakedOperationResult persisted = escrowPort.orElseThrow().prepare(
                new StakedEscrowPort.PrepareRequest(
                        operationId(challenge.id(), escrowId, "PREPARE_ESCROW"), stake,
                        payloads, settings.stakedProhibitedMaterials()));
        if (!persisted.accepted()) throw new IllegalStateException(persisted.code());
        loadouts.putAll(captured);
        stakedPayloadByPlayer.putAll(payloads);
        return stake;
    }

    private void sendStakeReview(ArenaMatch match, StakedEscrow stake) {
        for (UUID playerId : match.participants()) {
            Player player = server.getPlayer(playerId);
            if (player == null) continue;
            int ownStacks = stake.manifest().getOrDefault(playerId, List.of()).size();
            int otherStacks = stake.manifest().entrySet().stream()
                    .filter(entry -> !entry.getKey().equals(playerId))
                    .mapToInt(entry -> entry.getValue().size()).sum();
            try {
                player.sendMessage("§6Aposta 1v1 preparada: §f" + ownStacks + " lotes teus §7contra §f"
                        + otherStacks + " lotes do adversário§7.");
                player.sendMessage("§eA proposta inclui inventário, armadura e mão secundária completos. "
                        + "Itens proibidos rejeitam a proposta inteira e nada é removido.");
                player.sendMessage("§cSó confirma se aceitares perder todo esse equipamento: "
                        + "§f/coliseu aposta confirmar");
            } catch (RuntimeException ignored) {
                // Informational delivery must not turn a valid reservation into recovery.
            }
        }
    }

    private void withdrawStake(ArenaMatch match) {
        StakedEscrow stake = match.stakedEscrow().orElseThrow();
        StakedEscrowPort port = escrowPort.orElseThrow();
        if (!stake.canAdmit()) throw new IllegalStateException("STAKE_CONSENT_REQUIRED");
        Map<UUID, StakedInventoryPayload> livePayloads = new LinkedHashMap<>();
        for (UUID playerId : match.participants()) {
            Player player = requireOnline(playerId);
            StakedInventoryPayload expected = Objects.requireNonNull(
                    stakedPayloadByPlayer.get(playerId), "staked payload");
            StakedInventoryPayload live = stakedInventories.capture(player, stake.manifestDigest());
            if (!expected.exactlyMatches(live)) throw new IllegalStateException("STAKE_INVENTORY_CHANGED");
            StakedOperationResult verified = port.verifyInventory(
                    new StakedEscrowPort.InventoryVerificationRequest(
                            operationId(match.id(), playerId, "VERIFY_WITHDRAWAL"),
                            stake.escrowId(), playerId, live));
            if (!verified.accepted()) throw new IllegalStateException(verified.code());
            livePayloads.put(playerId, live);
        }
        for (UUID playerId : match.participants()) {
            Player player = requireOnline(playerId);
            StakedInventoryPayload payload = livePayloads.get(playerId);
            UUID beginId = operationId(match.id(), playerId, "BEGIN_WITHDRAWAL");
            StakedOperationResult begun = port.beginWithdrawal(new StakedEscrowPort.WithdrawalRequest(
                    beginId, stake.escrowId(), playerId, payload.payloadSha256()));
            if (!begun.accepted()) throw new IllegalStateException(begun.code());
            stakedInventories.withdrawExact(player, payload);
            StakedOperationResult committed = port.commitWithdrawal(
                    new StakedEscrowPort.WithdrawalCommitRequest(
                            operationId(match.id(), playerId, "COMMIT_WITHDRAWAL"), beginId,
                            stake.escrowId(), playerId, payload.payloadSha256()));
            if (!committed.accepted()) throw new IllegalStateException(committed.code());
        }
    }

    private void finalizeStake(ArenaMatch match, UUID resultId, String reason, boolean recovery) {
        if (match.kitMode() != ArenaKitMode.STAKED_SURVIVAL) return;
        StakedEscrow stake = match.stakedEscrow().orElseThrow();
        StakedEscrowPort port = escrowPort.orElseThrow();
        UUID winner = !recovery && (match.teamADefeated() ^ match.teamBDefeated())
                ? (match.teamADefeated() ? match.teamB().players() : match.teamA().players()).iterator().next()
                : null;
        if (winner != null) {
            StakedOperationResult settled = port.settle(new StakedEscrowPort.SettlementRequest(
                    operationId(match.id(), winner, "SETTLE_ESCROW"), stake.escrowId(), resultId, winner));
            if (!settled.accepted()) throw new IllegalStateException(settled.code());
            stake.settleToWinnerOnce(resultId, winner);
            return;
        }
        Map<UUID, UUID> beneficiaries = match.participants().stream()
                .collect(Collectors.toMap(player -> player, player -> player));
        String refundReason = machineCode(reason);
        StakedOperationResult refunded = port.refund(new StakedEscrowPort.RefundRequest(
                operationId(match.id(), stake.escrowId(), "REFUND_ESCROW"), stake.escrowId(), resultId,
                refundReason, beneficiaries));
        if (!refunded.accepted()) throw new IllegalStateException(refunded.code());
        stake.refundOnce(resultId, refundReason);
    }

    private void deliverOnlineStakeClaims(ArenaMatch match) {
        if (match.kitMode() != ArenaKitMode.STAKED_SURVIVAL) return;
        for (UUID participant : match.participants()) {
            Player player = server.getPlayer(participant);
            if (player == null || !player.isOnline() || !player.isValid()) continue;
            int delivered = deliverClaims(player);
            if (delivered > 0) player.sendMessage("§aRecebeste " + delivered
                    + (delivered == 1 ? " lote da aposta." : " lotes da aposta."));
            if (!escrowPort.orElseThrow().pendingClaimsFor(participant).isEmpty()) {
                player.sendMessage("§eAinda há loot guardado. Liberta espaço e usa /coliseu aposta reclamar.");
            }
        }
    }

    private int deliverClaims(Player player) {
        StakedEscrowPort port = escrowPort.orElseThrow();
        int delivered = 0;
        for (StakedClaim claim : port.pendingClaimsFor(player.getUniqueId())) {
            if (claim.state() != StakedClaimState.PENDING) continue;
            Optional<StakedInventoryPayload> preview = port.previewClaim(
                    claim.escrowId(), claim.claimId(), player.getUniqueId());
            if (preview.isEmpty() || !stakedInventories.canDeliver(player, claim, preview.orElseThrow())) continue;
            UUID beginId = UUID.randomUUID();
            var start = port.beginDelivery(new StakedEscrowPort.DeliveryBeginRequest(
                    beginId, claim.escrowId(), claim.claimId(), player.getUniqueId(), claim.payloadDigest()));
            if (!start.outcome().accepted() || start.payload().isEmpty() || start.claim().isEmpty()) break;
            try {
                stakedInventories.deliver(player, start.claim().orElseThrow(), start.payload().orElseThrow());
            } catch (RuntimeException ambiguous) {
                player.sendMessage("§cA entrega da aposta precisa de revisão administrativa. Não tentes repeti-la.");
                break;
            }
            StakedOperationResult committed = port.commitDelivery(
                    new StakedEscrowPort.DeliveryCommitRequest(UUID.randomUUID(), beginId,
                            claim.escrowId(), claim.claimId(), claim.payloadDigest()));
            if (!committed.accepted()) {
                player.sendMessage("§cA entrega foi bloqueada para revisão administrativa.");
                break;
            }
            delivered++;
        }
        return delivered;
    }

    private boolean stakedAvailable() {
        if (!settings.stakedEnabled() || escrowPort.isEmpty()) return false;
        return escrowPort.orElseThrow().outstandingEscrows().stream().noneMatch(snapshot ->
                snapshot.state().recoveryBlocking()
                        || snapshot.state() == StakedEscrowState.PREPARED
                        || snapshot.state() == StakedEscrowState.CONSENTED
                        || snapshot.state() == StakedEscrowState.WITHDRAWN);
    }

    private boolean refundPreparedStake(StakedEscrow stake) {
        Map<UUID, UUID> beneficiaries = stake.participants().stream()
                .collect(Collectors.toMap(player -> player, player -> player));
        UUID resultId = operationId(stake.matchId(), stake.escrowId(), "UNRESERVED_REFUND_RESULT");
        StakedOperationResult refunded = escrowPort.orElseThrow().refund(new StakedEscrowPort.RefundRequest(
                operationId(stake.matchId(), stake.escrowId(), "UNRESERVED_REFUND"),
                stake.escrowId(), resultId, "RESERVATION_FAILED", beneficiaries));
        return refunded.accepted();
    }

    private void clearPreparedStake(StakedEscrow stake) {
        if (stake == null) return;
        for (UUID participant : stake.participants()) {
            loadouts.remove(participant);
            stakedPayloadByPlayer.remove(participant);
        }
    }

    private ArenaPlayerResponse challengeFailure(RuntimeException failure) {
        String code = Optional.ofNullable(failure.getMessage()).orElse("");
        return switch (code) {
            case "STAKED_CURSOR_NOT_EMPTY" -> respond(code,
                    "Coloca primeiro o item que tens no cursor; nada foi retirado.");
            case "STAKED_PROHIBITED_ITEM", "BLACKLISTED_MATERIAL" -> respond("STAKED_PROHIBITED_ITEM",
                    "A proposta contém um item proibido. A aposta inteira foi rejeitada e nada foi retirado.");
            case "PLAYER_OFFLINE" -> respond(code,
                    "Todos os participantes têm de estar online para rever a aposta.");
            case "STAKED_1V1_ONLY" -> respond(code, "As apostas estão limitadas a desafios 1v1.");
            default -> respond("CHALLENGE_REJECTED", "O Coliseu não conseguiu reservar a arena.");
        };
    }

    private static String machineCode(String value) {
        String normalized = Objects.requireNonNull(value, "value").trim()
                .toUpperCase(java.util.Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        if (normalized.isEmpty()) normalized = "NO_CONTEST";
        if (!Character.isLetter(normalized.charAt(0))) normalized = "R_" + normalized;
        return normalized.substring(0, Math.min(64, normalized.length()));
    }

    private void cleanup(ArenaMatch match) {
        combatPolicies.remove(match.id());
        for (UUID playerId : match.participants()) {
            UUID sessionId = sessionByPlayer.remove(playerId);
            if (sessionId != null) admissions.revokeSession(sessionId);
            loadouts.remove(playerId);
            protectedWithheldItems.remove(playerId);
            contractByPlayer.remove(playerId);
            connectionByPlayer.remove(playerId);
        }
        matches.release(match);
        readyDeadline = null;
        combatStartedAt = null;
        combatDeadline = null;
        stakeConsentDeadline = null;
        stakedPayloadByPlayer.clear();
        departingConnections.clear();
        restorationPlan = null;
        stakeFinalized = false;
    }

    private boolean markEliminated(Player player) {
        UUID sessionId = sessionByPlayer.get(player.getUniqueId());
        if (sessionId != null) admissions.revokeSession(sessionId);
        var maximumHealthAttribute = player.getAttribute(Attribute.MAX_HEALTH);
        if (maximumHealthAttribute == null
                || !Double.isFinite(maximumHealthAttribute.getValue())
                || maximumHealthAttribute.getValue() <= 0.0D) {
            return false;
        }
        player.setGameMode(org.bukkit.GameMode.SPECTATOR);
        player.setHealth(maximumHealthAttribute.getValue());
        player.setVelocity(new org.bukkit.util.Vector());
        player.setFallDistance(0.0F);
        if (!player.teleport(settings.spectatorFallback().orElseThrow().clone(),
                PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            return false;
        }
        player.sendMessage("§eFicas em modo espectador até o estado survival ser restaurado.");
        return true;
    }

    private static String statisticsMode(ArenaMatch match) {
        String equipment = switch (match.kitMode()) {
            case FIXED -> "fixed";
            case MIRRORED_SURVIVAL -> "protected";
            case STAKED_SURVIVAL -> "staked";
        };
        return match.format().notation() + "-" + equipment;
    }

    private static String phasePtPt(ArenaPhase phase) {
        return switch (phase) {
            case IDLE, WAITING -> "à espera de jogadores";
            case RESERVED_READY -> "em preparação";
            case ADMITTING -> "a validar os participantes";
            case ACTIVE -> "combate em curso";
            case FINISHING -> "a terminar o combate";
            case RESTORING -> "a restaurar os jogadores";
            case RECOVERING -> "em recuperação segura";
            case CLOSED -> "fechado";
        };
    }

    private void applyEquipment(Player player, ArenaEquipmentContract contract, UUID sessionId) {
        if (contract.mode() == ArenaKitMode.MIRRORED_SURVIVAL
                || contract.mode() == ArenaKitMode.STAKED_SURVIVAL) {
            loadouts.get(player.getUniqueId()).apply(player);
            if (contract.mode() == ArenaKitMode.MIRRORED_SURVIVAL) {
                int withheld = protectedWithheldItems.getOrDefault(player.getUniqueId(), 0);
                if (withheld > 0) {
                    player.sendMessage("§e" + withheld + " item(ns) proibido(s) ficaram guardados fora do combate "
                            + "e regressam com o teu estado survival.");
                }
            }
            return;
        }
        List<ItemStack> kit = kits.find(contract.fixedKitId()).orElseThrow(
                () -> new IllegalStateException("FIXED_KIT_UNAVAILABLE"));
        var layout = ArenaFixedKitLayout.arrange(kit, player.getInventory().getStorageContents().length);
        ItemStack[] contents = new ItemStack[player.getInventory().getStorageContents().length];
        for (int index = 0; index < layout.storage().size(); index++) {
            contents[index] = temporaryItems.tag(layout.storage().get(index), sessionId, GameKey.ARENA);
        }
        player.getInventory().setContents(contents);
        player.getInventory().setHelmet(tagEquipment(layout, EquipmentSlot.HEAD, sessionId));
        player.getInventory().setChestplate(tagEquipment(layout, EquipmentSlot.CHEST, sessionId));
        player.getInventory().setLeggings(tagEquipment(layout, EquipmentSlot.LEGS, sessionId));
        player.getInventory().setBoots(tagEquipment(layout, EquipmentSlot.FEET, sessionId));
        player.getInventory().setItemInOffHand(layout.offhand() == null ? null
                : temporaryItems.tag(layout.offhand(), sessionId, GameKey.ARENA));
        player.updateInventory();
    }

    private ItemStack tagEquipment(ArenaFixedKitLayout.Layout layout,
                                   EquipmentSlot slot, UUID sessionId) {
        ItemStack item = layout.armor().get(slot);
        return item == null ? null : temporaryItems.tag(item, sessionId, GameKey.ARENA);
    }

    private CombatPolicy combatPolicy(ArenaMatch match) {
        Map<UUID, String> teams = new HashMap<>();
        match.teamA().players().forEach(player -> teams.put(player, "A"));
        match.teamB().players().forEach(player -> teams.put(player, "B"));
        return new CombatPolicy(match.id(), true, false, settings.friendlyFire(), teams);
    }

    private Location spawnFor(ArenaMatch match, UUID playerId) {
        return (match.teamA().players().contains(playerId)
                ? settings.teamASpawn() : settings.teamBSpawn()).orElseThrow().clone();
    }

    private void validateRegions() {
        if (!settings.enabled()) throw new IllegalStateException(CLOSED_CODE);
        String floor = settings.locations().orElseThrow().combatFloorRegion();
        String spectators = settings.locations().orElseThrow().spectatorRegion();
        ProtectedRegion floorRegion = regions.find(floor).orElseThrow(() -> new IllegalStateException("FLOOR_REGION_MISSING"));
        ProtectedRegion spectatorRegion = regions.find(spectators).orElseThrow(
                () -> new IllegalStateException("SPECTATOR_REGION_MISSING"));
        if (floorRegion.game() != GameKey.ARENA || floorRegion.role() != ProtectedRegionRole.PARTICIPANT_ONLY
                || !floorRegion.immutable() || spectatorRegion.game() != GameKey.ARENA
                || spectatorRegion.role() != ProtectedRegionRole.SPECTATOR_PUBLIC
                || !floorRegion.bounds().worldId().equals(settings.worldId().orElseThrow())
                || !spectatorRegion.bounds().worldId().equals(settings.worldId().orElseThrow())) {
            throw new IllegalStateException("ARENA_REGION_POLICY_INVALID");
        }
        if (!floorRegion.bounds().contains(settings.teamASpawn().orElseThrow())
                || !floorRegion.bounds().contains(settings.teamBSpawn().orElseThrow())
                || !spectatorRegion.bounds().contains(settings.spectatorFallback().orElseThrow())) {
            throw new IllegalStateException("ARENA_TELEPORT_POINT_OUTSIDE_REGION");
        }
    }

    private List<ArenaFormat> supportedFormats() {
        LinkedHashSet<ArenaFormat> formats = new LinkedHashSet<>();
        for (int size = 1; size <= settings.formatPolicy().maxTeamSize(); size++) {
            ArenaFormat format = ArenaFormat.standard(size);
            if (settings.formatPolicy().supports(format)) formats.add(format);
        }
        formats.addAll(settings.formatPolicy().allowedAsymmetricFormats());
        return List.copyOf(formats);
    }

    /** Presentation reads the installed format policy; it cannot enable formats. */
    public synchronized List<String> menuFormats() {
        return supportedFormats().stream().map(ArenaFormat::toString).toList();
    }

    private boolean contractsCompatible(QueueMatchProposal proposal) {
        List<ArenaEquipmentContract> contracts = proposal.sourceTickets().stream()
                .map(ticket -> contractByTicket.get(ticket.ticketId())).filter(Objects::nonNull).toList();
        if (contracts.size() != proposal.sourceTickets().size()) return false;
        return proposal.kitMode() != ArenaKitMode.FIXED
                || contracts.stream().map(ArenaEquipmentContract::fixedKitId).distinct().count() == 1;
    }

    private void requeue(QueueMatchProposal proposal) {
        for (QueueTicket ticket : proposal.sourceTickets()) {
            queue.enqueue(ticket);
            for (UUID player : ticket.partyRoster().players()) ticketByPlayer.put(player, ticket.ticketId());
        }
    }

    private Player requireOnline(UUID playerId) {
        Player player = server.getPlayer(playerId);
        if (player == null || !player.isOnline() || !player.isValid()) throw new IllegalStateException("PLAYER_OFFLINE");
        return player;
    }

    private boolean mainThread(Player player) { return player.getServer().isPrimaryThread(); }

    private ArenaPlayerResponse respond(String code, String message) {
        return new ArenaPlayerResponse(code, message, Optional.empty());
    }

    private ArenaPlayerResponse stakedUnavailable() {
        return escrowPort.isEmpty() || !settings.stakedEnabled()
                ? respond("STAKED_ESCROW_UNAVAILABLE",
                "As apostas estão fechadas: falta a persistência durável de escrow e a recuperação auditável.")
                : respond("STAKED_RECOVERY_REQUIRED",
                "As apostas estão temporariamente fechadas porque existe uma recuperação de equipamento pendente.");
    }

    private void teleportParticipantsToFallback(ArenaMatch match) {
        Location fallback = settings.spectatorFallback().orElseThrow().clone();
        // A queued player without a committed snapshot has no location to restore.
        for (UUID playerId : match.snapshottedPlayers()) {
            if (match.restoredPlayers().contains(playerId) || departingConnections.containsKey(playerId)) continue;
            Player player = server.getPlayer(playerId);
            if (player != null && mayRestoreOnline(player)
                    && !player.teleport(fallback.clone(), PlayerTeleportEvent.TeleportCause.PLUGIN)) {
                throw new IllegalStateException("SPECTATOR_FALLBACK_REJECTED");
            }
        }
    }

    private boolean mayRestoreOnline(Player player) {
        if (!player.isOnline() || !player.isValid()) return false;
        if (authentication == null || connectionRegistry == null) return true;
        AuthenticatedSession evidence = authentication.current(player.getUniqueId(), clock.instant()).orElse(null);
        return evidence != null && connectionRegistry.isCurrent(player, evidence.connectionId());
    }

    private static UUID operationId(UUID matchId, UUID subject, String phase) {
        return UUID.nameUUIDFromBytes((matchId + ":" + subject + ":" + phase).getBytes(StandardCharsets.UTF_8));
    }
}
