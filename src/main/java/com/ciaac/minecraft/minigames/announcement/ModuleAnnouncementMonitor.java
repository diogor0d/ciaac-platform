package com.ciaac.minecraft.minigames.announcement;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.module.MinigameModule;
import com.ciaac.minecraft.minigames.module.MinigameModuleRegistry;
import com.ciaac.minecraft.minigames.module.ModuleAvailability;
import com.ciaac.minecraft.minigames.module.ModuleStatus;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Edge-triggered status monitor; repository cooldowns remain the durable anti-spam authority. */
public final class ModuleAnnouncementMonitor {
    private final MinigameModuleRegistry modules;
    private final AnnouncementDispatcher dispatcher;
    private final Map<GameKey, ModuleStatus> previous = new EnumMap<>(GameKey.class);
    private final Map<GameKey, UUID> fallbackMatchIds = new EnumMap<>(GameKey.class);

    public ModuleAnnouncementMonitor(
            MinigameModuleRegistry modules, AnnouncementDispatcher dispatcher) {
        this.modules = Objects.requireNonNull(modules, "modules");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    public synchronized void tick() {
        for (MinigameModule module : modules.all()) {
            ModuleStatus current = module.status();
            ModuleStatus before = previous.put(module.key(), current);
            UUID matchId = module.currentMatchId().orElseGet(() -> fallback(module.key(), current));
            boolean waitingEdge = current.availability() == ModuleAvailability.WAITING
                    && current.participants() > 0
                    && (before == null || before.availability() != ModuleAvailability.WAITING
                            || before.participants() == 0);
            if (waitingEdge) dispatcher.waiting(module.key(), matchId, current.participants());
            boolean startingEdge = (current.availability() == ModuleAvailability.STARTING
                            || current.availability() == ModuleAvailability.RUNNING)
                    && (before == null || (before.availability() != ModuleAvailability.STARTING
                            && before.availability() != ModuleAvailability.RUNNING));
            if (startingEdge) dispatcher.starting(module.key(), matchId);
            if (current.participants() == 0
                    && (current.availability() == ModuleAvailability.WAITING
                            || current.availability() == ModuleAvailability.CLOSED)) {
                fallbackMatchIds.remove(module.key());
            }
        }
    }

    private UUID fallback(GameKey game, ModuleStatus status) {
        if (status.participants() == 0) {
            return UUID.nameUUIDFromBytes((game.id() + ":idle").getBytes(StandardCharsets.UTF_8));
        }
        return fallbackMatchIds.computeIfAbsent(game, ignored -> UUID.randomUUID());
    }
}
