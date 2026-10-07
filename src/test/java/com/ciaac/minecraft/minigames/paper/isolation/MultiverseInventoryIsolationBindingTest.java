package com.ciaac.minecraft.minigames.paper.isolation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.runtime.PlayerSession;
import com.ciaac.minecraft.minigames.runtime.SessionPhase;
import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class MultiverseInventoryIsolationBindingTest {
    @Test
    void cancelsShareEventForRecoverySessionButLeavesOrdinaryPlayersAlone() throws Exception {
        UUID isolatedId = UUID.randomUUID();
        PlayerSession session = session(isolatedId);
        session.transition(UUID.randomUUID(), SessionPhase.REQUESTED, SessionPhase.RECOVERING,
                Instant.parse("2026-10-07T10:00:00Z"), "RECOVERY");
        SessionRegistry sessions = new SessionRegistry();
        sessions.register(session);
        var binding = MultiverseInventoryIsolationBinding.EventBinding.create(FakeReadShareEvent.class);

        FakeReadShareEvent isolatedEvent = new FakeReadShareEvent(player(isolatedId));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        MultiverseInventoryIsolationBinding.dispatchShareEvent(binding, isolatedEvent, sessions, failure::set);
        assertTrue(isolatedEvent.isCancelled());
        assertNull(failure.get());

        UUID ordinaryId = UUID.randomUUID();
        FakeReadShareEvent ordinaryEvent = new FakeReadShareEvent(player(ordinaryId));
        MultiverseInventoryIsolationBinding.dispatchShareEvent(binding, ordinaryEvent, sessions, failure::set);
        assertFalse(ordinaryEvent.isCancelled());
        assertNull(failure.get());
    }

    @Test
    void failsClosedWhenLoadedEventBridgeCannotReturnNativePlayer() throws Exception {
        SessionRegistry sessions = new SessionRegistry();
        var binding = MultiverseInventoryIsolationBinding.EventBinding.create(FakeReadShareEvent.class);
        FakeReadShareEvent event = new FakeReadShareEvent(null);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        MultiverseInventoryIsolationBinding.dispatchShareEvent(binding, event, sessions, failure::set);

        assertTrue(event.isCancelled());
        assertTrue(failure.get() instanceof IllegalStateException);
    }

    @Test
    void providerLifecycleMustRemainTheInitiallyBoundKnownInstance() {
        Plugin expected = proxyPlugin();
        Plugin replacement = proxyPlugin();

        assertTrue(MultiverseInventoryIsolationBinding.providerIntact(expected, expected, true, "5.3.5", 4));
        assertFalse(MultiverseInventoryIsolationBinding.providerIntact(expected, replacement, true, "5.3.5", 4));
        assertFalse(MultiverseInventoryIsolationBinding.providerIntact(expected, expected, false, "5.3.5", 4));
        assertFalse(MultiverseInventoryIsolationBinding.providerIntact(expected, expected, true, "5.3.6", 4));
        assertFalse(MultiverseInventoryIsolationBinding.providerIntact(expected, expected, true, "5.3.5", 3));
    }

    @Test
    void bridgeAcceptsIndependentCancellableEventHandlerLists() throws Exception {
        var read = MultiverseInventoryIsolationBinding.EventBinding.create(FakeReadShareEvent.class);
        var write = MultiverseInventoryIsolationBinding.EventBinding.create(FakeWriteShareEvent.class);
        assertNotSame(read.handlerList, write.handlerList);
        assertDoesNotThrow(() -> MultiverseInventoryIsolationBinding.EventBinding.create(FakeReadShareEvent.class));
    }

    @Test
    void bridgeRejectsAnEventWithoutCancellableContract() {
        assertThrows(IllegalStateException.class,
                () -> MultiverseInventoryIsolationBinding.EventBinding.create(NonCancellableEvent.class));
    }

    private static PlayerSession session(UUID playerId) {
        return new PlayerSession(UUID.randomUUID(), UUID.randomUUID(), playerId,
                GameKey.ARENA, Instant.parse("2026-10-07T10:00:00Z"));
    }

    private static Player player(UUID id) {
        return (Player) java.lang.reflect.Proxy.newProxyInstance(Player.class.getClassLoader(),
                new Class<?>[] {Player.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "toString" -> "fixture-player";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    private static Plugin proxyPlugin() {
        return (Plugin) java.lang.reflect.Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[] {Plugin.class}, (proxy, method, args) -> null);
    }

    public static final class FakeReadShareEvent extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private final Player player;
        private boolean cancelled;

        public FakeReadShareEvent(Player player) { this.player = player; }
        public Player getPlayer() { return player; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    public static final class FakeWriteShareEvent extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        @Override public boolean isCancelled() { return false; }
        @Override public void setCancelled(boolean cancelled) { }
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
        public Player getPlayer() { return player(UUID.randomUUID()); }
    }

    public static final class NonCancellableEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
        public Player getPlayer() { return player(UUID.randomUUID()); }
    }
}
