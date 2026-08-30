package com.ciaac.minecraft.minigames.module;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.util.List;
import java.util.Objects;
import org.bukkit.entity.Player;

/** Explicit fail-closed placeholder retained when construction or validation fails. */
public final class UnavailableMinigameModule implements MinigameModule {
    private final GameKey key;
    private final String reasonPtPt;

    public UnavailableMinigameModule(GameKey key, String reasonPtPt) {
        this.key = Objects.requireNonNull(key, "key");
        if (reasonPtPt == null || reasonPtPt.isBlank()) {
            throw new IllegalArgumentException("reasonPtPt must not be blank");
        }
        this.reasonPtPt = reasonPtPt;
    }

    @Override public GameKey key() { return key; }
    @Override public ModuleStatus status() { return ModuleStatus.closed(key, reasonPtPt); }
    @Override public ModuleActionResult join(Player player, List<String> arguments) {
        return ModuleActionResult.rejected("MODULE_UNAVAILABLE", reasonPtPt);
    }
    @Override public ModuleActionResult leave(Player player) {
        return ModuleActionResult.rejected("NO_ACTIVE_SESSION", "Não tens uma sessão ativa neste minijogo.");
    }
}
