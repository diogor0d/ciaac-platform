package com.ciaac.minecraft.minigames.command;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Canonical Portuguese command route for every registered game. */
public final class GameCommandRoutes {
    private static final Map<GameKey, String> BY_GAME;
    private static final Map<String, GameKey> BY_COMMAND;

    static {
        EnumMap<GameKey, String> routes = new EnumMap<>(GameKey.class);
        routes.put(GameKey.ARENA, "coliseu");
        routes.put(GameKey.BUILD_BATTLE, "buildbattle");
        routes.put(GameKey.HOT_POTATO, "batataquente");
        routes.put(GameKey.KNOCKBACK_SUMO, "sumo");
        routes.put(GameKey.CHECKPOINT_PARKOUR, "parkour");
        routes.put(GameKey.ARCHERY_RANGE, "arco");
        routes.put(GameKey.ANVIL_DODGE, "bigornas");
        routes.put(GameKey.COLOR_FLOOR, "cores");
        routes.put(GameKey.ELYTRA_RINGS, "elytra");
        BY_GAME = Map.copyOf(routes);
        LinkedHashMap<String, GameKey> inverse = new LinkedHashMap<>();
        routes.forEach((game, command) -> inverse.put(command, game));
        BY_COMMAND = Map.copyOf(inverse);
    }

    private GameCommandRoutes() {}

    public static String command(GameKey game) {
        return Objects.requireNonNull(BY_GAME.get(Objects.requireNonNull(game, "game")), "Missing route");
    }

    public static Optional<GameKey> game(String command) {
        if (command == null) return Optional.empty();
        return Optional.ofNullable(BY_COMMAND.get(command.toLowerCase(Locale.ROOT)));
    }

    public static String joinCommand(GameKey game) {
        return "/" + command(game) + " entrar";
    }
}
