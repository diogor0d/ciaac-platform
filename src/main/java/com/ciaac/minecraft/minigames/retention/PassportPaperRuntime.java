package com.ciaac.minecraft.minigames.retention;

import com.ciaac.minecraft.minigames.runtime.AuthenticatedSession;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.ConnectionRegistry;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import com.ciaac.minecraft.minigames.runtime.SessionPhase;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Authenticated Paper adapter. It stores only minute credits, never movement paths. */
public final class PassportPaperRuntime implements Listener, CommandExecutor, TabCompleter, AutoCloseable {
    private final Plugin plugin;
    private final PassportService passport;
    private final PassportCommandService presentation;
    private final AuthenticationRegistry authentication;
    private final ConnectionRegistry connections;
    private final SessionRegistry sessions;
    private final Clock clock;
    private final Map<UUID, UUID> activityCandidates = new HashMap<>();
    private final Map<UUID, java.time.LocalDate> creditedJoinDays = new HashMap<>();
    private final BukkitTask sampler;
    private final PassportPlaceholderExpansion placeholders;
    private final PassportSafezonePresentation safezonePresentation;
    private java.time.LocalDate lastPrivacyPurge;

    public PassportPaperRuntime(Plugin plugin, PassportService passport, AuthenticationRegistry authentication,
                                ConnectionRegistry connections, SessionRegistry sessions, Clock clock) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.passport = Objects.requireNonNull(passport, "passport");
        this.presentation = new PassportCommandService(passport);
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.clock = Objects.requireNonNull(clock, "clock");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        PluginCommand command = Objects.requireNonNull(plugin.getServer().getPluginCommand("passaporte"),
                "Falta declarar o comando passaporte");
        command.setExecutor(this); command.setTabCompleter(this);
        sampler = plugin.getServer().getScheduler().runTaskTimer(plugin, this::sampleCandidates, 1200L, 1200L);
        PassportPlaceholderExpansion expansion = null;
        if (passport.configuration().placeholdersEnabled()
                && plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                expansion = new PassportPlaceholderExpansion(plugin, passport, clock);
                if (!expansion.register()) throw new IllegalStateException("O registo da expansão foi recusado.");
            } catch (RuntimeException | LinkageError failure) {
                plugin.getLogger().warning("A expansão PlaceholderAPI do Passaporte ficou fechada: "
                        + failure.getClass().getSimpleName());
                expansion = null;
            }
        }
        placeholders = expansion;
        PassportSafezonePresentation presentation = null;
        try {
            SafezonePresentationConfiguration safezone = SafezonePresentationConfiguration.load(
                    new java.io.File(plugin.getDataFolder(), "retention.yml"));
            if (safezone.enabled()) presentation = new PassportSafezonePresentation(plugin, passport, safezone, clock);
        } catch (RuntimeException | LinkageError failure) {
            plugin.getLogger().warning("A apresentação do Passaporte na zona segura ficou fechada: " + failure.getMessage());
        }
        safezonePresentation = presentation;
    }

    /** Called only by the verified nLogin post-authentication adapter. */
    public void onAuthenticated(Player player) {
        if (!passport.configuration().enabled()) return;
        qualifyCurrentJoin(player, clock.instant());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() != null && (event.getFrom().getBlockX() != event.getTo().getBlockX()
                || event.getFrom().getBlockY() != event.getTo().getBlockY()
                || event.getFrom().getBlockZ() != event.getTo().getBlockZ())) mark(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteraction(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK) mark(event.getPlayer());
    }

    private void mark(Player player) {
        if (!passport.configuration().enabled()) return;
        Instant now = clock.instant();
        eligibleAuthentication(player, now).ifPresent(auth ->
                activityCandidates.put(player.getUniqueId(), auth.connectionId()));
    }

    private void sampleCandidates() {
        java.time.LocalDate today = passport.calendar().localDate(clock.instant());
        if (!today.equals(lastPrivacyPurge)) {
            passport.purgeDetailedCredits(clock.instant());
            if (passport.configuration().enabled()) passport.finalizePreviousSeason(clock.instant());
            lastPrivacyPurge = today;
        }
        if (!passport.configuration().enabled()) { activityCandidates.clear(); return; }
        Map<UUID, UUID> candidates = Map.copyOf(activityCandidates);
        activityCandidates.clear();
        Instant now = clock.instant();
        for (Player player : plugin.getServer().getOnlinePlayers()) qualifyCurrentJoin(player, now);
        creditedJoinDays.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
        for (var candidate : candidates.entrySet()) {
            UUID id = candidate.getKey();
            Player player = plugin.getServer().getPlayer(id);
            if (player == null) continue;
            AuthenticatedSession auth = eligibleAuthentication(player, now).orElse(null);
            if (auth == null || !auth.connectionId().equals(candidate.getValue())) continue;
            passport.sampleActiveMinute(id, auth.connectionId(), now);
        }
    }

    private void qualifyCurrentJoin(Player player, Instant now) {
        AuthenticatedSession auth = eligibleAuthentication(player, now).orElse(null);
        if (auth == null || now.isBefore(auth.authenticatedAt().plus(passport.configuration().joinQualification()))) return;
        java.time.LocalDate date = passport.calendar().localDate(now);
        if (date.equals(creditedJoinDays.get(player.getUniqueId()))) return;
        var result = passport.qualifyJoin(player.getUniqueId(), auth.connectionId(), auth.authenticatedAt(), now);
        if (result.outcome() == PassportService.Outcome.CREDITED
                || result.outcome() == PassportService.Outcome.ALREADY_CREDITED) {
            creditedJoinDays.put(player.getUniqueId(), date);
        }
    }

    private java.util.Optional<AuthenticatedSession> eligibleAuthentication(Player player, Instant now) {
        if (!passport.configuration().enabled() || !player.isOnline() || excluded(player)
                || sessions.findByPlayer(player.getUniqueId())
                        .filter(session -> session.phase() != SessionPhase.CLOSED).isPresent()) return java.util.Optional.empty();
        return authentication.current(player.getUniqueId(), now)
                .filter(auth -> connections.isCurrent(player, auth.connectionId()));
    }

    private boolean excluded(Player player) {
        GameMode mode = player.getGameMode();
        return player.isOp() || player.hasPermission("ciaac.retention.excluded")
                || mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Este comando só está disponível para jogadores autenticados."); return true;
        }
        Instant now = clock.instant();
        if (authentication.current(player.getUniqueId(), now)
                .filter(auth -> connections.isCurrent(player, auth.connectionId())).isEmpty()) {
            player.sendMessage("Autentica-te antes de consultares o Passaporte CIAAC."); return true;
        }
        try {
            if (args.length == 0) return send(player, presentation.overview(player.getUniqueId(), now));
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "recompensas" -> send(player, presentation.rewards(player.getUniqueId(), now));
                case "classificacao" -> leaderboard(player, args, now);
                case "reclamar" -> claim(player, args, now);
                case "personalizar" -> personalize(player, args, now);
                case "admin" -> admin(player, args);
                default -> send(player, List.of("Uso: /passaporte [recompensas|classificacao|reclamar|personalizar|admin]"));
            };
        } catch (IllegalStateException failure) {
            player.sendMessage(failure.getMessage()); return true;
        }
    }

    private boolean leaderboard(Player player, String[] args, Instant now) {
        if (!player.hasPermission("ciaac.retention.leaderboard")) return send(player, List.of("Não tens permissão para consultar classificações."));
        PassportService.LeaderboardMetric metric = args.length < 2 ? PassportService.LeaderboardMetric.PASSPORT_POINTS
                : switch (args[1].toLowerCase(Locale.ROOT)) {
                    case "presencas" -> PassportService.LeaderboardMetric.CURRENT_JOIN_STREAK;
                    case "atividade" -> PassportService.LeaderboardMetric.ACTIVE_DAYS;
                    default -> PassportService.LeaderboardMetric.PASSPORT_POINTS;
                };
        SeasonWindow season = args.length >= 3 ? passport.calendar().seasonById(args[2]) : passport.calendar().seasonAt(now);
        boolean anonymized = passport.calendar().localDate(now).isAfter(season.graceEndsOnInclusive().plusMonths(12));
        List<String> lines = new java.util.ArrayList<>(); lines.add("Classificação do Passaporte CIAAC — " + season.id() + ":");
        for (var row : passport.leaderboard(metric, season, 10)) {
            OfflinePlayer named = plugin.getServer().getOfflinePlayer(row.playerId());
            String name = anonymized || named.getName() == null ? "Jogador anónimo" : named.getName();
            lines.add(row.rank() + ". " + name + " — " + row.value());
        }
        if (lines.size() == 1) lines.add("Ainda não existem resultados nesta época.");
        return send(player, lines);
    }

    private boolean claim(Player player, String[] args, Instant now) {
        if (!player.hasPermission("ciaac.retention.claim") || args.length != 2) return send(player, List.of("Uso: /passaporte reclamar <recompensa>"));
        if (eligibleAuthentication(player, now).isEmpty()) {
            return send(player, List.of("Esta sessão não pode reclamar recompensas. Conclui qualquer minijogo, recuperação ou elevação antes de tentares novamente."));
        }
        return send(player, List.of(passport.claim(player.getUniqueId(), args[1], now).messagePtPt()));
    }

    private boolean personalize(Player player, String[] args, Instant now) {
        if (args.length != 3) return send(player, List.of(
                "Uso: /passaporte personalizar <titulo|distintivo|particulas> <recompensa|nenhum>"));
        if (eligibleAuthentication(player, now).isEmpty()) return send(player, List.of(
                "Conclui qualquer minijogo, recuperação ou elevação antes de personalizares o Passaporte."));
        if (args[1].equalsIgnoreCase("particulas") && !player.hasPermission("ciaac.retention.particles")) {
            return send(player, List.of("Não tens permissão para usar partículas do Passaporte."));
        }
        return send(player, List.of(passport.personalize(player.getUniqueId(), args[1], args[2], now)));
    }

    private boolean admin(Player player, String[] args) {
        if (!player.hasPermission("ciaac.retention.admin.view")) return send(player, List.of("Não tens permissão para consultar o estado administrativo."));
        return send(player, List.of("Passaporte: " + (passport.configuration().enabled() ? "ativo" : "desativado"),
                "Integrações externas: fechadas até validação das versões instaladas."));
    }

    private static boolean send(CommandSender sender, List<String> lines) { lines.forEach(sender::sendMessage); return true; }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new java.util.ArrayList<>(
                    List.of("recompensas", "classificacao", "reclamar", "personalizar"));
            if (sender.hasPermission("ciaac.retention.admin.view")) options.add("admin");
            return List.copyOf(options);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("classificacao")) return List.of("presencas", "atividade", "pontos");
        if (args.length == 2 && args[0].equalsIgnoreCase("personalizar")) return List.of("titulo", "distintivo", "particulas");
        return List.of();
    }

    @Override public void close() {
        sampler.cancel(); activityCandidates.clear(); creditedJoinDays.clear();
        if (placeholders != null) placeholders.unregister();
        if (safezonePresentation != null) safezonePresentation.close();
    }
}
