package com.ciaac.minecraft.minigames.display;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public record NativeDisplayConfig(boolean enabled, Duration defaultRefresh, List<NativeDisplayEntry> entries) {
    public NativeDisplayConfig {
        if (defaultRefresh == null || defaultRefresh.isNegative() || defaultRefresh.isZero() || defaultRefresh.compareTo(Duration.ofMinutes(10)) > 0) throw new IllegalArgumentException("defaultRefresh is invalid");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (NativeDisplayEntry entry : entries) if (!ids.add(entry.id())) throw new IllegalArgumentException("duplicate display id " + entry.id());
    }
    public static NativeDisplayConfig disabled() { return new NativeDisplayConfig(false, Duration.ofSeconds(5), List.of()); }
}
