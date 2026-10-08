package com.ciaac.minecraft.minigames.paper.isolation;

import static org.junit.jupiter.api.Assertions.*;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.anvildodge.AnvilDodgeConfig;
import com.ciaac.minecraft.minigames.isolation.PlayerStateOperation;
import com.ciaac.minecraft.minigames.paper.configuration.*;
import com.ciaac.minecraft.minigames.persistence.*;
import com.ciaac.minecraft.minigames.region.*;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnvilWorldStateTest {
    @TempDir Path temporary;
    private final UUID worldId = UUID.randomUUID(), matchId = UUID.randomUUID();
    private boolean loaded = true;
    private static Object zero(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        return null;
    }
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
    private PlayerStateOperation capture(int id) {
        UUID op = new UUID(0, id);
        return new PlayerStateOperation(PlayerStateOperation.Kind.CAPTURE, op, op, new UUID(1,id),
                new UUID(2,id), matchId, new UUID(3,id), new UUID(4,id), new UUID(4,id),
                GameKey.ANVIL_DODGE, Instant.parse("2026-10-07T12:00:00Z"));
    }
    private PlayerStateOperation phase(PlayerStateOperation c, PlayerStateOperation.Kind kind) {
        return new PlayerStateOperation(kind, UUID.randomUUID(), c.captureOperationId(), c.snapshotId(),
                c.sessionId(), c.matchId(), c.playerId(), c.capturedConnectionId(), c.capturedConnectionId(), c.game(), c.capturedAt());
    }
    @Test void sharedColdMarkersArePurgedBeforeEitherPlayerCanRestore() {
        World world = proxy(World.class, (p,m,a) -> switch(m.getName()) {
            case "getUID" -> worldId;
            case "getName" -> "synthetic-anvil";
            case "getMinHeight" -> -64;
            case "getMaxHeight" -> 320;
            case "isChunkLoaded" -> loaded;
            default -> zero(m.getReturnType());
        });
        Server server = proxy(Server.class, (p,m,a) -> switch(m.getName()) {
            case "isPrimaryThread" -> true;
            case "getVersion" -> "Paper fixture";
            case "getWorld" -> worldId.equals(a[0]) ? world : null;
            default -> zero(m.getReturnType());
        });
        Plugin plugin = proxy(Plugin.class, (p,m,a) -> switch(m.getName()) {
            case "getServer" -> server;
            case "namespace" -> "ciaacplatform";
            default -> zero(m.getReturnType());
        });
        var floor = new CuboidRegion(worldId, 160,79,0,167,79,7);
        var boundary = new CuboidRegion(worldId,160,-64,0,167,319,7);
        var regions = new ProtectedRegionRegistry();
        regions.register(new ProtectedRegion("anvil-dodge.boundary",GameKey.ANVIL_DODGE,boundary,ProtectedRegionRole.PARTICIPANT_ONLY,true));
        var config = new ResolvedAnvilDodgeConfiguration(world,
                Map.of("start",new Location(world,161.5,80,4.5),"exit",new Location(world,-5.5,80,4.5)),
                Map.of("floor",floor,"boundary",boundary),"test-v1",
                new AnvilDodgeConfig(2,4,2,Duration.ofSeconds(4),Duration.ofSeconds(8),17,"test-v1"),
                1,0,8,8,TemplateResolution.readyForAdapter("tagged-anvil-hazard"));
        try (var ledger = new ArenaWorldLedger(temporary.resolve("ledger"));
             var journal = new ExternalOperationJournal(temporary.resolve("journal"))) {
            var state = new AnvilWorldState("fixture-world",server,regions,ledger,
                    new AnvilHazardOwnership(plugin,ledger),journal,event -> true,()->config);
            var first = capture(1); var second = capture(2);
            byte[] one = state.capture(first), two = state.capture(second);
            state.enter(phase(first,PlayerStateOperation.Kind.ENTER));
            state.enter(phase(second,PlayerStateOperation.Kind.ENTER));
            var current = NativeProcessIdentity.current();
            var old = new NativeProcessIdentity(current.pid()+1,current.startedAtEpochMillis());
            var a = UUID.randomUUID(); var b = UUID.randomUUID();
            ledger.beginEntity(first,a,worldId,ArenaWorldLedger.EntityType.ANVIL_MARKER,old);
            ledger.beginEntity(second,b,worldId,ArenaWorldLedger.EntityType.ANVIL_MARKER,old);
            assertThrows(IllegalStateException.class,()->state.validate(phase(first,PlayerStateOperation.Kind.RESTORE),1,one));
            loaded=false;
            assertThrows(IllegalStateException.class,()->state.purge(phase(first,PlayerStateOperation.Kind.PURGE)));
            assertEquals(ArenaWorldLedger.EntityStatus.PENDING,ledger.findEntity(a).orElseThrow().status());
            assertEquals(ArenaWorldLedger.EntityStatus.PENDING,ledger.findEntity(b).orElseThrow().status());
            loaded=true;
            state.purge(phase(first,PlayerStateOperation.Kind.PURGE));
            assertEquals(ArenaWorldLedger.EntityStatus.REMOVED,ledger.findEntity(a).orElseThrow().status());
            assertEquals(ArenaWorldLedger.EntityStatus.REMOVED,ledger.findEntity(b).orElseThrow().status());
            state.restore(phase(first,PlayerStateOperation.Kind.RESTORE),1,one);
            state.purge(phase(second,PlayerStateOperation.Kind.PURGE));
            state.restore(phase(second,PlayerStateOperation.Kind.RESTORE),1,two);
            assertEquals(ArenaWorldLedger.Status.RESTORED,ledger.requireLease(first).status());
            assertEquals(ArenaWorldLedger.Status.RESTORED,ledger.requireLease(second).status());
        }
    }
}
