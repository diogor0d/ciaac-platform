package com.ciaac.minecraft.minigames.paper.module;

import com.ciaac.minecraft.minigames.arena.ArenaEquipmentContract;
import com.ciaac.minecraft.minigames.arena.ArenaKitMode;
import com.ciaac.minecraft.minigames.arena.ArenaMatch;
import com.ciaac.minecraft.minigames.arena.ArenaParty;
import com.ciaac.minecraft.minigames.arena.ArenaPartyRegistry;
import com.ciaac.minecraft.minigames.arena.StakedEscrow;
import com.ciaac.minecraft.minigames.arena.TeamRoster;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.module.ModuleActionResult;
import com.ciaac.minecraft.minigames.module.ModuleStatus;
import com.ciaac.minecraft.minigames.module.MinigameModule;
import com.ciaac.minecraft.minigames.paper.arena.ArenaPlayerResponse;
import com.ciaac.minecraft.minigames.paper.arena.ArenaStatus;
import com.ciaac.minecraft.minigames.paper.arena.ColiseumController;
import com.ciaac.minecraft.minigames.runtime.AdmissionRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/** Portuguese command/runtime adapter for the physical Coliseum queue. */
public final class ColiseumModule implements MinigameModule {
    private final ColiseumController controller;
    private final ModuleIdentity identity;
    private final ColiseumEquipmentPort equipment;
    private final Runnable shutdownAction;
    private final Clock clock;
    private final ArenaPartyRegistry parties;
    private final Duration challengeLifetime;
    private final Set<UUID> queuePlayers = new LinkedHashSet<>();
    private final java.util.Map<UUID, PendingChallenge> pendingChallenges = new java.util.LinkedHashMap<>();
    private UUID readinessPromptMatchId;
    private boolean faulted;

    public ColiseumModule(ColiseumController controller, ModuleIdentity identity,
                          ColiseumEquipmentPort equipment, Clock clock) {
        this(controller, identity, equipment, () -> { }, clock,
                new ArenaPartyRegistry(Duration.ofMinutes(2)), Duration.ofMinutes(2));
    }

    public ColiseumModule(ColiseumController controller, ModuleIdentity identity,
                          ColiseumEquipmentPort equipment, Runnable shutdownAction, Clock clock) {
        this(controller, identity, equipment, shutdownAction, clock,
                new ArenaPartyRegistry(Duration.ofMinutes(2)), Duration.ofMinutes(2));
    }

    public ColiseumModule(ColiseumController controller, ModuleIdentity identity,
                          ColiseumEquipmentPort equipment, Runnable shutdownAction, Clock clock,
                          ArenaPartyRegistry parties, Duration challengeLifetime) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.equipment = Objects.requireNonNull(equipment, "equipment");
        this.shutdownAction = Objects.requireNonNull(shutdownAction, "shutdownAction");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.parties = Objects.requireNonNull(parties, "parties");
        this.challengeLifetime = positive(challengeLifetime, "challengeLifetime");
    }

    @Override public GameKey key() { return GameKey.ARENA; }
    public GameKey game() { return key(); }

    @Override public synchronized Optional<UUID> currentMatchId() {
        if (faulted) return Optional.empty();
        try { return controller.status().matchId(); }
        catch (RuntimeException ignored) { faulted = true; return Optional.empty(); }
    }

    @Override public synchronized ModuleStatus status() {
        if (faulted) return ModuleStatuses.unavailable(key());
        try {
            ArenaStatus value = controller.status();
            String phase = value.phase().map(Enum::name).orElse(value.enabled() ? "WAITING" : "CLOSED");
            boolean joinable = value.enabled() && (value.phase().isEmpty() || phase.equals("WAITING"));
            int participants = controller.currentMatch().map(match -> match.participants().size())
                    .orElse(value.queuedPlayers());
            return ModuleStatuses.of(key(), value.enabled(), phase, joinable, participants,
                    OptionalInt.empty(), value.messagePtPt());
        } catch (RuntimeException ignored) {
            faulted = true;
            return ModuleStatuses.unavailable(key());
        }
    }

    @Override public synchronized ModuleActionResult join(Player player, List<String> arguments) {
        return join(player, String.join(" ", Objects.requireNonNull(arguments, "arguments")));
    }

    @Override public List<String> joinCompletions(List<String> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        return arguments.size() <= 1
                ? List.of("1v1", "2v2", "3v3", "2v3", "3v2")
                : arguments.size() == 2
                ? List.of("kit", "equipamento", "aposta")
                : List.of();
    }

    public synchronized ModuleActionResult join(Player player) { return join(player, "1v1 kit"); }

    public synchronized ModuleActionResult join(Player player, String input) {
        if (faulted) return faultedResult();
        try {
            Objects.requireNonNull(player, "player");
            if (!player.isOnline() || !player.isValid()) {
                return ModuleActionResult.rejected("PLAYER_UNAVAILABLE",
                        "O jogador não está disponível neste momento.");
            }
            ArenaMode mode = parse(input);
            TeamRoster roster = rosterLedBy(player);
            ArenaEquipmentContract contract = validateEquipment(roster, player, mode.kit());
            UUID queueIdentity = currentMatchId().orElseGet(UUID::randomUUID);
            AdmissionRequest request = identity.request(player, key(), queueIdentity).orElse(null);
            if (request == null) {
                return ModuleActionResult.rejected("AUTHENTICATION_REQUIRED",
                        "Conclui primeiro a autenticação da tua sessão.");
            }
            ArenaPlayerResponse response = controller.join(player, request.connectionId(), roster, mode.format(),
                    contract, clock.instant());
            if (response.code().equals("QUEUED")) queuePlayers.addAll(roster.players());
            return ModuleResults.arena(response);
        } catch (IllegalArgumentException invalid) {
            return ModuleActionResult.rejected("MODE_INVALID",
                    "Formato ou equipamento inválido para o Coliseu.");
        } catch (IllegalStateException unavailable) {
            return partyFailure(unavailable.getMessage());
        } catch (RuntimeException ignored) {
            return ModuleResults.failure();
        }
    }

    @Override public synchronized ModuleActionResult leave(Player player) {
        try {
            Player value = Objects.requireNonNull(player, "player");
            Set<UUID> reservedParticipants = controller.currentMatch()
                    .map(ArenaMatch::participants).orElse(Set.of());
            ArenaPlayerResponse response = controller.leave(value);
            if (response.code().equals("PRESTART_CANCELLED")) {
                readinessPromptMatchId = null;
                notifyParticipants(reservedParticipants, response.messagePtPt(), value.getUniqueId());
            }
            reconcileQueuePlayers();
            return ModuleResults.arena(response);
        } catch (RuntimeException ignored) {
            return ModuleResults.failure();
        }
    }

    @Override public synchronized ModuleActionResult ready(Player player) {
        if (faulted) return faultedResult();
        try {
            Player value = Objects.requireNonNull(player, "player");
            requireAuthenticated(value);
            Set<UUID> reservedParticipants = controller.currentMatch()
                    .map(ArenaMatch::participants).orElse(Set.of());
            ArenaPlayerResponse response = controller.ready(value);
            if (response.code().equals("PRESTART_CANCELLED")) {
                readinessPromptMatchId = null;
                notifyParticipants(reservedParticipants, response.messagePtPt(), value.getUniqueId());
            }
            reconcileQueuePlayers();
            return ModuleResults.arena(response);
        } catch (IllegalStateException unavailable) {
            return partyFailure(unavailable.getMessage());
        } catch (RuntimeException ignored) {
            return ModuleResults.failure();
        }
    }

    @Override public synchronized ModuleActionResult action(
            Player player, String action, List<String> arguments) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(arguments, "arguments");
        if (faulted) return faultedResult();
        try {
            requireAuthenticated(player);
            return switch (action.toLowerCase(Locale.ROOT)) {
                case "grupo" -> group(player, arguments);
                case "desafiar" -> challenge(player, arguments);
                case "aceitar" -> acceptChallenge(player, arguments);
                case "aposta" -> stake(player, arguments);
                default -> ModuleActionResult.rejected(
                        "ACTION_UNKNOWN", "Usa estado, entrar, sair, pronto, grupo, desafiar, aceitar, aposta ou ajuda.");
            };
        } catch (IllegalArgumentException invalid) {
            return ModuleActionResult.rejected("REQUEST_INVALID", "Não foi possível validar esse pedido.");
        } catch (IllegalStateException unavailable) {
            return partyFailure(unavailable.getMessage());
        } catch (RuntimeException failure) {
            return ModuleResults.failure();
        }
    }

    @Override public synchronized List<String> actionCompletions(String action, List<String> arguments) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(arguments, "arguments");
        if (action.equalsIgnoreCase("entrar")) return joinCompletions(arguments);
        if (action.equalsIgnoreCase("grupo")) {
            return arguments.size() <= 1
                    ? List.of("criar", "convidar", "aceitar", "sair", "expulsar", "dissolver", "estado")
                    : List.of();
        }
        if (action.equalsIgnoreCase("desafiar")) {
            return arguments.size() == 2
                    ? List.of("1v1", "2v2", "3v3", "2v3", "3v2")
                    : arguments.size() == 3 ? List.of("kit", "equipamento", "aposta") : List.of();
        }
        if (action.equalsIgnoreCase("aposta")) {
            return arguments.size() <= 1 ? List.of("confirmar", "reclamar") : List.of();
        }
        return List.of();
    }

    @Override public synchronized void tick() { tick(clock.instant()); }

    public synchronized void tick(Instant now) {
        if (faulted) return;
        try {
            ArenaMatch previousMatch = controller.currentMatch().orElse(null);
            ArenaPlayerResponse response = controller.tick(Objects.requireNonNull(now, "now"));
            if (response.code().equals("MATCH_RESERVED")) {
                controller.currentMatch().ifPresent(match -> {
                    sendReadinessPrompt(match);
                });
            }
            if (controller.currentMatch().isEmpty()) {
                if (previousMatch != null) {
                    if (response.code().equals("PRESTART_CANCELLED")) {
                        notifyParticipants(previousMatch.participants(), response.messagePtPt(), null);
                    }
                }
                readinessPromptMatchId = null;
            }
            reconcileQueuePlayers();
        } catch (RuntimeException failure) {
            faulted = true;
            RuntimeException recoveryFailure = recoverPlayers();
            if (recoveryFailure != null) failure.addSuppressed(recoveryFailure);
            throw failure;
        }
    }

    @Override public synchronized void shutdown() {
        RuntimeException failure = recoverPlayers();
        try { shutdownAction.run(); }
        catch (RuntimeException current) {
            if (failure == null) failure = current; else failure.addSuppressed(current);
        }
        if (failure != null) { faulted = true; throw failure; }
    }

    private RuntimeException recoverPlayers() {
        RuntimeException failure = null;
        for (UUID playerId : List.copyOf(controller.queuedPlayerIds())) {
            Player player = player(playerId);
            if (player != null) {
                try { controller.leave(player); }
                catch (RuntimeException current) {
                    if (failure == null) failure = current; else failure.addSuppressed(current);
                }
            }
        }
        queuePlayers.clear();
        try {
            controller.recoverForShutdown();
        } catch (RuntimeException current) {
            if (failure == null) failure = current; else failure.addSuppressed(current);
        }
        return failure;
    }

    /** Typed event-routing access for the later Bukkit listener layer. */
    public ColiseumController controller() { return controller; }

    /** Called through the connection-bound authentication completion recovery gate. */
    public synchronized void resumeAfterAuthentication(Player player) {
        Objects.requireNonNull(player, "player");
        ArenaPlayerResponse response = controller.resumeAfterAuthentication(player);
        boolean gameStillOwnsRestore = controller.currentMatch()
                .filter(match -> match.snapshottedPlayers().contains(player.getUniqueId()))
                .filter(match -> !match.restoredPlayers().contains(player.getUniqueId()))
                .isPresent();
        if (gameStillOwnsRestore || "AUTHENTICATION_REQUIRED".equals(response.code())) {
            throw new com.ciaac.minecraft.minigames.paper.recovery.SessionRecoveryService.DeferredGameRecovery();
        }
        reconcileQueuePlayers();
    }

    /** Moves only authenticated non-participants found on the configured combat floor. */
    public boolean relocateUnaffiliatedFloorOccupant(Player player) {
        return controller.relocateUnaffiliatedFloorOccupant(Objects.requireNonNull(player, "player"));
    }

    private static ModuleActionResult faultedResult() {
        return ModuleActionResult.rejected("MODULE_FAULTED",
                "O Coliseu está fechado enquanto a recuperação é revista.");
    }

    private ModuleActionResult group(Player player, List<String> arguments) {
        if (arguments.isEmpty()) {
            return ModuleActionResult.rejected("PARTY_ACTION_REQUIRED",
                    "Usa /coliseu grupo criar, convidar, aceitar, sair, expulsar, dissolver ou estado.");
        }
        String action = arguments.getFirst().toLowerCase(Locale.ROOT);
        if (!action.equals("estado") && queuePlayers.contains(player.getUniqueId())) {
            return ModuleActionResult.rejected("PARTY_QUEUE_LOCKED",
                    "Sai primeiro da fila do Coliseu para alterares o grupo.");
        }
        return switch (action) {
            case "criar" -> {
                parties.create(player.getUniqueId(), clock.instant());
                yield ModuleActionResult.accepted("PARTY_CREATED",
                        "Grupo criado. Podes convidar até mais dois jogadores.");
            }
            case "convidar" -> invite(player, requiredPlayer(player, arguments, 1));
            case "aceitar" -> acceptParty(player, requiredPlayer(player, arguments, 1));
            case "sair" -> {
                parties.leave(player.getUniqueId());
                yield ModuleActionResult.accepted("PARTY_LEFT", "Saíste do grupo do Coliseu.");
            }
            case "expulsar" -> {
                Player target = requiredPlayer(player, arguments, 1);
                parties.kick(player.getUniqueId(), target.getUniqueId());
                target.sendMessage(Component.text(
                        "Foste removido do grupo do Coliseu.", NamedTextColor.YELLOW));
                yield ModuleActionResult.accepted("PARTY_MEMBER_KICKED",
                        "Removeste " + target.getName() + " do grupo.");
            }
            case "dissolver" -> {
                ArenaParty party = parties.findByMember(player.getUniqueId())
                        .orElseThrow(() -> new IllegalStateException("PARTY_UNAVAILABLE"));
                parties.disband(player.getUniqueId());
                notifyMembers(player, party, "O grupo do Coliseu foi dissolvido.");
                yield ModuleActionResult.accepted("PARTY_DISBANDED", "Dissolveste o grupo do Coliseu.");
            }
            case "estado" -> partyStatus(player);
            default -> ModuleActionResult.rejected("PARTY_ACTION_UNKNOWN",
                    "Ação de grupo desconhecida. Usa /coliseu ajuda.");
        };
    }

    private ModuleActionResult invite(Player leader, Player target) {
        requireAuthenticated(target);
        if (parties.findByMember(leader.getUniqueId()).isEmpty()) {
            parties.create(leader.getUniqueId(), clock.instant());
        }
        parties.invite(leader.getUniqueId(), target.getUniqueId(), clock.instant());
        String command = "/coliseu grupo aceitar " + leader.getName();
        target.sendMessage(Component.text(leader.getName() + " convidou-te para um grupo do Coliseu. ",
                        NamedTextColor.AQUA)
                .append(Component.text("[Aceitar]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand(command))
                        .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.YELLOW)))));
        return ModuleActionResult.accepted("PARTY_INVITED",
                "Convite enviado a " + target.getName() + ".");
    }

    private ModuleActionResult acceptParty(Player target, Player leader) {
        requireAuthenticated(leader);
        ArenaParty party = parties.accept(target.getUniqueId(), leader.getUniqueId(), clock.instant());
        notifyMembers(target, party, target.getName() + " entrou no grupo do Coliseu.");
        return ModuleActionResult.accepted("PARTY_JOINED", "Entraste no grupo de " + leader.getName() + ".");
    }

    private ModuleActionResult partyStatus(Player player) {
        Optional<ArenaParty> party = parties.findByMember(player.getUniqueId());
        if (party.isEmpty()) {
            return ModuleActionResult.accepted("PARTY_SOLO",
                    "Não tens grupo; entrarás sozinho na fila.");
        }
        ArenaParty value = party.orElseThrow();
        String names = value.members().stream().map(this::playerName)
                .collect(java.util.stream.Collectors.joining(", "));
        return ModuleActionResult.accepted("PARTY_STATUS",
                "Grupo " + value.members().size() + "/3 — líder: " + playerName(value.leader())
                        + " — membros: " + names + ".");
    }

    private ModuleActionResult challenge(Player challenger, List<String> arguments) {
        if (arguments.size() < 3) {
            return ModuleActionResult.rejected("CHALLENGE_USAGE",
                    "Usa /coliseu desafiar <jogador> <formato> <kit|equipamento|aposta>.");
        }
        Player target = requiredPlayer(challenger, arguments, 0);
        if (target.getUniqueId().equals(challenger.getUniqueId())) {
            return ModuleActionResult.rejected("CHALLENGE_SELF", "Não te podes desafiar a ti próprio.");
        }
        TeamRoster challengerRoster = rosterLedBy(challenger);
        TeamRoster targetRoster = rosterLedBy(target);
        if (challengerRoster.players().stream().anyMatch(targetRoster.players()::contains)) {
            return ModuleActionResult.rejected("CHALLENGE_SAME_PARTY",
                    "Os adversários não podem pertencer ao mesmo grupo.");
        }
        ArenaMode mode = parse(arguments.get(1) + " " + arguments.get(2));
        ArenaEquipmentContract contract = validateEquipment(challengerRoster, challenger, mode.kit());
        validateEquipment(targetRoster, target, mode.kit());
        ArenaPlayerResponse response = controller.challenge(challenger, target, challengerRoster, targetRoster,
                mode.format(), contract, clock.instant().plus(challengeLifetime));
        if (response.code().equals("CHALLENGE_CREATED") && response.matchId().isPresent()) {
            Set<UUID> participants = new LinkedHashSet<>(challengerRoster.players());
            participants.addAll(targetRoster.players());
            pendingChallenges.put(target.getUniqueId(), new PendingChallenge(
                    response.matchId().orElseThrow(), challenger.getUniqueId(),
                    clock.instant().plus(challengeLifetime), mode.kit(), participants));
            String command = "/coliseu aceitar " + challenger.getName();
            target.sendMessage(Component.text(challenger.getName() + " desafiou-te no Coliseu. ",
                            NamedTextColor.AQUA)
                    .append(Component.text("[Aceitar]", NamedTextColor.GREEN)
                            .clickEvent(ClickEvent.runCommand(command))
                            .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.YELLOW)))));
        }
        return ModuleResults.arena(response);
    }

    private ModuleActionResult acceptChallenge(Player target, List<String> arguments) {
        Instant now = clock.instant();
        pendingChallenges.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt()));
        PendingChallenge pending = pendingChallenges.get(target.getUniqueId());
        if (pending == null) {
            return ModuleActionResult.rejected("CHALLENGE_UNAVAILABLE",
                    "Não tens um desafio válido para aceitar.");
        }
        if (!arguments.isEmpty()) {
            Player expected = requiredPlayer(target, arguments, 0);
            if (!expected.getUniqueId().equals(pending.challenger())) {
                return ModuleActionResult.rejected("CHALLENGE_UNAVAILABLE",
                        "Esse jogador não tem um desafio pendente para ti.");
            }
        }
        for (UUID participant : pending.participants()) {
            Player online = player(participant);
            if (online == null) throw new IllegalStateException("PARTY_MEMBER_OFFLINE");
            requireAuthenticated(online);
        }
        ArenaPlayerResponse response = controller.acceptChallenge(target, pending.challengeId(), now);
        if (response.code().equals("CHALLENGE_ACCEPTED")) {
            pendingChallenges.remove(target.getUniqueId());
            if (pending.mode() == ArenaKitMode.STAKED_SURVIVAL) {
                sendStakeConfirmation(target);
                Player challenger = player(pending.challenger());
                if (challenger != null) sendStakeConfirmation(challenger);
            } else {
                controller.currentMatch().filter(match -> match.id().equals(pending.challengeId()))
                        .ifPresent(this::sendReadinessPrompt);
            }
        }
        return ModuleResults.arena(response);
    }

    private ModuleActionResult stake(Player player, List<String> arguments) {
        if (arguments.isEmpty()) {
            return ModuleActionResult.rejected("STAKE_ACTION_REQUIRED",
                    "Usa /coliseu aposta confirmar ou /coliseu aposta reclamar.");
        }
        return switch (arguments.getFirst().toLowerCase(Locale.ROOT)) {
            case "confirmar" -> {
                ArenaPlayerResponse response = controller.consentStake(player);
                if (response.code().equals("STAKE_CONFIRMED")
                        || response.code().equals("STAKE_ALREADY_CONFIRMED")) {
                    controller.currentMatch().filter(match -> match.stakedEscrow()
                                    .filter(StakedEscrow::canAdmit).isPresent())
                            .ifPresent(this::sendReadinessPrompt);
                }
                yield ModuleResults.arena(response);
            }
            case "reclamar" -> ModuleResults.arena(controller.claimStake(player));
            default -> ModuleActionResult.rejected("STAKE_ACTION_UNKNOWN",
                    "Usa /coliseu aposta confirmar ou /coliseu aposta reclamar.");
        };
    }

    private static void sendStakeConfirmation(Player player) {
        String command = "/coliseu aposta confirmar";
        safeSend(player, Component.text("Revê a aposta apresentada no chat. ", NamedTextColor.YELLOW)
                .append(Component.text("[Confirmar aposta]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand(command))
                        .hoverEvent(HoverEvent.showText(Component.text(
                                "Aceito perder todo o equipamento apresentado", NamedTextColor.RED)))));
    }

    private void sendReadinessPrompt(ArenaMatch match) {
        if (match.kitMode() == ArenaKitMode.STAKED_SURVIVAL
                && match.stakedEscrow().filter(StakedEscrow::canAdmit).isEmpty()) return;
        if (match.id().equals(readinessPromptMatchId)) return;
        readinessPromptMatchId = match.id();
        String command = "/coliseu pronto";
        Component prompt = Component.text("Partida reservada. Confirma quando estiveres pronto: ",
                        NamedTextColor.YELLOW)
                .append(Component.text("[Confirmar prontidão]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand(command))
                        .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.AQUA))));
        for (UUID participant : match.participants()) {
            try {
                Player online = player(participant);
                if (online != null && online.isOnline() && online.isValid()) safeSend(online, prompt);
            } catch (RuntimeException ignored) {
                // A failed lookup or UI delivery must not alter the reserved match.
            }
        }
    }

    private void notifyParticipants(Set<UUID> participants, String message, UUID excludedPlayer) {
        Component notice = Component.text(message, NamedTextColor.YELLOW);
        for (UUID participant : participants) {
            if (participant.equals(excludedPlayer)) continue;
            try {
                Player online = player(participant);
                if (online != null && online.isOnline() && online.isValid()) safeSend(online, notice);
            } catch (RuntimeException ignored) {
                // Cancellation notices are informational and never gate cleanup.
            }
        }
    }

    private void reconcileQueuePlayers() {
        Set<UUID> retained = new LinkedHashSet<>(controller.queuedPlayerIds());
        controller.currentMatch().ifPresent(match -> retained.addAll(match.participants()));
        queuePlayers.retainAll(retained);
    }

    private static void safeSend(Player player, Component message) {
        try { player.sendMessage(message); }
        catch (RuntimeException ignored) {
            // UI delivery must not alter reservation, consent, or recovery state.
        }
    }

    private TeamRoster rosterLedBy(Player player) {
        requireAuthenticated(player);
        Optional<ArenaParty> party = parties.findByMember(player.getUniqueId());
        if (party.isPresent() && !party.orElseThrow().leader().equals(player.getUniqueId())) {
            throw new IllegalStateException("PARTY_LEADER_REQUIRED");
        }
        TeamRoster roster = party.map(ArenaParty::roster)
                .orElseGet(() -> TeamRoster.of(player.getUniqueId()));
        for (UUID member : roster.players()) {
            Player online = player.getServer().getPlayer(member);
            if (online == null || !online.isOnline() || !online.isValid()
                    || identity.connections().current(member).isEmpty()) {
                throw new IllegalStateException("PARTY_MEMBER_OFFLINE");
            }
            requireAuthenticated(online);
        }
        return roster;
    }

    private void requireAuthenticated(Player player) {
        if (!player.isOnline() || !player.isValid() || !identity.requests().isAuthenticated(player)) {
            throw new IllegalStateException("AUTHENTICATION_REQUIRED");
        }
    }

    private ArenaEquipmentContract validateEquipment(
            TeamRoster roster, Player leader, ArenaKitMode mode) {
        ArenaEquipmentContract selected = null;
        for (UUID member : roster.players()) {
            Player online = leader.getServer().getPlayer(member);
            if (online == null) throw new IllegalStateException("PARTY_MEMBER_OFFLINE");
            ArenaEquipmentContract candidate = equipment.contract(online, mode);
            if (selected == null || member.equals(leader.getUniqueId())) selected = candidate;
            if (mode == ArenaKitMode.FIXED && !Objects.equals(selected.fixedKitId(), candidate.fixedKitId())) {
                throw new IllegalStateException("PARTY_KIT_MISMATCH");
            }
        }
        return Objects.requireNonNull(selected, "selected equipment");
    }

    private static Player requiredPlayer(Player requester, List<String> arguments, int index) {
        if (arguments.size() <= index || arguments.get(index).isBlank()) {
            throw new IllegalArgumentException("player name required");
        }
        Player target = requester.getServer().getPlayerExact(arguments.get(index));
        if (target == null || !target.isOnline() || !target.isValid()) {
            throw new IllegalStateException("PLAYER_OFFLINE");
        }
        return target;
    }

    private void notifyMembers(Player actor, ArenaParty party, String message) {
        for (UUID member : party.members()) {
            Player online = actor.getServer().getPlayer(member);
            if (online != null && !online.getUniqueId().equals(actor.getUniqueId())) {
                online.sendMessage(Component.text(message, NamedTextColor.AQUA));
            }
        }
    }

    private String playerName(UUID playerId) {
        Player online = player(playerId);
        return online == null ? playerId.toString().substring(0, 8) : online.getName();
    }

    private static ModuleActionResult partyFailure(String code) {
        if ("AUTHENTICATION_REQUIRED".equals(code)) {
            return ModuleActionResult.rejected("AUTHENTICATION_REQUIRED",
                    "Todos os jogadores envolvidos têm de concluir a autenticação da sessão atual.");
        }
        String message = switch (code == null ? "" : code) {
            case "PLAYER_ALREADY_IN_PARTY" -> "Já pertences a um grupo do Coliseu.";
            case "PARTY_FULL" -> "O grupo já tem o máximo de três jogadores.";
            case "TARGET_ALREADY_IN_PARTY" -> "Esse jogador já pertence a outro grupo.";
            case "INVITE_UNAVAILABLE" -> "Esse convite expirou ou já não está disponível.";
            case "PARTY_LEADER_REQUIRED" -> "Só o líder do grupo pode fazer isso.";
            case "PARTY_MEMBER_OFFLINE", "PLAYER_OFFLINE" -> "Todos os jogadores envolvidos têm de estar online.";
            case "PARTY_KIT_MISMATCH" -> "Todos os membros do grupo têm de usar o mesmo kit fixo.";
            default -> "O grupo do Coliseu não está disponível nesse estado.";
        };
        return ModuleActionResult.rejected("PARTY_UNAVAILABLE", message);
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private Player player(UUID id) {
        return identity.connections().current(id)
                .map(com.ciaac.minecraft.minigames.runtime.ConnectionRegistry.Connection::player)
                .orElse(null);
    }

    private static ArenaMode parse(String raw) {
        String input = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (input.isBlank()) input = "1v1 kit";
        String[] parts = input.split("\\s+");
        String format = "1v1";
        ArenaKitMode kit = ArenaKitMode.FIXED;
        for (String part : parts) {
            if (part.matches("(?:1v1|2v2|3v3|2v3|3v2)")) {
                format = part;
            } else if (part.equals("kit")) {
                kit = ArenaKitMode.FIXED;
            } else if (part.equals("equipamento")) {
                kit = ArenaKitMode.MIRRORED_SURVIVAL;
            } else if (part.equals("aposta")) {
                kit = ArenaKitMode.STAKED_SURVIVAL;
            } else {
                throw new IllegalArgumentException("unsupported Coliseum mode");
            }
        }
        return new ArenaMode(format, kit);
    }

    private record ArenaMode(String format, ArenaKitMode kit) { }

    private record PendingChallenge(UUID challengeId, UUID challenger, Instant expiresAt,
                                    ArenaKitMode mode, Set<UUID> participants) {
        private PendingChallenge {
            Objects.requireNonNull(challengeId, "challengeId");
            Objects.requireNonNull(challenger, "challenger");
            Objects.requireNonNull(expiresAt, "expiresAt");
            Objects.requireNonNull(mode, "mode");
            participants = Set.copyOf(participants);
        }
    }
}
