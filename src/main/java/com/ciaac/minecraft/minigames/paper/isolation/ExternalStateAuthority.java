package com.ciaac.minecraft.minigames.paper.isolation;

import java.util.UUID;

/** Read-only authoritative state. Reads and validation must never create accounts or write data. */
public interface ExternalStateAuthority {
    boolean available();
    byte[] read(UUID playerId);
    void validate(UUID playerId, byte[] payload);
}
