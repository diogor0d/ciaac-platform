package com.ciaac.minecraft.minigames.paper.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.isolation.PlayerStateGateway;
import com.ciaac.minecraft.minigames.isolation.SnapshotEnvelopeCodec;
import com.ciaac.minecraft.minigames.isolation.SnapshotRepository;
import com.ciaac.minecraft.minigames.persistence.AuditEvent;
import com.ciaac.minecraft.minigames.persistence.AuditRepository;
import com.ciaac.minecraft.minigames.persistence.SessionRepository;
import com.ciaac.minecraft.minigames.runtime.AdmissionStatus;
import com.ciaac.minecraft.minigames.runtime.AuthenticatedSession;
import com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry;
import com.ciaac.minecraft.minigames.runtime.ConnectionRegistry;
import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import com.ciaac.minecraft.minigames.runtime.SessionCoordinator;
import com.ciaac.minecraft.minigames.runtime.SessionPhase;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import com.ciaac.minecraft.minigames.runtime.SessionViolation;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class SessionRecoveryServiceTest {
    private static final Instant START = Instant.parse("2026-10-04T12:00:00Z");

    @Test
    void missingProviderCompletionCapabilityPreservesPendingSessionBeforeAnyRecoveryWork() {
        SessionRegistry registry = new SessionRegistry();
        PlayerSession session = activeSession();
        registry.register(session);
        Player player = player(session.playerId());
        AuthenticationRegistry authentication = new AuthenticationRegistry();
        ConnectionRegistry connections = new ConnectionRegistry();
        connections.begin(player, START.plusSeconds(10));
        AtomicInteger snapshotReads = new AtomicInteger();
        AtomicInteger stopCalls = new AtomicInteger();
        List<AuditEvent> audits = new ArrayList<>();
        SessionRecoveryService service = new SessionRecoveryService(
                repository(), registry, coordinator(registry, snapshotReads), audits::add,
                authentication, connections, Clock.fixed(START.plusSeconds(20), ZoneOffset.UTC),
                ignored -> stopCalls.incrementAndGet());

        var result = service.onAuthenticated(player);

        assertEquals("AUTHENTICATION_REQUIRED", result.code());
        assertEquals(SessionPhase.ACTIVE, session.phase());
        assertEquals(session, registry.findByPlayer(session.playerId()).orElseThrow());
        assertEquals(0, stopCalls.get());
        assertEquals(0, snapshotReads.get());
        assertTrue(audits.isEmpty());
    }

    @Test
    void authenticatedRecoveryReportsWhenStopCallbackCompletesAndReleasesOriginalSession() {
        SessionRegistry registry = new SessionRegistry();
        PlayerSession session = activeSession();
        registry.register(session);
        AuthContext auth = authenticatedPlayer(session.playerId(), START.plusSeconds(10));
        List<AuditEvent> audits = new ArrayList<>();
        SessionCoordinator coordinator = coordinator(registry);
        SessionRecoveryService service = new SessionRecoveryService(
                repository(), registry, coordinator, audits::add, auth.authentication, auth.connections,
                Clock.fixed(START.plusSeconds(10), ZoneOffset.UTC),
                original -> closeAndRelease(original, registry));

        var result = service.onAuthenticated(auth.player);

        assertEquals(AdmissionStatus.RECOVERED, result.status());
        assertEquals("RESTORED", result.code());
        assertEquals(SessionPhase.CLOSED, session.phase());
        assertTrue(registry.findById(session.sessionId()).isEmpty());
        assertEquals("RESTORED", audits.getLast().outcomeCode());
    }

    @Test
    void hydratedActiveSumoSessionRecoversAfterAuthentication() {
        SessionRegistry registry = new SessionRegistry();
        PlayerSession hydrated = activeSession(GameKey.KNOCKBACK_SUMO);
        AuthContext auth = authenticatedPlayer(hydrated.playerId(), START.plusSeconds(10));
        List<AuditEvent> audits = new ArrayList<>();
        AtomicInteger stopCalls = new AtomicInteger();
        SessionRepository persisted = proxy(SessionRepository.class, (method, args) ->
                method.getName().equals("nonTerminal") ? List.of(hydrated) : null);
        SessionRecoveryService service = new SessionRecoveryService(
                persisted, registry, coordinator(registry), audits::add,
                auth.authentication, auth.connections, Clock.fixed(START.plusSeconds(10), ZoneOffset.UTC),
                session -> {
                    stopCalls.incrementAndGet();
                    closeAndRelease(session, registry);
                });

        assertEquals(List.of(hydrated), service.loadBlockingSessions());
        var result = service.onAuthenticated(auth.player);

        assertEquals(AdmissionStatus.RECOVERED, result.status());
        assertEquals("RESTORED", result.code());
        assertEquals(GameKey.KNOCKBACK_SUMO, hydrated.game());
        assertEquals(1, stopCalls.get());
        assertTrue(registry.findById(hydrated.sessionId()).isEmpty());
        assertEquals("RESTORED", audits.getLast().outcomeCode());
    }

    @Test
    void violationRecoveryDoesNotRecoverAgainAfterStopCallbackClosedSessionAndRegisteredNewerIdentity() {
        SessionRegistry registry = new SessionRegistry();
        PlayerSession original = activeSession();
        registry.register(original);
        AuthContext auth = authenticatedPlayer(original.playerId(), START.plusSeconds(20));
        PlayerSession newer = new PlayerSession(
                UUID.randomUUID(), UUID.randomUUID(), original.playerId(), GameKey.ARENA, START.plusSeconds(10));
        List<AuditEvent> audits = new ArrayList<>();
        SessionCoordinator coordinator = coordinator(registry);
        SessionRecoveryService service = new SessionRecoveryService(
                repository(), registry, coordinator, audits::add, auth.authentication, auth.connections,
                Clock.fixed(START.plusSeconds(20), ZoneOffset.UTC),
                session -> {
                    closeAndRelease(session, registry);
                    registry.register(newer);
                });

        service.onViolation(auth.player, original, SessionViolation.WORLD_ISOLATION_FAILURE);

        assertEquals(SessionPhase.CLOSED, original.phase());
        assertTrue(registry.findById(original.sessionId()).isEmpty());
        assertEquals(newer, registry.findByPlayer(original.playerId()).orElseThrow());
        assertEquals("RESTORED", audits.getLast().outcomeCode());
    }

    @Test
    void authenticatedRecoveryReportsQuarantineReachedByStopCallback() {
        SessionRegistry registry = new SessionRegistry();
        PlayerSession session = activeSession();
        registry.register(session);
        AuthContext auth = authenticatedPlayer(session.playerId(), START.plusSeconds(20));
        List<AuditEvent> audits = new ArrayList<>();
        SessionCoordinator coordinator = coordinator(registry);
        SessionRecoveryService service = new SessionRecoveryService(
                repository(), registry, coordinator, audits::add, auth.authentication, auth.connections,
                Clock.fixed(START.plusSeconds(20), ZoneOffset.UTC),
                original -> {
                    original.transition(UUID.randomUUID(), SessionPhase.ACTIVE, SessionPhase.RECOVERING,
                            original.updatedAt().plusSeconds(1), "TEST");
                    original.transition(UUID.randomUUID(), SessionPhase.RECOVERING, SessionPhase.QUARANTINED,
                            original.updatedAt().plusSeconds(1), "TEST");
                });

        var result = service.onAuthenticated(auth.player);

        assertEquals(AdmissionStatus.QUARANTINED, result.status());
        assertEquals("RECOVERY_QUARANTINED", result.code());
        assertEquals(SessionPhase.QUARANTINED, session.phase());
        assertEquals("RECOVERY_QUARANTINED", audits.getLast().outcomeCode());
    }

    @Test
    void closedButStillRegisteredSessionIsNotReportedAsCompletedRecovery() {
        SessionRegistry registry = new SessionRegistry();
        PlayerSession session = activeSession();
        registry.register(session);
        AuthContext auth = authenticatedPlayer(session.playerId(), START.plusSeconds(20));
        SessionRecoveryService service = new SessionRecoveryService(
                repository(), registry, coordinator(registry), event -> true, auth.authentication, auth.connections,
                Clock.fixed(START.plusSeconds(20), ZoneOffset.UTC), SessionRecoveryServiceTest::closeThroughLifecycle);

        assertThrows(IllegalStateException.class, () -> service.onAuthenticated(auth.player));
        assertEquals(SessionPhase.CLOSED, session.phase());
        assertEquals(session, registry.findById(session.sessionId()).orElseThrow());
    }

    @Test
    void unauthenticatedViolationIsAuditedAndDeferredWithoutStoppingOrRecovering() {
        SessionRegistry registry = new SessionRegistry();
        PlayerSession session = activeSession();
        registry.register(session);
        Player player = player(session.playerId());
        AuthenticationRegistry authentication = new AuthenticationRegistry();
        ConnectionRegistry connections = new ConnectionRegistry();
        connections.begin(player, START.plusSeconds(10));
        AtomicInteger snapshotReads = new AtomicInteger();
        AtomicInteger stopCalls = new AtomicInteger();
        List<AuditEvent> audits = new ArrayList<>();
        SessionRecoveryService service = new SessionRecoveryService(
                repository(), registry, coordinator(registry, snapshotReads), audits::add,
                authentication, connections, Clock.fixed(START.plusSeconds(20), ZoneOffset.UTC),
                ignored -> stopCalls.incrementAndGet());

        service.onViolation(player, session, SessionViolation.UNAUTHORIZED_REGION_ENTRY);

        assertEquals(SessionPhase.ACTIVE, session.phase());
        assertEquals(0, stopCalls.get());
        assertEquals(0, snapshotReads.get());
        assertEquals("DEFERRED_UNAUTHENTICATED", audits.getLast().outcomeCode());
    }

    @Test
    void staleAuthenticationEpochCannotTriggerViolationRecovery() {
        SessionRegistry registry = new SessionRegistry();
        PlayerSession session = activeSession();
        registry.register(session);
        AuthContext stale = authenticatedPlayerThenReplaceConnection(session.playerId(), START.plusSeconds(20));
        AtomicInteger stopCalls = new AtomicInteger();
        List<AuditEvent> audits = new ArrayList<>();
        SessionRecoveryService service = new SessionRecoveryService(
                repository(), registry, coordinator(registry), audits::add,
                stale.authentication, stale.connections, Clock.fixed(START.plusSeconds(20), ZoneOffset.UTC),
                ignored -> stopCalls.incrementAndGet());

        service.onViolation(stale.player, session, SessionViolation.UNAUTHORIZED_REGION_ENTRY);

        assertEquals(SessionPhase.ACTIVE, session.phase());
        assertEquals(0, stopCalls.get());
        assertEquals("DEFERRED_UNAUTHENTICATED", audits.getLast().outcomeCode());
    }

    @Test
    void currentAuthenticatedViolationKeepsNormalStopAndRecoveryPath() {
        SessionRegistry registry = new SessionRegistry();
        PlayerSession session = activeSession();
        registry.register(session);
        AuthContext auth = authenticatedPlayer(session.playerId(), START.plusSeconds(20));
        AtomicInteger stopCalls = new AtomicInteger();
        List<AuditEvent> audits = new ArrayList<>();
        SessionRecoveryService service = new SessionRecoveryService(
                repository(), registry, coordinator(registry), audits::add,
                auth.authentication, auth.connections, Clock.fixed(START.plusSeconds(20), ZoneOffset.UTC),
                ignored -> stopCalls.incrementAndGet());

        service.onViolation(auth.player, session, SessionViolation.UNAUTHORIZED_REGION_ENTRY);

        assertEquals(1, stopCalls.get());
        assertEquals(SessionPhase.QUARANTINED, session.phase());
        assertEquals("RECOVERY_QUARANTINED", audits.getLast().outcomeCode());
    }

    @Test
    void authenticatedRecoveryRejectsStaleConnectionEpochBeforeStopCallback() {
        SessionRegistry registry = new SessionRegistry();
        PlayerSession session = activeSession();
        registry.register(session);
        AuthContext stale = authenticatedPlayerThenReplaceConnection(session.playerId(), START.plusSeconds(20));
        AtomicInteger stopCalls = new AtomicInteger();
        SessionRecoveryService service = new SessionRecoveryService(
                repository(), registry, coordinator(registry), event -> true,
                stale.authentication, stale.connections, Clock.fixed(START.plusSeconds(20), ZoneOffset.UTC),
                ignored -> stopCalls.incrementAndGet());

        var result = service.onAuthenticated(stale.player);

        assertEquals(AdmissionStatus.REJECTED, result.status());
        assertEquals("AUTHENTICATION_REQUIRED", result.code());
        assertEquals(0, stopCalls.get());
        assertEquals(SessionPhase.ACTIVE, session.phase());
    }

    private static SessionCoordinator coordinator(SessionRegistry registry) {
        return coordinator(registry, new AtomicInteger());
    }

    private static SessionCoordinator coordinator(SessionRegistry registry, AtomicInteger snapshotReads) {
        return new SessionCoordinator(
                new com.ciaac.minecraft.minigames.runtime.AuthenticationRegistry(), registry,
                proxy(SessionRepository.class, (method, args) -> method.getName().equals("save") ? true : null),
                proxy(SnapshotRepository.class, (method, args) -> {
                    if (method.getName().equals("find")) {
                        snapshotReads.incrementAndGet();
                        return java.util.Optional.empty();
                    }
                    return null;
                }),
                proxy(PlayerStateGateway.class, (method, args) -> null),
                IsolationPolicy.strictNoProgress(), new SnapshotEnvelopeCodec(),
                Clock.fixed(START.plusSeconds(30), ZoneOffset.UTC));
    }

    private static SessionRepository repository() {
        return proxy(SessionRepository.class, (method, args) -> null);
    }

    private static PlayerSession activeSession() {
        return activeSession(GameKey.ARENA);
    }

    private static PlayerSession activeSession(GameKey game) {
        PlayerSession session = new PlayerSession(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), game, START);
        session.transition(UUID.randomUUID(), SessionPhase.REQUESTED, SessionPhase.SNAPSHOTTING,
                START.plusSeconds(1), "TEST");
        session.transition(UUID.randomUUID(), SessionPhase.SNAPSHOTTING, SessionPhase.SNAPSHOT_COMMITTED,
                START.plusSeconds(2), "TEST");
        session.transition(UUID.randomUUID(), SessionPhase.SNAPSHOT_COMMITTED, SessionPhase.PREPARING,
                START.plusSeconds(3), "TEST");
        session.transition(UUID.randomUUID(), SessionPhase.PREPARING, SessionPhase.ACTIVE,
                START.plusSeconds(4), "TEST");
        return session;
    }

    private static PlayerSession closeThroughLifecycle(PlayerSession session) {
        session.transition(UUID.randomUUID(), SessionPhase.ACTIVE, SessionPhase.FINISHING,
                session.updatedAt().plusSeconds(1), "TEST");
        session.transition(UUID.randomUUID(), SessionPhase.FINISHING, SessionPhase.RESTORING,
                session.updatedAt().plusSeconds(1), "TEST");
        session.transition(UUID.randomUUID(), SessionPhase.RESTORING, SessionPhase.CLOSED,
                session.updatedAt().plusSeconds(1), "TEST");
        return session;
    }

    private static void closeAndRelease(PlayerSession session, SessionRegistry registry) {
        closeThroughLifecycle(session);
        registry.releaseClosed(session.sessionId());
    }

    private static Player player(UUID playerId) {
        return proxy(Player.class, (method, args) -> switch (method.getName()) {
            case "getUniqueId" -> playerId;
            case "isOnline" -> true;
            default -> null;
        });
    }

    private static AuthContext authenticatedPlayer(UUID playerId, Instant now) {
        Player player = player(playerId);
        AuthenticationRegistry authentication = new AuthenticationRegistry();
        ConnectionRegistry connections = new ConnectionRegistry();
        ConnectionRegistry.Connection connection = connections.begin(player, now.minusSeconds(10));
        authentication.authenticated(new AuthenticatedSession(
                UUID.randomUUID(), playerId, connection.id(), now.minusSeconds(1), now.plusSeconds(3600)));
        return new AuthContext(player, authentication, connections);
    }

    private static AuthContext authenticatedPlayerThenReplaceConnection(UUID playerId, Instant now) {
        Player player = player(playerId);
        AuthenticationRegistry authentication = new AuthenticationRegistry();
        ConnectionRegistry connections = new ConnectionRegistry();
        ConnectionRegistry.Connection old = connections.begin(player, now.minusSeconds(10));
        authentication.authenticated(new AuthenticatedSession(
                UUID.randomUUID(), playerId, old.id(), now.minusSeconds(1), now.plusSeconds(3600)));
        connections.begin(player, now);
        return new AuthContext(player, authentication, connections);
    }

    private record AuthContext(Player player, AuthenticationRegistry authentication, ConnectionRegistry connections) {}

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> type.getSimpleName() + " test proxy";
                    default -> null;
                };
            }
            return invocation.call(method, args == null ? new Object[0] : args);
        });
    }

    @FunctionalInterface
    private interface Invocation {
        Object call(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }
}
