package com.ciaac.minecraft.minigames.runtime;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Enforces one non-terminal minigame session per authenticated player. */
public final class SessionRegistry {
    private final Map<UUID, PlayerSession> bySessionId = new LinkedHashMap<>();
    private final Map<UUID, UUID> sessionIdByPlayer = new LinkedHashMap<>();
    private final Map<UUID, Set<UUID>> sessionIdsByMatch = new LinkedHashMap<>();

    public synchronized boolean register(PlayerSession session) {
        Objects.requireNonNull(session, "session");
        PlayerSession existingById = bySessionId.get(session.sessionId());
        if (existingById != null) {
            if (existingById != session) {
                throw new IllegalStateException("Conflicting session identifier " + session.sessionId());
            }
            return false;
        }
        UUID currentSessionId = sessionIdByPlayer.get(session.playerId());
        if (currentSessionId != null) {
            PlayerSession current = bySessionId.get(currentSessionId);
            if (current != null && current.phase() != SessionPhase.CLOSED) {
                throw new IllegalStateException("Player already has an active minigame session");
            }
        }
        bySessionId.put(session.sessionId(), session);
        sessionIdByPlayer.put(session.playerId(), session.sessionId());
        sessionIdsByMatch.computeIfAbsent(session.matchId(), ignored -> new LinkedHashSet<>())
                .add(session.sessionId());
        return true;
    }

    public synchronized void releaseClosed(UUID sessionId) {
        PlayerSession session = require(sessionId);
        if (session.phase() != SessionPhase.CLOSED) {
            throw new IllegalStateException("Only a safely closed session may be released");
        }
        bySessionId.remove(sessionId);
        sessionIdByPlayer.remove(session.playerId(), sessionId);
        Set<UUID> matchSessions = sessionIdsByMatch.get(session.matchId());
        if (matchSessions != null) {
            matchSessions.remove(sessionId);
            if (matchSessions.isEmpty()) {
                sessionIdsByMatch.remove(session.matchId());
            }
        }
    }

    public synchronized Optional<PlayerSession> findByPlayer(UUID playerId) {
        UUID sessionId = sessionIdByPlayer.get(Objects.requireNonNull(playerId, "playerId"));
        return Optional.ofNullable(sessionId == null ? null : bySessionId.get(sessionId));
    }

    public synchronized Optional<PlayerSession> findById(UUID sessionId) {
        return Optional.ofNullable(bySessionId.get(Objects.requireNonNull(sessionId, "sessionId")));
    }

    public synchronized Set<PlayerSession> findByMatch(UUID matchId) {
        Set<UUID> ids = sessionIdsByMatch.get(Objects.requireNonNull(matchId, "matchId"));
        if (ids == null) {
            return Set.of();
        }
        LinkedHashSet<PlayerSession> sessions = new LinkedHashSet<>();
        for (UUID id : ids) {
            sessions.add(bySessionId.get(id));
        }
        return Set.copyOf(sessions);
    }

    public synchronized Set<PlayerSession> all() {
        return Set.copyOf(bySessionId.values());
    }

    private PlayerSession require(UUID sessionId) {
        PlayerSession session = bySessionId.get(Objects.requireNonNull(sessionId, "sessionId"));
        if (session == null) {
            throw new IllegalArgumentException("Unknown session " + sessionId);
        }
        return session;
    }
}
