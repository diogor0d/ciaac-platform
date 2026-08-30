package com.ciaac.minecraft.platform.securityevents;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Logger;

/** Emits one bounded base64url JSON envelope per line for the external observer. */
public final class SecurityEventLogger {
    public static final String PREFIX = "CIAAC_SECURITY_EVENT_V1 ";
    public static final int MAXIMUM_ENVELOPE_BYTES = 4096;
    private final Logger logger;
    private final String instanceId;

    public SecurityEventLogger(Logger logger, String instanceId) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.instanceId = bounded(instanceId, "instanceId", 64);
    }

    public void emit(SecurityEvent event) {
        byte[] json = encode(Objects.requireNonNull(event, "event")).getBytes(StandardCharsets.UTF_8);
        if (json.length > MAXIMUM_ENVELOPE_BYTES) {
            throw new IllegalArgumentException("O evento de segurança excede o limite de 4096 bytes.");
        }
        logger.info(PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json));
    }

    String encode(SecurityEvent event) {
        StringBuilder output = new StringBuilder(768);
        output.append('{')
                .append("\"schemaVersion\":\"1\",")
                .append("\"eventId\":").append(quoted(event.eventId().toString())).append(',')
                .append("\"sourceId\":").append(quoted(instanceId)).append(',')
                .append("\"occurredAt\":").append(quoted(event.occurredAt().toString())).append(',')
                .append("\"parserName\":\"ciaac-security-event\",")
                .append("\"parserVersion\":\"v1\",")
                .append("\"category\":").append(quoted(category(event.category()))).append(',')
                .append("\"type\":").append(quoted(event.eventType())).append(',')
                .append("\"severityFloor\":").append(quoted(event.severityFloor().name())).append(',')
                .append("\"identityProvenance\":");
        if (event.actor().isEmpty()) output.append("\"SYSTEM\"");
        else {
            SecurityEvent.Actor actor = event.actor().orElseThrow();
            if (!actor.authenticated()) {
                throw new IllegalArgumentException("A identidade nao autenticada nao pode ser emitida neste envelope.");
            }
            output.append("\"POST_AUTHENTICATED\",")
                    .append("\"authenticatedActorId\":").append(quoted(actor.actorRef())).append(',')
                    .append("\"authenticatedActorName\":").append(quoted(actor.actorName()));
        }
        output.append(",\"attributes\":{\"result_code\":").append(quoted(event.outcomeCode()));
        for (Map.Entry<String, String> attribute : new java.util.TreeMap<>(event.attributes()).entrySet()) {
            if (!attribute.getKey().equals("result_code")) output.append(',')
                    .append(quoted(attribute.getKey())).append(':').append(quoted(attribute.getValue()));
        }
        output.append("},\"correlationId\":")
                .append(quoted(event.correlationId().toString()))
                .append(",\"sourceCursor\":\"paper-log\"");
        event.publicChatContent().ifPresent(content -> output.append(",\"publicChatContent\":").append(quoted(content)));
        return output.append('}').toString();
    }

    private static String category(SecurityEvent.Category category) {
        return switch (category) {
            case AUTHENTICATION -> "AUTHENTICATION";
            case PRIVILEGE -> "PRIVILEGE";
            case RECOVERY -> "RECOVERY";
            case INTEGRITY -> "AUDIT";
            case LIFECYCLE -> "PLUGIN_HEALTH";
            case MODERATION -> "MODERATION";
            case PUBLIC_CHAT -> "PUBLIC_CHAT";
        };
    }

    private static String quoted(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.append('"').toString();
    }

    private static String bounded(String value, String field, int maximum) {
        String candidate = Objects.requireNonNull(value, field).strip();
        if (candidate.isEmpty() || candidate.length() > maximum || candidate.indexOf('\n') >= 0
                || candidate.indexOf('\r') >= 0) throw new IllegalArgumentException(field + " is invalid");
        return candidate;
    }
}
