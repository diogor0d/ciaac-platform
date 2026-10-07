package com.ciaac.minecraft.minigames.paper.module;

import com.ciaac.minecraft.minigames.runtime.AdmissionResult;
import com.ciaac.minecraft.minigames.runtime.AdmissionStatus;
import com.ciaac.minecraft.minigames.paper.arena.ArenaPlayerResponse;
import com.ciaac.minecraft.minigames.module.ModuleActionResult;

final class ModuleResults {
    private ModuleResults() {}
    static ModuleActionResult admission(AdmissionResult result) {
        boolean recoveryOrFailure = switch (result.code()) {
            case "RECOVERED", "QUARANTINED", "FAILED_CLOSED" -> true;
            default -> false;
        };
        boolean terminalStatus = result.status() == AdmissionStatus.FAILED_CLOSED
                || result.status() == AdmissionStatus.RECOVERED
                || result.status() == AdmissionStatus.QUARANTINED;
        boolean accepted = !recoveryOrFailure && !terminalStatus
                && (result.status() == AdmissionStatus.PREPARED || result.code().equals("QUEUED"));
        return new ModuleActionResult(accepted, result.code(), result.messagePtPt());
    }
    static ModuleActionResult arena(ArenaPlayerResponse result) {
        String code = result.code();
        boolean accepted = switch (code) {
            case "QUEUED", "LEFT_QUEUE", "READY_RECORDED", "COMBAT_STARTED",
                    "RESTORED", "RESTORED_UNRANKED", "PRESTART_CANCELLED", "CHALLENGE_CREATED",
                    "CHALLENGE_ACCEPTED", "STAKE_CONFIRMED", "STAKE_ALREADY_CONFIRMED",
                    "STAKE_CLAIMS_DELIVERED", "STAKE_NO_CLAIMS" -> true;
            default -> false;
        };
        return new ModuleActionResult(accepted, code, result.messagePtPt());
    }
    static ModuleActionResult failure() { return ModuleActionResult.rejected("MODULE_FAILURE", "Não foi possível concluir a operação do minijogo."); }
}
