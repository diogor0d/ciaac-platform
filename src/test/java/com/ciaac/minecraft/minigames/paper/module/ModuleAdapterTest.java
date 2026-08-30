package com.ciaac.minecraft.minigames.paper.module;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.module.ModuleActionResult;
import com.ciaac.minecraft.minigames.module.ModuleAvailability;
import com.ciaac.minecraft.minigames.module.ModuleStatus;
import com.ciaac.minecraft.minigames.paper.arena.ArenaPlayerResponse;
import com.ciaac.minecraft.minigames.runtime.AdmissionResult;
import com.ciaac.minecraft.minigames.runtime.AdmissionStatus;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

final class ModuleAdapterTest {
    @Test
    void queuedAdmissionIsAcceptedForCommandUxDespiteRuntimeRejectedStatus() {
        ModuleActionResult result = ModuleResults.admission(
                AdmissionResult.rejected("QUEUED", "Entraste na fila."));
        assertTrue(result.accepted());
    }

    @Test
    void realAdmissionFailureRemainsRejected() {
        ModuleActionResult result = ModuleResults.admission(
                AdmissionResult.rejected("AUTHENTICATION_REQUIRED", "Autentica-te."));
        assertFalse(result.accepted());
    }

    @Test
    void queuedCodeCannotOverrideAClosedAdmissionStatus() {
        ModuleActionResult result = ModuleResults.admission(new AdmissionResult(
                AdmissionStatus.FAILED_CLOSED, "QUEUED", "A admissão está fechada.", Optional.empty()));
        assertFalse(result.accepted());
    }

    @Test
    void statusCarriesSharedAvailabilityAndBoundedCapacity() {
        ModuleStatus status = new ModuleStatus(GameKey.HOT_POTATO, ModuleAvailability.WAITING,
                true, 2, OptionalInt.of(8), "À espera.");
        assertTrue(status.joinable());
        assertTrue(status.capacity().getAsInt() >= status.participants());
    }

    @Test
    void arenaErrorCodesRemainRejectedAtTheSharedBoundary() {
        ModuleActionResult result = ModuleResults.arena(
                new ArenaPlayerResponse("NOT_QUEUED", "Não estás na fila.", Optional.empty()));
        assertFalse(result.accepted());
        ModuleActionResult recovery = ModuleResults.arena(
                new ArenaPlayerResponse("RECOVERED", "A recuperação terminou.", Optional.empty()));
        assertFalse(recovery.accepted());
    }

    @Test
    void unknownPhaseFailsClosedForSharedDisplays() {
        ModuleStatus status = ModuleStatuses.of(GameKey.BUILD_BATTLE, true, "UNKNOWN", true,
                0, OptionalInt.empty(), "Estado indisponível.");
        assertFalse(status.joinable());
        assertTrue(status.availability() == ModuleAvailability.CLOSED);
    }
}
