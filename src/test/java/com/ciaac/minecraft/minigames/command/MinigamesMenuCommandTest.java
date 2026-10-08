package com.ciaac.minecraft.minigames.command;

import static org.junit.jupiter.api.Assertions.*;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.module.*;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class MinigamesMenuCommandTest {
    private final UUID playerId = UUID.randomUUID();
    private final Set<String> grants = new HashSet<>(List.of("ciaac.minigames.use", "ciaac.minigames.arena.use"));
    private final AtomicInteger joins = new AtomicInteger();
    private final List<List<String>> selections = new ArrayList<>();
    private final Player player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Player.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "hasPermission" -> grants.contains(args[0]);
                case "getUniqueId" -> playerId;
                case "toString" -> "menu-test-player";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> method.getReturnType() == boolean.class ? false : method.getReturnType() == int.class ? 0 : null;
            });
    private final MinigameModule arena = new MinigameModule() {
        public GameKey key() { return GameKey.ARENA; }
        public ModuleStatus status() { return ModuleStatus.closed(key(), "fixture"); }
        public ModuleActionResult join(Player player, List<String> args) {
            joins.incrementAndGet(); selections.add(List.copyOf(args)); return ModuleActionResult.accepted("QUEUED", "Em fila");
        }
        public ModuleActionResult leave(Player player) { return ModuleActionResult.accepted("LEFT", "Saíste"); }
    };
    private MinigamesCommand command() {
        return new MinigamesCommand(new MinigameModuleRegistry(Arrays.stream(GameKey.values()).map(key -> key == GameKey.ARENA ? arena : new UnavailableMinigameModule(key, "fixture")).toList()), Optional.empty(), ignored -> "fixture", Clock.systemUTC());
    }
    private static Command route(String name) {
        return new Command(name) { public boolean execute(CommandSender sender, String label, String[] args) { throw new AssertionError(); } };
    }

    @Test void emptyAndExplicitMenuCommandsNavigateWithoutJoining() {
        var command = command(); List<Optional<GameKey>> opened = new ArrayList<>();
        command.menuOpener((p, game) -> { assertSame(player, p); opened.add(game); });
        command.onCommand(player, route("minijogos"), "minijogos", new String[]{});
        command.onCommand(player, route("coliseu"), "coliseu", new String[]{"menu"});
        assertEquals(List.of(Optional.empty(), Optional.of(GameKey.ARENA)), opened);
        assertEquals(0, joins.get());
        assertThrows(IllegalStateException.class, () -> command.menuOpener((p, game) -> {}));
    }

    @Test void menuAndCommandsShareRateLimitsAndExactArguments() {
        var command = command();
        var first = command.performGameAction(player, GameKey.ARENA, "entrar", List.of("2v2", "equipamento"));
        assertTrue(first.accepted());
        command.onCommand(player, route("coliseu"), "coliseu", new String[]{"entrar", "1v1", "kit"});
        assertEquals(1, joins.get());
        assertEquals(List.of(List.of("2v2", "equipamento")), selections);
    }

    @Test void explicitGameAndGlobalDenialsAreCheckedBeforeActionsEvenAfterMenuOpen() {
        var command = command();
        grants.remove("ciaac.minigames.arena.use");
        assertEquals("PERMISSION_DENIED", command.performGameAction(player, GameKey.ARENA, "entrar", List.of()).code());
        grants.add("ciaac.minigames.arena.use"); grants.remove("ciaac.minigames.use");
        assertEquals("PERMISSION_DENIED", command.performGameAction(player, GameKey.ARENA, "entrar", List.of()).code());
        assertEquals(0, joins.get());
    }

    @Test void permissionRoutesMatchAllNineDeclaredNodes() {
        assertEquals(Set.of("ciaac.minigames.arena.use", "ciaac.minigames.buildbattle.use", "ciaac.minigames.hotpotato.use",
                "ciaac.minigames.sumo.use", "ciaac.minigames.parkour.use", "ciaac.minigames.archery.use",
                "ciaac.minigames.anvildodge.use", "ciaac.minigames.colorfloor.use", "ciaac.minigames.elytra.use"),
                new HashSet<>(Arrays.stream(GameKey.values()).map(GameCommandRoutes::permission).toList()));
    }
}
