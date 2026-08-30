package com.ciaac.minecraft.platform.securityevents;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Bounded, machine-readable observation. Local audit data remains authoritative. */
public record SecurityEvent(
        UUID eventId,
        Instant occurredAt,
        Category category,
        String eventType,
        Severity severityFloor,
        String outcomeCode,
        Optional<Actor> actor,
        Map<String, String> attributes,
        UUID correlationId,
        Optional<String> publicChatContent) {
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,63}");
    private static final Pattern VALUE = Pattern.compile("[A-Z0-9][A-Z0-9_.:-]{0,127}");
    private static final java.util.Set<String> ATTRIBUTE_KEYS = java.util.Set.of(
            "subsystem", "result_code", "reason_code", "plugin_code", "error_code", "action_code", "world_code", "count");

    public SecurityEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(category, "category");
        eventType = code(eventType, "eventType");
        Objects.requireNonNull(severityFloor, "severityFloor");
        outcomeCode = code(outcomeCode, "outcomeCode");
        actor = Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(attributes, "attributes");
        if (attributes.size() > 16) throw new IllegalArgumentException("attributes exceeds 16 entries");
        attributes = Map.copyOf(attributes.entrySet().stream().collect(java.util.stream.Collectors.toMap(
                entry -> attributeKey(entry.getKey()),
                entry -> value(entry.getValue(), "attribute value"))));
        Objects.requireNonNull(correlationId, "correlationId");
        publicChatContent = Objects.requireNonNull(publicChatContent, "publicChatContent");
        publicChatContent.ifPresent(content -> {
            if (category != Category.PUBLIC_CHAT || content.isBlank() || content.length() > 512
                    || content.indexOf('\n') >= 0 || content.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("public chat content is invalid");
            }
        });
    }

    public static SecurityEvent system(
            Instant occurredAt, Category category, String eventType, Severity floor, String outcomeCode) {
        UUID eventId = UUID.randomUUID();
        return new SecurityEvent(eventId, occurredAt, category, eventType, floor, outcomeCode,
                Optional.empty(), Map.of(), eventId, Optional.empty());
    }

    public static SecurityEvent authenticatedPlayer(
            Instant occurredAt, UUID playerId, String playerName, UUID connectionId, String eventType, Severity floor,
            String outcomeCode) {
        return new SecurityEvent(UUID.randomUUID(), occurredAt, Category.AUTHENTICATION, eventType, floor,
                outcomeCode, Optional.of(new Actor(IdentityKind.AUTHENTICATED_UUID, playerId.toString(), playerName, true)),
                Map.of("action_code", "AUTHENTICATED_SESSION"),
                connectionId, Optional.empty());
    }

    public static SecurityEvent authenticatedPublicChat(Instant occurredAt, UUID playerId, String playerName,
                                                        UUID connectionId, String content) {
        return new SecurityEvent(UUID.randomUUID(), occurredAt, Category.PUBLIC_CHAT, "AUTHENTICATED_PUBLIC_CHAT",
                Severity.LOW, "OBSERVED", Optional.of(new Actor(IdentityKind.AUTHENTICATED_UUID,
                playerId.toString(), playerName, true)), Map.of("result_code", "OBSERVED"), connectionId,
                Optional.of(content));
    }

    private static String code(String value, String field) {
        String candidate = Objects.requireNonNull(value, field).strip();
        if (!CODE.matcher(candidate).matches()) throw new IllegalArgumentException(field + " is not a code");
        return candidate;
    }

    private static String value(String value, String field) {
        String candidate = Objects.requireNonNull(value, field).strip();
        if (!VALUE.matcher(candidate).matches()) throw new IllegalArgumentException(field + " is not bounded");
        return candidate;
    }

    private static String attributeKey(String value) {
        String candidate = Objects.requireNonNull(value, "attribute key");
        if (!ATTRIBUTE_KEYS.contains(candidate)) throw new IllegalArgumentException("attribute key is not allowlisted");
        return candidate;
    }

    public enum Category { AUTHENTICATION, PRIVILEGE, RECOVERY, INTEGRITY, LIFECYCLE, MODERATION, PUBLIC_CHAT }
    public enum Severity { INFO, LOW, MEDIUM, HIGH, CRITICAL }
    public enum IdentityKind { AUTHENTICATED_UUID, UNAUTHENTICATED_REFERENCE, SYSTEM }

    public record Actor(IdentityKind identityKind, String actorRef, String actorName, boolean authenticated) {
        public Actor {
            Objects.requireNonNull(identityKind, "identityKind");
            actorRef = Objects.requireNonNull(actorRef, "actorRef").strip();
            if (actorRef.isEmpty() || actorRef.length() > 128 || actorRef.indexOf('\n') >= 0
                    || actorRef.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("actorRef is invalid");
            }
            actorName = Objects.requireNonNull(actorName, "actorName").strip();
            if (actorName.isEmpty() || actorName.length() > 64 || actorName.indexOf('\n') >= 0
                    || actorName.indexOf('\r') >= 0) throw new IllegalArgumentException("actorName is invalid");
            if (authenticated != (identityKind == IdentityKind.AUTHENTICATED_UUID)) {
                throw new IllegalArgumentException("authenticated flag conflicts with identity kind");
            }
        }
    }
}
