package com.ciaac.minecraft.minigames.persistence;

public final class PersistenceFailure extends RuntimeException {
    public PersistenceFailure(String message, Throwable cause) {
        super(message, cause);
    }

    public PersistenceFailure(String message) {
        super(message);
    }
}
