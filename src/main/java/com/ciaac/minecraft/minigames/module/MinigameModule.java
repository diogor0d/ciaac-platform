package com.ciaac.minecraft.minigames.module;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Shared port for commands and native displays.
 *
 * <p>Game-specific event routing stays behind each implementation. This keeps
 * future games additive: register one module without changing command or
 * display dispatch.</p>
 */
public interface MinigameModule {
    GameKey key();

    ModuleStatus status();

    /** Stable current match identity used for deduplicated announcements and audit correlation. */
    default Optional<UUID> currentMatchId() { return Optional.empty(); }

    ModuleActionResult join(Player player, List<String> arguments);

    ModuleActionResult leave(Player player);

    default ModuleActionResult ready(Player player) {
        return ModuleActionResult.rejected(
                "READY_UNSUPPORTED", "Este minijogo não usa confirmação de prontidão.");
    }

    /** Additive game-specific action hook without coupling the shared command dispatcher. */
    default ModuleActionResult action(Player player, String action, List<String> arguments) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(arguments, "arguments");
        return ModuleActionResult.rejected(
                "ACTION_UNKNOWN", "Essa ação não está disponível neste minijogo.");
    }

    default List<String> joinCompletions(List<String> arguments) {
        return List.of();
    }

    default List<String> actionCompletions(String action, List<String> arguments) {
        return "entrar".equalsIgnoreCase(Objects.requireNonNull(action, "action"))
                ? joinCompletions(Objects.requireNonNull(arguments, "arguments"))
                : List.of();
    }

    default void tick() {}

    default void shutdown() {}
}
