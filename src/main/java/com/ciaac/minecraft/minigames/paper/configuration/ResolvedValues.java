package com.ciaac.minecraft.minigames.paper.configuration;

import com.ciaac.minecraft.minigames.configuration.ConfigValues;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.bukkit.Material;

/**
 * Typed, bounded view over one module's flattened values. Optional getters do
 * not report absent optional keys; required getters do.
 */
public final class ResolvedValues {
    private static final int MAX_DIAGNOSTICS = 512;
    private final ConfigValues values;
    private final String root;
    private final List<ResolutionDiagnostic> diagnostics = new ArrayList<>();

    public ResolvedValues(ConfigValues values, String root) {
        this.values = Objects.requireNonNull(values, "values");
        this.root = boundedPath(root);
    }

    public Map<String, Object> all() {
        return values.all();
    }

    public Object raw(String path) {
        return values.all().get(relativePath(path));
    }

    public List<ResolutionDiagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    public Optional<String> string(String path, int maximumLength) {
        Object raw = raw(path);
        if (raw == null) return Optional.empty();
        if (!(raw instanceof String value)) {
            invalid(path, "VALUE_TYPE_INVALID", "O valor tem de ser texto.");
            return Optional.empty();
        }
        if (value.isBlank() || value.length() > maximumLength) {
            invalid(path, "STRING_OUT_OF_RANGE", "O texto não pode estar vazio nem exceder o limite permitido.");
            return Optional.empty();
        }
        if (containsPlaceholder(value)) {
            invalid(path, "PLACEHOLDER_UNRESOLVED", "A configuração ainda contém um marcador __SET_ME__.");
            return Optional.empty();
        }
        return Optional.of(value.trim());
    }

    public String requiredString(String path, int maximumLength) {
        return string(path, maximumLength).orElseThrow(() -> missing(path));
    }

    public Optional<Boolean> bool(String path) {
        Object raw = raw(path);
        if (raw == null) return Optional.empty();
        if (!(raw instanceof Boolean value)) {
            invalid(path, "VALUE_TYPE_INVALID", "O valor tem de ser booleano.");
            return Optional.empty();
        }
        return Optional.of(value);
    }

    public Optional<Boolean> booleanValue(String path) {
        return bool(path);
    }

    public boolean requiredBool(String path) {
        return bool(path).orElseThrow(() -> missing(path));
    }

    public OptionalInt integer(String path, int minimum, int maximum) {
        Object raw = raw(path);
        if (raw == null) return OptionalInt.empty();
        if (!(raw instanceof Number number) || !whole(number)) {
            invalid(path, "VALUE_TYPE_INVALID", "O valor tem de ser um número inteiro.");
            return OptionalInt.empty();
        }
        long value = number.longValue();
        if (value < minimum || value > maximum) {
            invalid(path, "NUMBER_OUT_OF_RANGE", "O valor está fora dos limites permitidos.");
            return OptionalInt.empty();
        }
        return OptionalInt.of((int) value);
    }

    public OptionalInt intValue(String path, int minimum, int maximum) {
        return integer(path, minimum, maximum);
    }

    public int requiredInteger(String path, int minimum, int maximum) {
        return integer(path, minimum, maximum).orElseThrow(() -> missing(path));
    }

    public OptionalLong longValue(String path, long minimum, long maximum) {
        Object raw = raw(path);
        if (raw == null) return OptionalLong.empty();
        if (!(raw instanceof Number number) || !whole(number)) {
            invalid(path, "VALUE_TYPE_INVALID", "O valor tem de ser um número inteiro.");
            return OptionalLong.empty();
        }
        long value = number.longValue();
        if (value < minimum || value > maximum) {
            invalid(path, "NUMBER_OUT_OF_RANGE", "O valor está fora dos limites permitidos.");
            return OptionalLong.empty();
        }
        return OptionalLong.of(value);
    }

    public long requiredLong(String path, long minimum, long maximum) {
        return longValue(path, minimum, maximum).orElseThrow(() -> missing(path));
    }

    public OptionalDouble decimal(String path, double minimum, double maximum) {
        Object raw = raw(path);
        if (raw == null) return OptionalDouble.empty();
        if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            invalid(path, "VALUE_TYPE_INVALID", "O valor tem de ser um número finito.");
            return OptionalDouble.empty();
        }
        double value = number.doubleValue();
        if (value < minimum || value > maximum) {
            invalid(path, "NUMBER_OUT_OF_RANGE", "O valor está fora dos limites permitidos.");
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(value);
    }

    public OptionalDouble doubleValue(String path, double minimum, double maximum) {
        return decimal(path, minimum, maximum);
    }

    public double requiredDecimal(String path, double minimum, double maximum) {
        return decimal(path, minimum, maximum).orElseThrow(() -> missing(path));
    }

    public Optional<Duration> durationSeconds(String path, double minimum, double maximum) {
        OptionalDouble seconds = decimal(path, minimum, maximum);
        if (seconds.isEmpty()) return Optional.empty();
        try {
            long millis = BigDecimal.valueOf(seconds.orElseThrow())
                    .movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValueExact();
            return Optional.of(Duration.ofMillis(millis));
        } catch (ArithmeticException exception) {
            invalid(path, "DURATION_INVALID", "A duração não é representável com segurança.");
            return Optional.empty();
        }
    }

    public Duration requiredDurationSeconds(String path, double minimum, double maximum) {
        return durationSeconds(path, minimum, maximum).orElseThrow(() -> missing(path));
    }

    public Optional<Duration> durationMilliseconds(String path, long minimum, long maximum) {
        OptionalLong milliseconds = longValue(path, minimum, maximum);
        return milliseconds.isEmpty() ? Optional.empty() : Optional.of(Duration.ofMillis(milliseconds.orElseThrow()));
    }

    public Duration requiredDurationMilliseconds(String path, long minimum, long maximum) {
        return durationMilliseconds(path, minimum, maximum).orElseThrow(() -> missing(path));
    }

    public Optional<Duration> durationTicks(String path, long minimum, long maximum) {
        OptionalLong ticks = longValue(path, minimum, maximum);
        if (ticks.isEmpty()) return Optional.empty();
        try {
            return Optional.of(Duration.ofMillis(Math.multiplyExact(ticks.orElseThrow(), 50)));
        } catch (ArithmeticException exception) {
            invalid(path, "DURATION_INVALID", "A duração não é representável com segurança.");
            return Optional.empty();
        }
    }

    public Duration requiredDurationTicks(String path, long minimum, long maximum) {
        return durationTicks(path, minimum, maximum).orElseThrow(() -> missing(path));
    }

    public List<String> stringList(String path, int maximumItems, int maximumLength) {
        Object raw = raw(path);
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list) || list.size() > maximumItems) {
            invalid(path, "LIST_INVALID", "A lista tem um tipo ou dimensão inválida.");
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank() || text.length() > maximumLength
                    || containsPlaceholder(text)) {
                invalid(path, containsPlaceholder(String.valueOf(item)) ? "PLACEHOLDER_UNRESOLVED" : "LIST_ITEM_INVALID",
                        "A lista contém um item inválido ou por configurar.");
                return List.of();
            }
            result.add(text.trim());
        }
        return List.copyOf(result);
    }

    public List<String> requiredStringList(String path, int maximumItems, int maximumLength) {
        if (raw(path) == null) throw missing(path);
        return stringList(path, maximumItems, maximumLength);
    }

    public List<Integer> integerList(String path, int maximumItems, int minimum, int maximum) {
        Object raw = raw(path);
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list) || list.size() > maximumItems) {
            invalid(path, "LIST_INVALID", "A lista tem um tipo ou dimensão inválida.");
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Number number) || !whole(number)) {
                invalid(path, "LIST_ITEM_INVALID", "A lista contém um inteiro inválido.");
                return List.of();
            }
            long value = number.longValue();
            if (value < minimum || value > maximum) {
                invalid(path, "NUMBER_OUT_OF_RANGE", "A lista contém um valor fora dos limites permitidos.");
                return List.of();
            }
            result.add((int) value);
        }
        return List.copyOf(result);
    }

    public List<Integer> requiredIntegerList(String path, int maximumItems, int minimum, int maximum) {
        if (raw(path) == null) throw missing(path);
        return integerList(path, maximumItems, minimum, maximum);
    }

    public List<Material> materials(String path, int maximumItems) {
        List<String> names = stringList(path, maximumItems, 128);
        if (raw(path) != null && names.isEmpty()) return List.of();
        List<Material> result = new ArrayList<>();
        for (String name : names) {
            Material material = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
            if (material == null) {
                invalid(path, "MATERIAL_UNKNOWN", "A lista contém um material desconhecido.");
                return List.of();
            }
            result.add(material);
        }
        return List.copyOf(result);
    }

    public <T extends Enum<T>> Optional<T> enumValue(String path, Class<T> type) {
        Optional<String> text = string(path, 128);
        if (text.isEmpty()) return Optional.empty();
        String normalized = text.orElseThrow().trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return Optional.of(Enum.valueOf(type, normalized));
        } catch (IllegalArgumentException exception) {
            invalid(path, "ENUM_UNKNOWN", "O valor não pertence às opções permitidas.");
            return Optional.empty();
        }
    }

    public <T extends Enum<T>> T requiredEnum(String path, Class<T> type) {
        return enumValue(path, type).orElseThrow(() -> missing(path));
    }

    private IllegalArgumentException missing(String path) {
        invalid(path, "MISSING_KEY", "Falta uma opção obrigatória.");
        return new IllegalArgumentException("Missing required configuration key: " + path(path));
    }

    private void invalid(String path, String code, String message) {
        if (diagnostics.size() < MAX_DIAGNOSTICS) diagnostics.add(new ResolutionDiagnostic(code, path(path), message));
    }

    private String path(String path) {
        String full = root + "." + relativePath(path);
        if (full.length() > 256) throw new IllegalArgumentException("Configuration path is too long");
        return full;
    }

    private static String relativePath(String path) {
        if (path == null || path.isBlank() || path.length() > 256 || path.contains("..")) {
            throw new IllegalArgumentException("Configuration path is invalid");
        }
        return path;
    }

    private static String boundedPath(String value) {
        Objects.requireNonNull(value, "root");
        String trimmed = value.trim();
        if (trimmed.isBlank() || trimmed.length() > 128) throw new IllegalArgumentException("root is invalid");
        return trimmed;
    }

    private static boolean whole(Number number) {
        double value = number.doubleValue();
        return Double.isFinite(value) && Math.rint(value) == value
                && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE;
    }

    private static boolean containsPlaceholder(String value) {
        return value != null && value.contains("__SET_ME__");
    }
}
