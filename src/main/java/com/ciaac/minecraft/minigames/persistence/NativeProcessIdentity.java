package com.ciaac.minecraft.minigames.persistence;

import java.lang.management.ManagementFactory;

/** Identifies one native JVM lifetime, stable across plugin reloads in that process. */
public record NativeProcessIdentity(long pid, long startedAtEpochMillis) {
    public NativeProcessIdentity {
        if (pid <= 0) throw new IllegalArgumentException("pid must be positive");
        if (startedAtEpochMillis <= 0) throw new IllegalArgumentException("process start time must be positive");
    }

    public static NativeProcessIdentity current() {
        long pid = ProcessHandle.current().pid();
        long startedAt = ManagementFactory.getRuntimeMXBean().getStartTime();
        return new NativeProcessIdentity(pid, startedAt);
    }
}
