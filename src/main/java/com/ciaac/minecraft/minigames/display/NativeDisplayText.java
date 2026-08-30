package com.ciaac.minecraft.minigames.display;

import com.ciaac.minecraft.minigames.module.ModuleStatus;

/** Deterministic Portuguese text shared by every native display. */
public final class NativeDisplayText {
    private NativeDisplayText() { }

    public static String render(ModuleStatus status, String joinCommand) {
        String state = switch (status.availability()) {
            case CLOSED -> "Fechado"; case WAITING -> "À espera"; case STARTING -> "Preparação";
            case RUNNING -> "Em jogo"; case VOTING -> "Votação"; case FINISHING -> "A terminar"; case RECOVERY -> "A restaurar";
        };
        String capacity = status.capacity().isPresent() ? status.participants() + "/" + status.capacity().getAsInt() : status.participants() + "/?";
        String command = joinCommand == null ? "" : joinCommand;
        String result = status.game().portugueseName() + "\n" + state + " | " + capacity + " participantes\n" + status.messagePtPt() + "\n" + command;
        return result.length() <= 512 ? result : result.substring(0, 509) + "…";
    }
}
