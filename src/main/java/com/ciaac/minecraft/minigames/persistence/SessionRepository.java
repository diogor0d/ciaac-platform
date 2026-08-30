package com.ciaac.minecraft.minigames.persistence;

import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository {
    /** Inserts or advances a session and its append-only transition evidence. */
    boolean save(PlayerSession session);

    Optional<PlayerSession> find(UUID sessionId);

    /** Sessions that still block new admission, including quarantined evidence. */
    List<PlayerSession> nonTerminal();
}
