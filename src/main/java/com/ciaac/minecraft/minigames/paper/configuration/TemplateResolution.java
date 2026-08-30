package com.ciaac.minecraft.minigames.paper.configuration;

import java.util.Objects;

/**
 * Explicit hand-off for data that must be supplied by a later Paper adapter.
 * This resolver never reads live blocks, entities, or chunks.
 */
public record TemplateResolution(State state, String identifier, String reason) {
    public enum State { UNRESOLVED, READY_FOR_ADAPTER }

    public TemplateResolution {
        state = Objects.requireNonNull(state, "state");
        identifier = bounded(identifier, "identifier", 128);
        reason = bounded(reason, "reason", 300);
    }

    public static TemplateResolution unresolved(String identifier, String reason) {
        return new TemplateResolution(State.UNRESOLVED, identifier, reason);
    }

    public static TemplateResolution readyForAdapter(String identifier) {
        return new TemplateResolution(State.READY_FOR_ADAPTER, identifier, "A aguardar validação do adaptador.");
    }

    public boolean unresolved() {
        return state == State.UNRESOLVED;
    }

    private static String bounded(String value, String name, int max) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isBlank() || trimmed.length() > max) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return trimmed;
    }
}
