package com.ciaac.minecraft.minigames.command;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.module.MinigameModule;
import com.ciaac.minecraft.minigames.module.MinigameModuleRegistry;
import com.ciaac.minecraft.minigames.module.ModuleActionResult;
import com.ciaac.minecraft.minigames.module.ModuleAvailability;
import com.ciaac.minecraft.minigames.module.ModuleStatus;
import com.ciaac.minecraft.minigames.statistics.LeaderboardEntry;
import com.ciaac.minecraft.minigames.statistics.LeaderboardPreset;
import com.ciaac.minecraft.minigames.statistics.LeaderboardPresetCatalog;
import com.ciaac.minecraft.minigames.statistics.LeaderboardScope;
import com.ciaac.minecraft.minigames.statistics.StatisticsRepository;
import com.ciaac.minecraft.minigames.updater.UpdaterService;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Portuguese command UX shared by every modular game. */
public final class MinigamesCommand implements CommandExecutor, TabCompleter {
    private static final int MAX_SCOPE_CHOICES = 8;
    private final MinigameModuleRegistry modules;
    private final Optional<StatisticsRepository> statistics;
    private final Function<UUID, String> playerName;
    private final Supplier<Optional<UpdaterService.UpdaterOutcome>> updaterOutcome;
    private final CommandRateLimiter limiter;
    private BiConsumer<Player, Optional<GameKey>> menuOpener;

    public void menuOpener(BiConsumer<Player, Optional<GameKey>> opener) {
        if (menuOpener != null) throw new IllegalStateException("Menu opener already bound");
        menuOpener = Objects.requireNonNull(opener, "opener");
    }

    public MinigamesCommand(
            MinigameModuleRegistry modules,
            Optional<StatisticsRepository> statistics,
            Function<UUID, String> playerName,
            Clock clock) {
        this(modules, statistics, playerName, clock, Optional::empty);
    }

    public MinigamesCommand(
            MinigameModuleRegistry modules,
            Optional<StatisticsRepository> statistics,
            Function<UUID, String> playerName,
            Clock clock,
            Supplier<Optional<UpdaterService.UpdaterOutcome>> updaterOutcome) {
        this.modules = Objects.requireNonNull(modules, "modules");
        this.statistics = Objects.requireNonNull(statistics, "statistics");
        this.playerName = Objects.requireNonNull(playerName, "playerName");
        this.updaterOutcome = Objects.requireNonNull(updaterOutcome, "updaterOutcome");
        this.limiter = new CommandRateLimiter(Objects.requireNonNull(clock, "clock"), Duration.ofMillis(750));
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args) {
        if (command.getName().equalsIgnoreCase("minijogos")) return handleRoot(sender, args);
        Optional<GameKey> game = GameCommandRoutes.game(command.getName());
        if (game.isEmpty()) return false;
        return handleGame(sender, modules.get(game.orElseThrow()), args);
    }

    private boolean handleRoot(CommandSender sender, String[] args) {
        if (openMenu(sender, args, Optional.empty())) return true;
        String action = args.length == 0 ? "estado" : args[0].toLowerCase(Locale.ROOT);
        return switch (action) {
            case "estado" -> { showCatalog(sender); yield true; }
            case "ajuda" -> { showHelp(sender); yield true; }
            case "top" -> { showLeaderboard(sender, args); yield true; }
            case "estatisticas" -> { showPosition(sender, args); yield true; }
            case "atualizacao" -> { showUpdater(sender); yield true; }
            default -> {
                sendError(sender, "Usa /minijogos ajuda para veres os comandos disponíveis.");
                yield true;
            }
        };
    }

    private boolean handleGame(CommandSender sender, MinigameModule module, String[] args) {
        if (openMenu(sender, args, Optional.of(module.key()))) return true;
        String action = args.length == 0 ? "estado" : args[0].toLowerCase(Locale.ROOT);
        if (action.equals("estado")) {
            showStatus(sender, module.status());
            return true;
        }
        if (action.equals("ajuda")) {
            showGameHelp(sender, module.key());
            return true;
        }
        if (!(sender instanceof Player player)) {
            sendError(sender, "Este comando só pode ser usado por um jogador dentro do servidor.");
            return true;
        }
        performGameAction(player, module.key(), action, args.length <= 1
                ? List.of() : List.copyOf(Arrays.asList(args).subList(1, args.length)));
        return true;
    }

    private boolean openMenu(CommandSender sender, String[] args, Optional<GameKey> game) {
        if (menuOpener == null || !(sender instanceof Player player)
                || !(args.length == 0 || args.length == 1 && args[0].equalsIgnoreCase("menu"))) return false;
        menuOpener.accept(player, game);
        return true;
    }

    /** The menu and command paths share authorization, throttling and module actions. */
    public ModuleActionResult performGameAction(Player player, GameKey game, String action, List<String> arguments) {
        MinigameModule module = modules.get(game);
        if (!player.hasPermission("ciaac.minigames.use") || !player.hasPermission(GameCommandRoutes.permission(game))) {
            ModuleActionResult denied = ModuleActionResult.rejected("PERMISSION_DENIED", "Não tens permissão para este minijogo.");
            sendResult(player, denied);
            return denied;
        }
        if (!limiter.allow(player.getUniqueId(), module.key().id() + ":" + action)) {
            sendError(player, "Espera um instante antes de repetires esse pedido.");
            return ModuleActionResult.rejected("RATE_LIMITED", "Espera um instante antes de repetires esse pedido.");
        }
        ModuleActionResult result = switch (action) {
            case "entrar" -> module.join(player, arguments);
            case "sair" -> module.leave(player);
            case "pronto" -> module.ready(player);
            default -> module.action(player, action, arguments);
        };
        sendResult(player, result);
        return result;
    }

    private void showCatalog(CommandSender sender) {
        sender.sendMessage(Component.text("Minijogos CIAAC", NamedTextColor.GOLD)
                .append(Component.text("  •  escolhe um jogo", NamedTextColor.GRAY)));
        for (MinigameModule module : modules.all()) {
            ModuleStatus status = module.status();
            Component line = Component.text("• ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(status.game().portugueseName(), NamedTextColor.AQUA))
                    .append(Component.text(" — ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(statusLabel(status.availability()), statusColor(status.availability())))
                    .append(Component.text(" — " + status.messagePtPt(), NamedTextColor.GRAY));
            if (status.joinable()) {
                String join = GameCommandRoutes.joinCommand(status.game());
                line = line.clickEvent(ClickEvent.runCommand(join))
                        .hoverEvent(HoverEvent.showText(Component.text("Clica para entrar", NamedTextColor.GREEN)));
            }
            sender.sendMessage(line);
        }
        sender.sendMessage(Component.text("Classificações: ", NamedTextColor.GRAY)
                .append(Component.text("/minijogos top <jogo> [regras modo]", NamedTextColor.YELLOW)));
    }

    private void showStatus(CommandSender sender, ModuleStatus status) {
        sender.sendMessage(Component.text(status.game().portugueseName(), NamedTextColor.GOLD)
                .append(Component.text("  •  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(statusLabel(status.availability()), statusColor(status.availability()))));
        sender.sendMessage(Component.text(status.messagePtPt(), NamedTextColor.GRAY));
        String capacity = status.capacity().isPresent()
                ? status.participants() + "/" + status.capacity().getAsInt()
                : Integer.toString(status.participants());
        sender.sendMessage(Component.text("Jogadores: " + capacity, NamedTextColor.AQUA));
        if (status.joinable()) {
            String join = GameCommandRoutes.joinCommand(status.game());
            sender.sendMessage(Component.text("[Entrar agora]", NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.runCommand(join))
                    .hoverEvent(HoverEvent.showText(Component.text(join, NamedTextColor.YELLOW))));
        }
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage(Component.text("Ajuda dos Minijogos", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("/minijogos", NamedTextColor.YELLOW)
                .append(Component.text(" — abre o menu; cada comando de jogo também abre o respetivo menu", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/minijogos estado", NamedTextColor.YELLOW)
                .append(Component.text(" — estado de todos os jogos", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/minijogos top <jogo> [regras modo]", NamedTextColor.YELLOW)
                .append(Component.text(" — classificação pública sem misturar âmbitos", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/minijogos estatisticas <jogo> [regras modo]", NamedTextColor.YELLOW)
                .append(Component.text(" — a tua posição", NamedTextColor.GRAY)));
        if (sender.hasPermission("ciaac.minigames.admin")) {
            sender.sendMessage(Component.text("/minijogos atualizacao", NamedTextColor.YELLOW)
                    .append(Component.text(" — estado do atualizador assinado", NamedTextColor.GRAY)));
        }
        sender.sendMessage(Component.text("Em cada jogo: estado, entrar, sair, pronto e ajuda.", NamedTextColor.GRAY));
    }

    private void showUpdater(CommandSender sender) {
        if (!sender.hasPermission("ciaac.minigames.admin")) {
            sendError(sender, "Não tens permissão para consultar o atualizador.");
            return;
        }
        Optional<UpdaterService.UpdaterOutcome> outcome;
        try {
            outcome = updaterOutcome.get();
        } catch (RuntimeException failure) {
            outcome = Optional.empty();
        }
        if (outcome.isEmpty()) {
            sendError(sender, "O estado do atualizador não está disponível.");
            return;
        }
        UpdaterService.UpdaterOutcome value = outcome.orElseThrow();
        sender.sendMessage(Component.text("Atualizador assinado", NamedTextColor.GOLD)
                .append(Component.text("  •  " + updaterStatusPtPt(value.status()), NamedTextColor.AQUA)));
        sender.sendMessage(Component.text(value.diagnosticPtPt(), NamedTextColor.GRAY));
        value.version().ifPresent(version -> sender.sendMessage(
                Component.text("Versão publicada: " + version, NamedTextColor.YELLOW)));
    }

    private static String updaterStatusPtPt(UpdaterService.Status status) {
        return switch (status) {
            case DISABLED -> "desativado";
            case CHECKING -> "a verificar";
            case NO_UPDATE -> "sem atualização";
            case STAGED -> "atualização preparada";
            case WAITING_FOR_EMPTY_SERVER -> "à espera do servidor vazio";
            case RESTART_REQUESTED -> "reinício pedido";
            case RESTART_BLOCKED -> "reinício bloqueado";
            case RESTART_CONSUMED -> "atualização aplicada";
            case FAILED -> "falhou em segurança";
            case CLOSED -> "encerrado";
        };
    }

    private void showGameHelp(CommandSender sender, GameKey game) {
        String root = "/" + GameCommandRoutes.command(game);
        sender.sendMessage(Component.text(game.portugueseName(), NamedTextColor.GOLD));
        sender.sendMessage(Component.text(root + " estado | entrar | sair | pronto", NamedTextColor.YELLOW));
        if (game == GameKey.ARENA) {
            sender.sendMessage(Component.text(
                    root + " entrar <1v1|2v2|3v3|2v3|3v2> <kit|equipamento|aposta>",
                    NamedTextColor.GRAY));
            sender.sendMessage(Component.text(
                    root + " grupo <criar|convidar|aceitar|sair|expulsar|dissolver|estado>",
                    NamedTextColor.GRAY));
            sender.sendMessage(Component.text(
                    root + " desafiar <jogador> <formato> <kit|equipamento|aposta> | aceitar [jogador]",
                    NamedTextColor.GRAY));
            sender.sendMessage(Component.text(
                    root + " aposta <confirmar|reclamar> — aposta todo o equipamento apresentado",
                    NamedTextColor.GRAY));
            sender.sendMessage(Component.text(
                    "As apostas são apenas 1v1, exigem confirmação de ambos e rejeitam o inventário inteiro se contiver itens proibidos.",
                    NamedTextColor.RED));
            sender.sendMessage(Component.text(
                    "No modo de equipamento protegido, itens proibidos ficam apenas fora da cópia de combate e regressam com o estado survival.",
                    NamedTextColor.GRAY));
        }
        if (game == GameKey.BUILD_BATTLE) {
            sender.sendMessage(Component.text(root + " tema <tema>", NamedTextColor.GRAY));
            sender.sendMessage(Component.text(root + " ver [seguinte]", NamedTextColor.GRAY));
            sender.sendMessage(Component.text(root + " avaliar <parcela> <pontuação>", NamedTextColor.GRAY));
        }
    }

    private void showLeaderboard(CommandSender sender, String[] args) {
        Optional<GameKey> game = args.length >= 2 ? parseGame(args[1]) : Optional.empty();
        if (game.isEmpty()) {
            sendError(sender, "Indica o jogo, por exemplo: /minijogos top arena");
            return;
        }
        if (statistics.isEmpty()) {
            sendError(sender, "As classificações estão indisponíveis enquanto a persistência não estiver pronta.");
            return;
        }
        LeaderboardPreset preset = LeaderboardPresetCatalog.get(game.orElseThrow());
        Optional<LeaderboardScope> scope = selectScope(sender, args, game.orElseThrow(), preset, "top");
        if (scope.isEmpty()) return;
        try {
            LeaderboardScope selected = scope.orElseThrow();
            List<LeaderboardEntry> entries = statistics.orElseThrow().leaderboard(preset.query(selected, 10));
            sender.sendMessage(Component.text(
                    "Top — " + game.orElseThrow().portugueseName() + " — " + preset.labelPtPt()
                            + " — " + selected.ruleset() + "/" + selected.mode(),
                    NamedTextColor.GOLD));
            if (entries.isEmpty()) {
                sender.sendMessage(Component.text("Ainda não há resultados classificados para este âmbito.", NamedTextColor.GRAY));
                return;
            }
            for (LeaderboardEntry entry : entries) {
                sender.sendMessage(Component.text(
                        entry.rank() + ". " + safeName(entry.playerId()) + " — "
                                + entry.value().toPlainString() + " (" + entry.sampleCount() + ")",
                        entry.rank() <= 3 ? NamedTextColor.AQUA : NamedTextColor.GRAY));
            }
        } catch (RuntimeException failure) {
            sendError(sender, "Não foi possível consultar a classificação neste momento.");
        }
    }

    private void showPosition(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sendError(sender, "A consulta da posição pessoal exige um jogador dentro do servidor.");
            return;
        }
        Optional<GameKey> game = args.length >= 2 ? parseGame(args[1]) : Optional.empty();
        if (game.isEmpty() || statistics.isEmpty()) {
            sendError(sender, "Usa /minijogos estatisticas <jogo>; a persistência tem de estar disponível.");
            return;
        }
        LeaderboardPreset preset = LeaderboardPresetCatalog.get(game.orElseThrow());
        Optional<LeaderboardScope> scope = selectScope(sender, args, game.orElseThrow(), preset, "estatisticas");
        if (scope.isEmpty()) return;
        try {
            LeaderboardScope selected = scope.orElseThrow();
            Optional<LeaderboardEntry> entry = statistics.orElseThrow()
                    .position(preset.query(selected, 1_000), player.getUniqueId());
            if (entry.isEmpty()) {
                sender.sendMessage(Component.text("Ainda não tens resultados classificados neste âmbito.", NamedTextColor.GRAY));
                return;
            }
            LeaderboardEntry value = entry.orElseThrow();
            sender.sendMessage(Component.text(
                    game.orElseThrow().portugueseName() + ": posição " + value.rank()
                            + " — " + value.value().toPlainString() + " — "
                            + value.sampleCount() + " resultado(s) — "
                            + selected.ruleset() + "/" + selected.mode(), NamedTextColor.AQUA));
        } catch (RuntimeException failure) {
            sendError(sender, "Não foi possível consultar as tuas estatísticas neste momento.");
        }
    }

    private Optional<GameKey> parseGame(String value) {
        return GameKey.fromId(value).or(() -> GameCommandRoutes.game(value));
    }

    private Optional<LeaderboardScope> selectScope(
            CommandSender sender,
            String[] args,
            GameKey game,
            LeaderboardPreset preset,
            String action) {
        if (args.length != 2 && args.length != 4) {
            sendError(sender, "Usa /minijogos " + action + " " + game.id() + " [regras modo].");
            return Optional.empty();
        }
        List<LeaderboardScope> scopes;
        try {
            scopes = statistics.orElseThrow().discoverScopes(game, preset.metric());
        } catch (RuntimeException failure) {
            sendError(sender, "Não foi possível descobrir os âmbitos desta classificação.");
            return Optional.empty();
        }
        if (args.length == 4) {
            final LeaderboardScope requested;
            try {
                // The scope constructor is the existing bounded identifier contract.
                requested = new LeaderboardScope(game, args[2], args[3], preset.metric());
            } catch (IllegalArgumentException invalid) {
                sendError(sender, "A versão de regras e o modo têm de ser identificadores válidos.");
                return Optional.empty();
            }
            if (!scopes.contains(requested)) {
                sender.sendMessage(Component.text("Ainda não há resultados classificados para "
                        + requested.ruleset() + "/" + requested.mode() + ".", NamedTextColor.GRAY));
                return Optional.empty();
            }
            return Optional.of(requested);
        }
        if (scopes.isEmpty()) {
            sender.sendMessage(Component.text("Ainda não há resultados classificados neste jogo.", NamedTextColor.GRAY));
            return Optional.empty();
        }
        if (scopes.size() > 1) {
            sender.sendMessage(Component.text(
                    "Existem vários âmbitos; escolhe a versão de regras e o modo para não misturar classificações:",
                    NamedTextColor.YELLOW));
            int shown = Math.min(MAX_SCOPE_CHOICES, scopes.size());
            for (int index = 0; index < shown; index++) {
                LeaderboardScope scope = scopes.get(index);
                sender.sendMessage(Component.text("/minijogos " + action + " " + game.id() + " "
                        + scope.ruleset() + " " + scope.mode(), NamedTextColor.AQUA));
            }
            if (scopes.size() > shown) {
                sender.sendMessage(Component.text("… e mais " + (scopes.size() - shown)
                        + " âmbitos; usa um identificador exato.", NamedTextColor.GRAY));
            }
            return Optional.empty();
        }
        return Optional.of(scopes.getFirst());
    }

    private String safeName(UUID playerId) {
        String resolved = playerName.apply(playerId);
        return resolved == null || resolved.isBlank() ? playerId.toString().substring(0, 8) : resolved;
    }

    private static void sendResult(CommandSender sender, ModuleActionResult result) {
        sender.sendMessage(Component.text(
                result.messagePtPt(), result.accepted() ? NamedTextColor.GREEN : NamedTextColor.RED));
    }

    private static void sendError(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.RED));
    }

    private static String statusLabel(ModuleAvailability availability) {
        return switch (availability) {
            case CLOSED -> "FECHADO";
            case WAITING -> "À ESPERA";
            case STARTING -> "A COMEÇAR";
            case RUNNING -> "EM JOGO";
            case VOTING -> "EM VOTAÇÃO";
            case FINISHING -> "A TERMINAR";
            case RECOVERY -> "EM RECUPERAÇÃO";
        };
    }

    private static NamedTextColor statusColor(ModuleAvailability availability) {
        return switch (availability) {
            case WAITING -> NamedTextColor.GREEN;
            case STARTING, VOTING -> NamedTextColor.YELLOW;
            case RUNNING -> NamedTextColor.AQUA;
            case FINISHING -> NamedTextColor.GOLD;
            case CLOSED, RECOVERY -> NamedTextColor.RED;
        };
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args) {
        List<String> options;
        if (command.getName().equalsIgnoreCase("minijogos")) {
            if (args.length == 1) {
                options = sender.hasPermission("ciaac.minigames.admin")
                        ? List.of("menu", "estado", "ajuda", "top", "estatisticas", "atualizacao")
                        : List.of("menu", "estado", "ajuda", "top", "estatisticas");
            }
            else if (args.length == 2 && (args[0].equalsIgnoreCase("top")
                    || args[0].equalsIgnoreCase("estatisticas"))) {
                options = Arrays.stream(GameKey.values()).map(GameKey::id).toList();
            } else if (args.length == 3 && (args[0].equalsIgnoreCase("top")
                    || args[0].equalsIgnoreCase("estatisticas"))) {
                options = leaderboardScopeCompletions(args[1], Optional.empty());
            } else if (args.length == 4 && (args[0].equalsIgnoreCase("top")
                    || args[0].equalsIgnoreCase("estatisticas"))) {
                options = leaderboardScopeCompletions(args[1], Optional.of(args[2]));
            } else options = List.of();
        } else if (args.length == 1) {
            Optional<GameKey> game = GameCommandRoutes.game(command.getName());
            List<String> base = new ArrayList<>(List.of("menu", "estado", "entrar", "sair", "pronto", "ajuda"));
            game.ifPresent(value -> {
                if (value == GameKey.ARENA) base.addAll(List.of("grupo", "desafiar", "aceitar", "aposta"));
                if (value == GameKey.BUILD_BATTLE) base.addAll(List.of("tema", "ver", "avaliar", "votar"));
            });
            options = List.copyOf(base);
        } else {
            Optional<GameKey> game = GameCommandRoutes.game(command.getName());
            String action = args[0].toLowerCase(Locale.ROOT);
            List<String> actionArguments = args.length <= 1 ? List.of()
                    : List.copyOf(Arrays.asList(args).subList(1, args.length));
            options = game.map(value -> modules.get(value).actionCompletions(action, actionArguments))
                    .orElse(List.of());
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) matches.add(option);
        }
        return matches;
    }

    private List<String> leaderboardScopeCompletions(
            String gameValue, Optional<String> selectedRuleset) {
        if (statistics.isEmpty()) return List.of();
        Optional<GameKey> game = parseGame(gameValue);
        if (game.isEmpty()) return List.of();
        LeaderboardPreset preset = LeaderboardPresetCatalog.get(game.orElseThrow());
        try {
            List<LeaderboardScope> scopes = statistics.orElseThrow().discoverScopes(
                    game.orElseThrow(), preset.metric());
            return scopes.stream()
                    .filter(scope -> selectedRuleset
                            .map(value -> value.equals(scope.ruleset()))
                            .orElse(true))
                    .map(scope -> selectedRuleset.isEmpty() ? scope.ruleset() : scope.mode())
                    .distinct()
                    .toList();
        } catch (RuntimeException failure) {
            return List.of();
        }
    }
}
