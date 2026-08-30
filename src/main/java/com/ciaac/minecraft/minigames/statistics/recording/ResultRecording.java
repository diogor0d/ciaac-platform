package com.ciaac.minecraft.minigames.statistics.recording;

import java.util.Objects;
import java.util.UUID;

/** Outcome of one attempt to apply an immutable result to the statistics sink. */
public record ResultRecording(Status status, UUID resultId, String code) {
    public enum Status {
        RECORDED,
        IDEMPOTENT_REPLAY,
        UNAVAILABLE,
        FAILED
    }

    public ResultRecording {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(resultId, "resultId");
        Objects.requireNonNull(code, "code");
    }

    public boolean committed() {
        return status == Status.RECORDED || status == Status.IDEMPOTENT_REPLAY;
    }
}
