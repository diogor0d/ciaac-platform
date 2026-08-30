package com.ciaac.minecraft.minigames.minecart;

import com.ciaac.minecraft.platform.securityevents.SecurityEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Emite provas locais limitadas para cada alteração temporária dos carrinhos. */
public final class MinecartSpeedAuditLogger implements MinecartSpeedAuditSink {
    public static final String PREFIX = "CIAAC_MINECART_TEST_AUDIT_V1 ";
    public static final int MAXIMUM_ENVELOPE_BYTES = 2048;
    private final Logger logger;
    private final Consumer<SecurityEvent> securityEvents;
    private final Function<CommandSender, Optional<SecurityEvent.Actor>> actorResolver;

    public MinecartSpeedAuditLogger(Logger logger) {
        this(logger, ignored -> {}, ignored -> Optional.empty());
    }

    public MinecartSpeedAuditLogger(Logger logger, Consumer<SecurityEvent> securityEvents) {
        this(logger, securityEvents, ignored -> Optional.empty());
    }

    public MinecartSpeedAuditLogger(
            Logger logger,
            Consumer<SecurityEvent> securityEvents,
            Function<CommandSender, Optional<SecurityEvent.Actor>> actorResolver) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.securityEvents = Objects.requireNonNull(securityEvents, "securityEvents");
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver");
    }

    @Override
    public void emit(Action action, CommandSender sender, MinecartSpeedControl.ChangeResult result) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(result, "result");
        List<MinecartSpeedControl.WorldChange> changes = result.worldChanges();
        if (changes.isEmpty()) {
            emitEncoded(encode(action, sender, result, null));
        } else {
            for (MinecartSpeedControl.WorldChange change : changes) {
                emitEncoded(encode(action, sender, result, change));
            }
        }
        emitSecurityEvent(sender, result);
    }

    private void emitSecurityEvent(CommandSender sender, MinecartSpeedControl.ChangeResult result) {
        Optional<SecurityEvent.Actor> actor = actorResolver.apply(sender);
        if (actor == null) actor = Optional.empty();
        java.util.Map<String, String> attributes = new java.util.LinkedHashMap<>();
        attributes.put("action_code", "MINECART_SPEED_TEST");
        attributes.put("count", Integer.toString(result.worldChanges().size()));
        securityEvents.accept(new SecurityEvent(
                UUID.randomUUID(),
                Instant.now(),
                SecurityEvent.Category.PRIVILEGE,
                "MINECART_TEST_OVERRIDE",
                SecurityEvent.Severity.MEDIUM,
                result.changed() ? "APPLIED" : "NO_CHANGE",
                actor,
                attributes,
                result.operationId(),
                Optional.empty()));
    }

    String encode(
            Action action,
            CommandSender sender,
            MinecartSpeedControl.ChangeResult result,
            MinecartSpeedControl.WorldChange change) {
        boolean player = sender instanceof Player;
        String actorName = bounded(sender.getName(), 64);
        String actorRef = player ? ((Player) sender).getUniqueId().toString() : actorName;
        StringBuilder json = new StringBuilder(512).append('{')
                .append("\"schemaVersion\":\"1\",")
                .append("\"operationId\":").append(quoted(result.operationId().toString())).append(',')
                .append("\"actionCode\":").append(quoted(action.name())).append(',')
                .append("\"descriptionPtPt\":").append(quoted(action.descriptionPtPt)).append(',')
                .append("\"actorKind\":").append(quoted(player ? "PLAYER" : "COMMAND_SENDER")).append(',')
                .append("\"actorRef\":").append(quoted(actorRef)).append(',')
                .append("\"actorName\":").append(quoted(actorName)).append(',')
                .append("\"accepted\":").append(result.accepted()).append(',')
                .append("\"changed\":").append(result.changed());
        if (change == null) {
            json.append(",\"worldName\":null,\"worldId\":null,\"previousBlocksPerSecond\":null,")
                    .append("\"newBlocksPerSecond\":null");
        } else {
            json.append(",\"worldName\":").append(quoted(change.world().name()))
                    .append(",\"worldId\":").append(quoted(change.world().worldId().toString()))
                    .append(",\"previousBlocksPerSecond\":").append(speed(change.previousSpeed()))
                    .append(",\"newBlocksPerSecond\":").append(speed(change.newSpeed()));
        }
        return json.append('}').toString();
    }

    private void emitEncoded(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAXIMUM_ENVELOPE_BYTES) {
            throw new IllegalArgumentException("A prova de auditoria dos carrinhos excede 2048 bytes.");
        }
        logger.info(PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    private static String speed(java.util.Optional<Double> value) {
        return value.map(number -> String.format(Locale.ROOT, "%.3f", number)).orElse("null");
    }

    private static String bounded(String value, int maximum) {
        String candidate = Objects.requireNonNullElse(value, "desconhecido").strip()
                .replace('\n', ' ').replace('\r', ' ');
        if (candidate.isEmpty()) candidate = "desconhecido";
        return candidate.length() <= maximum ? candidate : candidate.substring(0, maximum);
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
                    if (character < 0x20) escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.append('"').toString();
    }

    public enum Action {
        SET_WORLD("Definiu uma velocidade temporária para um mundo."),
        SET_DEFAULT("Definiu a velocidade temporária predefinida."),
        CLEAR_WORLD("Repôs a substituição temporária de um mundo."),
        CLEAR_ALL("Apagou todas as substituições temporárias."),
        RELOAD_CLEAR("Recarregou o ficheiro e apagou as substituições temporárias.");

        private final String descriptionPtPt;

        Action(String descriptionPtPt) { this.descriptionPtPt = descriptionPtPt; }
    }

}
