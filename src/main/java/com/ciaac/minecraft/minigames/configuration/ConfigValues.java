package com.ciaac.minecraft.minigames.configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable flattened configuration view for module-specific adapters. */
public final class ConfigValues {
    private final Map<String, Object> values;

    public ConfigValues(Map<String, Object> values) {
        Objects.requireNonNull(values, "values");
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(
                Objects.requireNonNull(key, "configuration key"), immutableValue(value)));
        this.values = Map.copyOf(copy);
    }

    public Optional<String> string(String path) {
        Object value = values.get(path);
        return value instanceof String text ? Optional.of(text) : Optional.empty();
    }

    public Optional<Integer> integer(String path) {
        Object value = values.get(path);
        return value instanceof Number number ? Optional.of(number.intValue()) : Optional.empty();
    }

    public Optional<Long> longValue(String path) {
        Object value = values.get(path);
        return value instanceof Number number ? Optional.of(number.longValue()) : Optional.empty();
    }

    public Optional<Double> decimal(String path) {
        Object value = values.get(path);
        return value instanceof Number number ? Optional.of(number.doubleValue()) : Optional.empty();
    }

    public Optional<Boolean> bool(String path) {
        Object value = values.get(path);
        return value instanceof Boolean flag ? Optional.of(flag) : Optional.empty();
    }

    public List<String> strings(String path) {
        Object value = values.get(path);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    public Map<String, Object> all() {
        return values;
    }

    private static Object immutableValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof List<?> list) {
            return List.copyOf(new ArrayList<>(list));
        }
        return value.toString();
    }
}
