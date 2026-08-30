package com.ciaac.minecraft.minigames.runtime;

import java.util.Objects;
import java.util.Optional;

public record AdmissionResult(
        AdmissionStatus status,
        String code,
        String messagePtPt,
        Optional<PlayerSession> session) {

    public AdmissionResult {
        Objects.requireNonNull(status, "status");
        code = MachineCode.normalize(code, "code");
        if (messagePtPt == null || messagePtPt.isBlank() || messagePtPt.length() > 300) {
            throw new IllegalArgumentException("messagePtPt must contain 1-300 characters");
        }
        session = Objects.requireNonNull(session, "session");
    }

    public static AdmissionResult rejected(String code, String messagePtPt) {
        return new AdmissionResult(AdmissionStatus.REJECTED, code, messagePtPt, Optional.empty());
    }
}
