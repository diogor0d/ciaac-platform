package com.ciaac.minecraft.minigames.paper.configuration;

import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.Material;

/** Package-local defensive-copy and scalar validation helpers for resolved records. */
final class ResolvedConfigurationSupport {
    private ResolvedConfigurationSupport() {}

    static Map<String, Location> locations(Map<String, Location> values) {
        Objects.requireNonNull(values, "locations");
        LinkedHashMap<String, Location> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(text(key, "location id", 64), Objects.requireNonNull(value, "location").clone()));
        return Map.copyOf(copy);
    }

    static Map<String, CuboidRegion> regions(Map<String, CuboidRegion> values) {
        Objects.requireNonNull(values, "regions");
        LinkedHashMap<String, CuboidRegion> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(text(key, "region id", 64), Objects.requireNonNull(value, "region")));
        return Map.copyOf(copy);
    }

    static List<Location> locationList(List<Location> values) {
        Objects.requireNonNull(values, "locations");
        return values.stream().map(value -> Objects.requireNonNull(value, "location").clone()).toList();
    }

    static Map<String, List<Material>> materialLists(Map<String, List<Material>> values) {
        Objects.requireNonNull(values, "material lists");
        LinkedHashMap<String, List<Material>> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(text(key, "material list id", 64), List.copyOf(Objects.requireNonNull(value, "material list"))));
        return Map.copyOf(copy);
    }

    static List<String> ids(List<String> values, String name, int max) {
        Objects.requireNonNull(values, name);
        if (values.isEmpty() || values.size() > max) throw new IllegalArgumentException(name + " is empty or too large");
        List<String> copy = new ArrayList<>();
        for (String value : values) {
            String id = text(value, name, 64);
            if (!id.matches("[a-z0-9][a-z0-9_.-]{0,63}")) throw new IllegalArgumentException(name + " contains an invalid id");
            if (copy.contains(id)) throw new IllegalArgumentException(name + " contains duplicate ids");
            copy.add(id);
        }
        return List.copyOf(copy);
    }

    static String text(String value, String name, int max) {
        Objects.requireNonNull(value, name);
        String trimmed = value.trim();
        if (trimmed.isBlank() || trimmed.length() > max || trimmed.contains("__SET_ME__")) {
            throw new IllegalArgumentException(name + " is unresolved or out of bounds");
        }
        return trimmed;
    }

    static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    static Duration nonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }
}
