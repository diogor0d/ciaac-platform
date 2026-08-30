package com.ciaac.minecraft.minigames.display;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Paper-free loader for sanitized YAML-like maps; malformed entries stay closed. */
public final class NativeDisplayConfigLoader {
    public DisplayConfigLoadResult load(Map<String, ?> root) {
        List<DisplayDiagnostic> diagnostics = new ArrayList<>();
        if (root == null) return new DisplayConfigLoadResult(NativeDisplayConfig.disabled(), List.of(new DisplayDiagnostic("config", "CONFIG_ROOT_INVALID", "A configuração dos displays não é um mapa válido.")));
        boolean enabled = booleanValue(root.containsKey("enabled") ? root.get("enabled") : Boolean.FALSE, false, diagnostics, "config");
        int diagnosticsBeforeRefresh = diagnostics.size();
        Object refreshValue = root.containsKey("refresh-seconds")
                ? root.get("refresh-seconds")
                : ticksToSeconds(root.get("refresh-ticks"), 5);
        Duration defaultRefresh = duration(refreshValue, Duration.ofSeconds(5), diagnostics, "config");
        if (diagnostics.size() != diagnosticsBeforeRefresh) enabled = false;
        List<NativeDisplayEntry> entries = new ArrayList<>();
        Object rawEntries = root.get("entries");
        if (rawEntries instanceof Map<?, ?> keyedEntries) {
            for (Map.Entry<?, ?> keyed : keyedEntries.entrySet()) {
                String fallbackId = text(keyed.getKey(), "entry");
                if (!(keyed.getValue() instanceof Map<?, ?> value)) {
                    diagnostics.add(new DisplayDiagnostic(
                            safeId(fallbackId), "ENTRY_INVALID", "A entrada do display não é um mapa válido."));
                    continue;
                }
                java.util.LinkedHashMap<Object, Object> withId = new java.util.LinkedHashMap<>(value);
                withId.putIfAbsent("id", fallbackId);
                parseEntry(withId, defaultRefresh, entries, diagnostics, fallbackId);
            }
        } else if (rawEntries instanceof Iterable<?> values) {
            int index = 0;
            for (Object value : values) {
                String fallbackId = "entry-" + index++;
                if (!(value instanceof Map<?, ?> map)) { diagnostics.add(new DisplayDiagnostic(fallbackId, "ENTRY_INVALID", "A entrada do display não é um mapa válido.")); continue; }
                parseEntry(map, defaultRefresh, entries, diagnostics, fallbackId);
            }
        } else if (rawEntries != null) {
            diagnostics.add(new DisplayDiagnostic("config", "ENTRIES_INVALID", "A lista de displays não é válida."));
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<NativeDisplayEntry> uniqueEntries = new ArrayList<>();
        for (NativeDisplayEntry entry : entries) {
            if (!seen.add(entry.id())) diagnostics.add(new DisplayDiagnostic(entry.id(), "DUPLICATE_ENTRY", "O ID deste display está repetido."));
            else uniqueEntries.add(entry);
        }
        entries = uniqueEntries;
        try { return new DisplayConfigLoadResult(new NativeDisplayConfig(enabled, defaultRefresh, entries), diagnostics); }
        catch (RuntimeException failure) { diagnostics.add(new DisplayDiagnostic("config", "CONFIG_INVALID", "A configuração dos displays foi fechada por validação.")); return new DisplayConfigLoadResult(NativeDisplayConfig.disabled(), diagnostics); }
    }

    private void parseEntry(Map<?, ?> map, Duration fallbackRefresh, List<NativeDisplayEntry> output, List<DisplayDiagnostic> diagnostics, String fallbackId) {
        String id = text(map.get("id"), fallbackId); try {
            GameKey game = GameKey.fromId(text(map.get("game"), "")).orElseThrow();
            UUID worldId = UUID.fromString(text(map.get("world-id"), ""));
            DisplayWorldIdentity world = new DisplayWorldIdentity(worldId, text(map.get("world-name"), ""));
            DisplayLocation location = new DisplayLocation(number(map.get("x")), number(map.get("y")), number(map.get("z")), (float) number(valueOr(map, "yaw", 0)), (float) number(valueOr(map, "pitch", 0)));
            DisplayDimensions dimensions = new DisplayDimensions((float) number(valueOr(map, "width", 1)), (float) number(valueOr(map, "height", 1)));
            int diagnosticsBeforeRefresh = diagnostics.size();
            Duration refresh = map.containsKey("refresh-seconds") ? duration(map.get("refresh-seconds"), fallbackRefresh, diagnostics, id) : fallbackRefresh;
            if (diagnostics.size() != diagnosticsBeforeRefresh) return;
            String command = text(map.get("join-command"), canonicalJoin(game));
            output.add(new NativeDisplayEntry(id, game, world, location, dimensions, refresh, command));
        } catch (RuntimeException failure) { diagnostics.add(new DisplayDiagnostic(safeId(id), "ENTRY_INVALID", "A entrada do display foi fechada por validação.")); }
    }

    private static String canonicalJoin(GameKey key) { return switch (key) {
        case ARENA -> "/coliseu entrar"; case BUILD_BATTLE -> "/buildbattle entrar"; case HOT_POTATO -> "/batataquente entrar"; case KNOCKBACK_SUMO -> "/sumo entrar"; case CHECKPOINT_PARKOUR -> "/parkour entrar"; case ARCHERY_RANGE -> "/arco entrar"; case ANVIL_DODGE -> "/bigornas entrar"; case COLOR_FLOOR -> "/cores entrar"; case ELYTRA_RINGS -> "/elytra entrar"; };
    }
    private static String text(Object value, String fallback) { return value == null ? fallback : String.valueOf(value).trim(); }
    private static Object valueOr(Map<?, ?> map, String key, Object fallback) { return map.containsKey(key) ? map.get(key) : fallback; }
    private static Object ticksToSeconds(Object value, double fallbackSeconds) {
        if (value == null) return fallbackSeconds;
        if (!(value instanceof Number number)) return value;
        return number.doubleValue() / 20.0;
    }
    private static String safeId(String id) { return id == null || id.isBlank() ? "entry" : id.length() > 64 ? id.substring(0, 64) : id; }
    private static double number(Object value) { if (!(value instanceof Number n)) throw new IllegalArgumentException("number required"); double result = n.doubleValue(); if (!Double.isFinite(result)) throw new IllegalArgumentException("finite number required"); return result; }
    private static Duration duration(Object value, Duration fallback, List<DisplayDiagnostic> diagnostics, String id) { try { double seconds = number(value); if (seconds <= 0 || seconds > 600) throw new IllegalArgumentException(); return Duration.ofMillis((long) (seconds * 1000)); } catch (RuntimeException failure) { diagnostics.add(new DisplayDiagnostic(safeId(id), "REFRESH_INVALID", "O intervalo de atualização do display é inválido.")); return fallback; } }
    private static boolean booleanValue(Object value, boolean fallback, List<DisplayDiagnostic> diagnostics, String id) { if (value instanceof Boolean result) return result; diagnostics.add(new DisplayDiagnostic(id, "ENABLED_INVALID", "O estado ativo dos displays é inválido.")); return fallback; }
}
