package com.ciaac.minecraft.minigames.paper;

import com.ciaac.minecraft.minigames.arena.ArenaPhase;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattlePhase;
import com.ciaac.minecraft.minigames.command.CommandRateLimiter;
import com.ciaac.minecraft.minigames.command.GameCommandRoutes;
import com.ciaac.minecraft.minigames.command.MinigamesCommand;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.module.MinigameModuleRegistry;
import com.ciaac.minecraft.minigames.module.ModuleAvailability;
import com.ciaac.minecraft.minigames.paper.module.BuildBattleModule;
import com.ciaac.minecraft.minigames.paper.module.ArcheryModule;
import com.ciaac.minecraft.minigames.paper.module.ColiseumModule;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.ConnectionRegistry;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import com.ciaac.minecraft.platform.CiaacPlatformPlugin;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Native chest navigation. Icons are never equipment, and every action is connection-bound. */
public final class MinigameMenus implements Listener, AutoCloseable {
    private enum Page { CATALOG, GAME, PLAYERS, PARTY, CONFIRM, ADMIN }
    private record Selection(String format, String equipment) {}
    private record Target(UUID playerId, UUID connectionId, String name) {}
    private record Request(Page page, Optional<GameKey> game, String action, List<String> arguments,
                           int offset, Optional<Target> target) {
        Request { arguments = List.copyOf(arguments); }
    }
    private final class Menu implements MinigameInventoryHolder {
        final Player player;
        final Request request;
        final MinigameMenuGuard guard;
        final Inventory inventory;
        final Map<Integer, Runnable> actions = new HashMap<>();
        boolean consumed;
        Menu(Player player, Request request, MinigameMenuGuard guard, String title) {
            this.player = player; this.request = request; this.guard = guard;
            inventory = plugin.getServer().createInventory(this, 54, Component.text(title));
        }
        @Override public Inventory getInventory() { return inventory; }
    }
    private final CiaacPlatformPlugin plugin;
    private final MinigameModuleRegistry modules;
    private final AuthenticationRegistry authentication;
    private final ConnectionRegistry connections;
    private final SessionRegistry sessions;
    private final Clock clock;
    private final MinigamesCommand commands;
    private final CommandRateLimiter navigation;
    private final Map<UUID, Menu> open = new HashMap<>();
    private final Map<UUID, Selection> selections = new HashMap<>();
    private final Map<UUID, String> lanes = new HashMap<>();
    private final Map<UUID, String> buildPrompts = new HashMap<>();
    private final List<String> equipment;
    private boolean closed;

    public MinigameMenus(CiaacPlatformPlugin plugin, MinigameModuleRegistry modules,
            AuthenticationRegistry authentication, ConnectionRegistry connections,
            SessionRegistry sessions, Clock clock, MinigamesCommand commands) {
        this.plugin = Objects.requireNonNull(plugin); this.modules = Objects.requireNonNull(modules);
        this.authentication = Objects.requireNonNull(authentication); this.connections = Objects.requireNonNull(connections);
        this.sessions = Objects.requireNonNull(sessions); this.clock = Objects.requireNonNull(clock);
        this.commands = Objects.requireNonNull(commands);
        navigation = new CommandRateLimiter(clock, Duration.ofMillis(150));
        equipment = plugin.getConfig().getStringList("modules.arena.kit-modes").stream()
                .map(value -> switch (value) { case "fixed" -> "kit"; case "mirrored-survival" -> "equipamento";
                    case "staked-survival" -> "aposta"; default -> ""; })
                .filter(value -> !value.equals("aposta") || plugin.getConfig().getBoolean("modules.arena.staked-survival.enabled"))
                .filter(value -> !value.isEmpty()).distinct().toList();
    }

    public void open(Player player, Optional<GameKey> game) {
        if (!plugin.getServer().isPrimaryThread() || !navigation.allow(player.getUniqueId(), "menu")) return;
        show(player, request(game.isPresent() ? Page.GAME : Page.CATALOG, game));
    }

    private static Request request(Page page, Optional<GameKey> game) {
        return new Request(page, game, "", List.of(), 0, Optional.empty());
    }

    private Optional<MinigameMenuGuard> guard(Player player, Optional<GameKey> game) {
        if (closed || !plugin.isEnabled() || !player.isOnline() || !player.isValid() || player.isDead()
                || !player.hasPermission("ciaac.minigames.use")) return Optional.empty();
        if (game.isPresent() && !player.hasPermission(GameCommandRoutes.permission(game.orElseThrow()))) return Optional.empty();
        var auth = authentication.current(player.getUniqueId(), clock.instant())
                .filter(value -> connections.isCurrent(player, value.connectionId()));
        if (auth.isEmpty()) return Optional.empty();
        var session = sessions.findByPlayer(player.getUniqueId());
        if (session.isPresent() && !MinigameMenuGuard.allowsPhase(session.orElseThrow().phase(),
                modules.get(session.orElseThrow().game()).status().availability())) return Optional.empty();
        if (game.isPresent() && session.isPresent() && session.orElseThrow().game() != game.orElseThrow()) return Optional.empty();
        String state = player.getWorld().getUID() + ":" + session.map(value -> value.sessionId() + ":" + value.phase()).orElse("idle");
        String match = game.map(value -> modules.get(value).currentMatchId().map(UUID::toString).orElse("none")
                + ":" + modules.get(value).status().availability()).orElse("catalog");
        return Optional.of(new MinigameMenuGuard(player.getUniqueId(), auth.orElseThrow().connectionId(),
                auth.orElseThrow().capabilityId(), state, match));
    }

    private boolean cursorEmpty(Player player) {
        ItemStack cursor = player.getItemOnCursor();
        return cursor == null || cursor.getType().isAir();
    }

    private void show(Player player, Request request) {
        Optional<MinigameMenuGuard> binding = guard(player, request.game());
        if (binding.isEmpty() || !cursorEmpty(player)) {
            player.sendMessage(Component.text("Conclui a autenticação/recuperação e guarda o item do cursor antes de abrir o menu.", NamedTextColor.RED));
            return;
        }
        if (request.page() == Page.ADMIN && !player.hasPermission("ciaac.minigames.admin")) return;
        if ((request.page() == Page.PARTY || request.page() == Page.PLAYERS)
                && sessions.findByPlayer(player.getUniqueId()).isPresent()) return;
        String title = request.page() == Page.CATALOG ? "Minijogos CIAAC"
                : "CIAAC • " + (request.page() == Page.ADMIN ? "Administração"
                : request.game().map(GameKey::portugueseName).orElse("Minijogos"));
        Menu menu = new Menu(player, request, binding.orElseThrow(), title);
        switch (request.page()) {
            case CATALOG -> catalog(menu);
            case GAME -> game(menu);
            case PLAYERS -> players(menu);
            case PARTY -> party(menu);
            case CONFIRM -> confirmation(menu);
            case ADMIN -> admin(menu);
        }
        button(menu, 45, Material.ARROW, "Voltar", () -> show(player, request(Page.CATALOG, Optional.empty())));
        button(menu, 49, Material.BARRIER, "Fechar", player::closeInventory);
        button(menu, 53, Material.CLOCK, "Atualizar", () -> show(player, request));
        player.openInventory(menu.inventory);
        if (player.getOpenInventory().getTopInventory() == menu.inventory) open.put(player.getUniqueId(), menu);
    }

    private void catalog(Menu menu) {
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 21, 23};
        int index = 0;
        for (GameKey key : GameKey.values()) {
            var status = modules.get(key).status();
            boolean allowed = guard(menu.player, Optional.of(key)).isPresent();
            button(menu, slots[index++], allowed ? icon(key) : Material.BARRIER, key.portugueseName(),
                    allowed ? () -> show(menu.player, request(Page.GAME, Optional.of(key))) : null,
                    status.messagePtPt(), "Jogadores: " + status.participants(), allowed ? "Clica para escolher" : "Sem permissão");
        }
        if (menu.player.hasPermission("ciaac.minigames.admin"))
            button(menu, 47, Material.COMPARATOR, "Administração", () -> show(menu.player, request(Page.ADMIN, Optional.empty())));
    }

    private void game(Menu menu) {
        GameKey key = menu.request.game().orElseThrow();
        var module = modules.get(key);
        var status = module.status();
        button(menu, 4, icon(key), key.portugueseName(), null, status.messagePtPt(), "Jogadores: " + status.participants());
        List<String> arguments = List.of();
        if (key == GameKey.ARENA && module instanceof ColiseumModule arena) {
            List<String> formats = arena.controller().menuFormats();
            Selection selection = selections.computeIfAbsent(menu.player.getUniqueId(), ignored ->
                    new Selection(formats.isEmpty() ? "1v1" : formats.getFirst(), equipment.isEmpty() ? "kit" : equipment.getFirst()));
            var match = arena.controller().currentMatch().filter(value -> value.participants().contains(menu.player.getUniqueId()));
            boolean locked = sessions.findByPlayer(menu.player.getUniqueId()).isPresent() || match.isPresent() || arena.controller().queuedPlayerIds().contains(menu.player.getUniqueId());
            if (match.isPresent()) selection = new Selection(match.orElseThrow().format().toString(), switch (match.orElseThrow().kitMode()) {
                case FIXED -> "kit";
                case MIRRORED_SURVIVAL -> "equipamento";
                case STAKED_SURVIVAL -> "aposta";
            });
            Selection selected = selection;
            button(menu, 10, Material.IRON_SWORD, "Formato: " + selected.format(), locked ? null : () -> {
                selections.put(menu.player.getUniqueId(), new Selection(next(formats, selected.format()), selected.equipment()));
                show(menu.player, menu.request);
            }, locked ? "Sai da fila para mudar as opções" : "Clica para mudar o formato");
            button(menu, 12, Material.CHEST, "Equipamento: " + selected.equipment(), locked ? null : () -> {
                selections.put(menu.player.getUniqueId(), new Selection(selected.format(), next(equipment, selected.equipment())));
                show(menu.player, menu.request);
            }, "kit: kit fixo do servidor", "equipamento: cópia protegida", "aposta: equipamento em risco", "Só modos configurados");
            arguments = List.of(selected.format(), selected.equipment());
            List<String> chosen = arguments;
            button(menu, 16, Material.PLAYER_HEAD, "Desafiar jogador", locked ? null : () -> choosePlayer(menu, "desafiar", chosen));
            button(menu, 25, Material.EMERALD, "Aceitar desafio", locked ? null : () -> choosePlayer(menu, "aceitar", List.of()));
            button(menu, 34, Material.LEAD, "Grupo / equipa", locked ? null : () -> show(menu.player, request(Page.PARTY, Optional.of(key))));
            if (match.isPresent() && match.orElseThrow().phase() == ArenaPhase.RESERVED_READY) {
                boolean ready = match.orElseThrow().readyPlayers().contains(menu.player.getUniqueId());
                button(menu, 30, Material.LIME_CONCRETE, ready ? "Já estás pronto" : "Pronto", ready ? null : () -> perform(menu, "pronto", List.of()),
                        "Confirma esta partida", "Ambos têm de confirmar", "Teleporte e kit só após confirmação");
                if (match.orElseThrow().stakedEscrow().isPresent())
                    button(menu, 31, Material.RED_CONCRETE, "Confirmar aposta", () -> confirm(menu, "aposta", List.of("confirmar")),
                            "Todo o equipamento apresentado fica em risco", "Vitória transfere; NO_CONTEST reembolsa", "Confirma só após rever o manifesto no chat");
            }
        }
        if (module instanceof ArcheryModule archery) {
            List<String> choices = new ArrayList<>(List.of("auto"));
            choices.addAll(archery.joinCompletions(List.of()));
            String lane = lanes.getOrDefault(menu.player.getUniqueId(), "auto");
            button(menu, 10, Material.BOW, "Lane: " + lane,
                    sessions.findByPlayer(menu.player.getUniqueId()).isPresent() ? null : () -> {
                        lanes.put(menu.player.getUniqueId(), next(choices, lane));
                        show(menu.player, menu.request);
                    }, "auto: primeira lane livre", "Clica para escolher uma lane configurada");
            arguments = lane.equals("auto") ? List.of() : List.of(lane);
        }
        List<String> chosen = arguments;
        if (status.joinable() && sessions.findByPlayer(menu.player.getUniqueId()).isEmpty()) button(menu, 20, Material.LIME_CONCRETE, "Entrar", () -> {
            if (chosen.contains("aposta")) confirm(menu, "entrar", chosen);
            else perform(menu, "entrar", chosen);
        }, "Entrar na fila / instalação", String.join(" ", chosen));
        button(menu, 24, Material.RED_CONCRETE, "Sair", () -> confirm(menu, "sair", List.of()),
                "Sair durante uma partida pode contar como derrota", "O estado original é recuperado pelo jogo");
        button(menu, 38, Material.GOLD_INGOT, "Classificação", () -> rootCommand(menu, "top", key.id()));
        button(menu, 42, Material.PAPER, "As minhas estatísticas", () -> rootCommand(menu, "estatisticas", key.id()));
        if (module instanceof BuildBattleModule build) {
            int slot = 10;
            if (build.controller().status().phase() == BuildBattlePhase.THEME_VOTING) {
                for (var theme : build.controller().themeOptions()) {
                    if (slot >= 19) break;
                    String id = theme.id();
                    button(menu, slot++, Material.OAK_SIGN, "Tema: " + theme.displayName(), () -> perform(menu, "tema", List.of(id)));
                }
            }
            if (build.controller().status().phase() == BuildBattlePhase.VOTING) {
                var plot = build.controller().currentReviewPlot(menu.player.getUniqueId());
                if (plot != null) for (String score : build.controller().voteScoreOptions()) {
                    if (slot >= 19) break;
                    button(menu, slot++, Material.EMERALD, "Avaliar " + plot.id() + ": " + score,
                            () -> perform(menu, "avaliar", List.of(plot.id(), score)));
                }
            }
        }
    }

    private void choosePlayer(Menu menu, String action, List<String> arguments) {
        show(menu.player, new Request(Page.PLAYERS, menu.request.game(), action, arguments, 0, Optional.empty()));
    }

    private boolean arenaQueued(UUID playerId) {
        return modules.get(GameKey.ARENA) instanceof ColiseumModule arena
                && arena.controller().queuedPlayerIds().contains(playerId);
    }

    private void players(Menu menu) {
        List<? extends Player> players = plugin.getServer().getOnlinePlayers().stream()
                .filter(player -> player != menu.player && menu.player.canSee(player))
                .filter(player -> guard(player, Optional.of(GameKey.ARENA)).isPresent() && sessions.findByPlayer(player.getUniqueId()).isEmpty() && !arenaQueued(player.getUniqueId()))
                .sorted(Comparator.comparing(Player::getName)).toList();
        int offset = Math.min(menu.request.offset(), Math.max(0, players.size() - 1));
        int end = Math.min(players.size(), offset + 28);
        for (int i = offset; i < end; i++) {
            Player target = players.get(i);
            var binding = guard(target, Optional.of(GameKey.ARENA)).orElseThrow();
            Target selected = new Target(target.getUniqueId(), binding.connectionId(), target.getName());
            button(menu, 9 + i - offset, Material.PLAYER_HEAD, target.getName(), () -> {
                List<String> args = new ArrayList<>();
                String action = menu.request.action();
                if (action.startsWith("grupo:")) {
                    args.add(action.substring("grupo:".length())); args.add(selected.name()); action = "grupo";
                } else { args.add(selected.name()); args.addAll(menu.request.arguments()); }
                show(menu.player, new Request(Page.CONFIRM, menu.request.game(), action, args, 0, Optional.of(selected)));
            }, "Selecionar para " + menu.request.action());
        }
        if (offset > 0) button(menu, 46, Material.ARROW, "Anterior", () -> playerPage(menu, Math.max(0, offset - 28)));
        if (end < players.size()) button(menu, 52, Material.ARROW, "Seguinte", () -> playerPage(menu, end));
    }

    private void playerPage(Menu menu, int offset) {
        Request r = menu.request;
        show(menu.player, new Request(Page.PLAYERS, r.game(), r.action(), r.arguments(), offset, Optional.empty()));
    }

    private void party(Menu menu) {
        String[] actions = {"criar", "convidar", "aceitar", "expulsar", "sair", "dissolver", "estado"};
        for (int i = 0; i < actions.length; i++) {
            String action = actions[i];
            button(menu, 10 + i, Material.LEAD, "Grupo: " + action, () -> {
                if (List.of("convidar", "aceitar", "expulsar").contains(action)) choosePlayer(menu, "grupo:" + action, List.of());
                else if (List.of("sair", "dissolver").contains(action)) confirm(menu, "grupo", List.of(action));
                else perform(menu, "grupo", List.of(action));
            });
        }
    }

    private void confirm(Menu menu, String action, List<String> arguments) {
        show(menu.player, new Request(Page.CONFIRM, menu.request.game(), action, arguments, 0, Optional.empty()));
    }

    private void confirmation(Menu menu) {
        Request r = menu.request;
        button(menu, 13, Material.PAPER, "Confirmar: " + r.action(), null, String.join(" ", r.arguments()),
                r.arguments().contains("aposta") || r.action().equals("aposta") ? "APOSTA: podes perder o equipamento" : "Revê a ação antes de confirmar");
        button(menu, 20, Material.LIME_CONCRETE, "Confirmar", () -> perform(menu, r.action(), r.arguments()));
        button(menu, 24, Material.RED_CONCRETE, "Cancelar", () -> show(menu.player, request(Page.GAME, r.game())));
    }

    private void perform(Menu menu, String action, List<String> arguments) {
        if (menu.request.target().isPresent()) {
            Target target = menu.request.target().orElseThrow();
            Player current = plugin.getServer().getPlayer(target.playerId());
            if (current == null || arenaQueued(target.playerId()) || sessions.findByPlayer(target.playerId()).isPresent() || !menu.player.canSee(current) || !current.getName().equals(target.name())
                    || guard(current, Optional.of(GameKey.ARENA)).filter(value -> value.connectionId().equals(target.connectionId())).isEmpty()) {
                menu.player.sendMessage(Component.text("O jogador mudou de sessão. Seleciona-o novamente.", NamedTextColor.RED));
                return;
            }
        }
        GameKey key = menu.request.game().orElseThrow();
        if (!MinigameMenuGuard.allowsAction(sessions.findByPlayer(menu.player.getUniqueId()).map(value -> value.game()), key, action)) {
            menu.player.sendMessage(Component.text("Conclui primeiro o minijogo atual.", NamedTextColor.RED));
            return;
        }
        commands.performGameAction(menu.player, key, action, arguments);
        if (key != GameKey.ARENA || !action.equals("pronto")
                || modules.get(key).status().availability() != ModuleAvailability.RUNNING)
            show(menu.player, request(Page.GAME, Optional.of(key)));
    }

    private void rootCommand(Menu menu, String... args) {
        commands.onCommand(menu.player, Objects.requireNonNull(plugin.getCommand("minijogos")), "minijogos", args);
    }

    private void admin(Menu menu) {
        int slot = 10;
        for (GameKey game : GameKey.values()) {
            var state = modules.get(game).status();
            button(menu, slot++, icon(game), game.portugueseName(), null, state.messagePtPt(), "Estado: " + state.availability());
        }
        button(menu, 31, Material.COMPARATOR, "Estado do atualizador", () -> {
            if (menu.player.hasPermission("ciaac.minigames.admin")) rootCommand(menu, "atualizacao");
        });
        button(menu, 40, Material.PAPER, "Configuração das instalações", null,
                "Geometria e capturas exigem consola local", "Não há reload nem ativação por este menu");
    }

    private static String next(List<String> options, String current) {
        if (options.isEmpty()) return current;
        return options.get((options.indexOf(current) + 1) % options.size());
    }

    private void button(Menu menu, int slot, Material material, String title, Runnable action, String... lore) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(Component.text(title, NamedTextColor.GOLD));
        meta.lore(List.of(lore).stream().filter(value -> !value.isEmpty())
                .map(value -> Component.text(value, NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta); menu.inventory.setItem(slot, item);
        if (action != null) menu.actions.put(slot, action);
    }

    private static Material icon(GameKey game) {
        return switch (game) {
            case ARENA -> Material.IRON_SWORD;
            case BUILD_BATTLE -> Material.BRICKS;
            case HOT_POTATO -> Material.POTATO;
            case KNOCKBACK_SUMO -> Material.STICK;
            case CHECKPOINT_PARKOUR -> Material.FEATHER;
            case ARCHERY_RANGE -> Material.BOW;
            case ANVIL_DODGE -> Material.ANVIL;
            case COLOR_FLOOR -> Material.RED_WOOL;
            case ELYTRA_RINGS -> Material.ELYTRA;
        };
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        boolean cancelled = event.isCancelled(); event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || player != menu.player
                || event instanceof InventoryCreativeEvent || menu.consumed || open.get(player.getUniqueId()) != menu) return;
        Optional<MinigameMenuGuard> current = guard(player, menu.request.game());
        if (current.isEmpty() || !menu.guard.permits(current.orElseThrow(), event.getRawSlot(), menu.inventory.getSize(),
                event.getClick(), cursorEmpty(player), cancelled)) return;
        Runnable action = menu.actions.get(event.getRawSlot());
        if (action == null || !navigation.allow(player.getUniqueId(), "click")) return;
        menu.consumed = true;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (closed || open.get(player.getUniqueId()) != menu || player.getOpenInventory().getTopInventory() != menu.inventory) return;
            if (!guard(player, menu.request.game()).filter(menu.guard::equals).isPresent()
                    || !cursorEmpty(player) || menu.request.page() == Page.ADMIN && !player.hasPermission("ciaac.minigames.admin")) {
                player.closeInventory(); return;
            }
            player.closeInventory();
            try { action.run(); }
            catch (RuntimeException failure) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Minigame menu action failed", failure);
                player.sendMessage(Component.text("Não foi possível concluir o pedido. Consulta o estado do jogo.", NamedTextColor.RED));
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void move(InventoryMoveItemEvent event) {
        if (event.getSource().getHolder() instanceof Menu || event.getDestination().getHolder() instanceof Menu) event.setCancelled(true);
    }

    @EventHandler public void inventoryClosed(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu) open.remove(menu.player.getUniqueId(), menu);
    }

    @EventHandler public void quit(PlayerQuitEvent event) {
        open.remove(event.getPlayer().getUniqueId()); selections.remove(event.getPlayer().getUniqueId()); lanes.remove(event.getPlayer().getUniqueId()); buildPrompts.remove(event.getPlayer().getUniqueId());
    }

    /** State changes replace the entire window; old click packets cannot target new actions. */
    public void tick() {
        for (Menu menu : List.copyOf(open.values())) {
            Optional<MinigameMenuGuard> current = guard(menu.player, menu.request.game());
            if (current.isEmpty() || menu.request.page() == Page.ADMIN && !menu.player.hasPermission("ciaac.minigames.admin")) {
                menu.player.closeInventory();
            } else if (!menu.guard.equals(current.orElseThrow())) {
                menu.player.closeInventory();
                boolean combatStarted = menu.request.game().orElse(null) == GameKey.ARENA
                        && modules.get(GameKey.ARENA).status().availability() == ModuleAvailability.RUNNING;
                if (menu.request.page() != Page.CONFIRM && !combatStarted) show(menu.player, request(menu.request.game().isPresent() ? Page.GAME : Page.CATALOG, menu.request.game()));
            }
        }
        // Paper closes inventories on phase teleports. Offer voting once per phase/plot,
        // and respect a player who closes the resulting screen.
        if (modules.get(GameKey.BUILD_BATTLE) instanceof BuildBattleModule build) {
            BuildBattlePhase phase = build.controller().status().phase();
            if (phase == BuildBattlePhase.THEME_VOTING || phase == BuildBattlePhase.VOTING) {
                for (var session : sessions.all()) {
                    if (session.game() != GameKey.BUILD_BATTLE) continue;
                    Player player = plugin.getServer().getPlayer(session.playerId());
                    if (player == null || guard(player, Optional.of(GameKey.BUILD_BATTLE)).isEmpty()) continue;
                    var plot = phase == BuildBattlePhase.VOTING ? build.controller().currentReviewPlot(session.playerId()) : null;
                    if (phase == BuildBattlePhase.VOTING && plot == null) continue;
                    String prompt = session.sessionId() + ":" + phase + ":" + (plot == null ? "theme" : plot.id());
                    if (prompt.equals(buildPrompts.get(session.playerId()))) continue;
                    if (open.containsKey(session.playerId())) {
                        buildPrompts.put(session.playerId(), prompt);
                    } else if (cursorEmpty(player) && player.getOpenInventory().getTopInventory().getHolder() instanceof Player) {
                        show(player, request(Page.GAME, Optional.of(GameKey.BUILD_BATTLE)));
                        if (open.containsKey(session.playerId())) buildPrompts.put(session.playerId(), prompt);
                    }
                }
            }
        }
    }

    @Override public void close() {
        closed = true;
        for (Menu menu : List.copyOf(open.values())) menu.player.closeInventory();
        open.clear(); selections.clear(); lanes.clear(); buildPrompts.clear();
    }
}
