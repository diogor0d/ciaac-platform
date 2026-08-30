package com.ciaac.minecraft.minigames.paper.elytrarings;
import com.ciaac.minecraft.minigames.elytrarings.ElytraRingsPhase;
import java.util.Objects;

public record ElytraRingsPaperStatus(boolean enabled, boolean admissionReady,
                                     ElytraRingsPhase phase, String messagePtPt,
                                     ElytraChunkPreparation.Status chunkPreparation) {
    public ElytraRingsPaperStatus {
        phase = Objects.requireNonNull(phase, "phase");
        messagePtPt = Objects.requireNonNull(messagePtPt, "messagePtPt");
        chunkPreparation = Objects.requireNonNull(chunkPreparation, "chunkPreparation");
    }

    /** Compatibility constructor for status consumers predating chunk preparation. */
    public ElytraRingsPaperStatus(boolean enabled, boolean admissionReady,
                                  ElytraRingsPhase phase, String messagePtPt) {
        this(enabled, admissionReady, phase, messagePtPt,
                new ElytraChunkPreparation.Status(
                        !enabled ? ElytraChunkPreparation.Phase.DISABLED
                                : admissionReady ? ElytraChunkPreparation.Phase.READY
                                : ElytraChunkPreparation.Phase.FAILED,
                        0, 0, 0,
                        !enabled ? "DISABLED" : admissionReady ? "READY" : "UNAVAILABLE"));
    }
}
