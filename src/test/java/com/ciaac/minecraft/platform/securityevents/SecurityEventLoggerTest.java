package com.ciaac.minecraft.platform.securityevents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class SecurityEventLoggerTest {
    @Test
    void emitsOneBoundedCanonicalEnvelopeWithoutRawMarker() {
        Logger logger = Logger.getLogger("security-event-test-" + UUID.randomUUID());
        logger.setUseParentHandlers(false);
        AtomicReference<String> message = new AtomicReference<>();
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { message.set(record.getMessage()); }
            @Override public void flush() {}
            @Override public void close() {}
        });
        UUID playerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID connectionId = UUID.fromString("22222222-2222-2222-2222-222222222222");

        new SecurityEventLogger(logger, "paper-principal").emit(SecurityEvent.authenticatedPlayer(
                Instant.parse("2026-08-26T12:00:00Z"), playerId, "JogadorSintético", connectionId,
                "PLAYER_AUTHENTICATED", SecurityEvent.Severity.INFO, "SUCCESS"));

        assertTrue(message.get().startsWith(SecurityEventLogger.PREFIX));
        String encoded = message.get().substring(SecurityEventLogger.PREFIX.length());
        String json = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schemaVersion\":\"1\""));
        assertTrue(json.contains(playerId.toString()));
        assertTrue(json.contains("\"identityProvenance\":\"POST_AUTHENTICATED\""));
        assertTrue(json.contains("\"sourceCursor\":\"paper-log\""));
        assertFalse(json.contains("password"));
    }

    @Test
    void rejectsFreeTextAttributesAndIdentityConflicts() {
        assertThrows(IllegalArgumentException.class, () -> new SecurityEvent(
                UUID.randomUUID(), Instant.now(), SecurityEvent.Category.INTEGRITY,
                "BAD EVENT", SecurityEvent.Severity.HIGH, "FAILED", java.util.Optional.empty(),
                java.util.Map.of(), UUID.randomUUID(), java.util.Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new SecurityEvent.Actor(
                SecurityEvent.IdentityKind.UNAUTHENTICATED_REFERENCE, "actor", "Jogador", true));
    }

    @Test
    void refusesToEscalateAnUnauthenticatedActorInTheExternalEnvelope() {
        SecurityEvent event = new SecurityEvent(
                UUID.randomUUID(), Instant.parse("2026-08-26T12:00:00Z"), SecurityEvent.Category.MODERATION,
                "MODERATION_OBSERVED", SecurityEvent.Severity.LOW, "OBSERVED",
                java.util.Optional.of(new SecurityEvent.Actor(
                        SecurityEvent.IdentityKind.UNAUTHENTICATED_REFERENCE, "reference", "Jogador", false)),
                java.util.Map.of(), UUID.randomUUID(), java.util.Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> new SecurityEventLogger(Logger.getAnonymousLogger(), "paper-principal").emit(event));
    }

    @Test
    void instanceIdentifierCannotInjectAnotherLogLine() {
        assertThrows(IllegalArgumentException.class,
                () -> new SecurityEventLogger(Logger.getAnonymousLogger(), "paper\nforged"));
        assertEquals(4096, SecurityEventLogger.MAXIMUM_ENVELOPE_BYTES);
    }
}
