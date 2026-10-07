package com.ciaac.minecraft.minigames.paper.isolation;

import com.ciaac.minecraft.minigames.runtime.SessionRegistry;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Server;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.entity.Player;

/** Optional, version-pinned cancellation boundary for Multiverse-Inventories sharing. */
public final class MultiverseInventoryIsolationBinding {
    private static final String PLUGIN_NAME = "Multiverse-Inventories";
    private static final String PLUGIN_CLASS = "org.mvplugins.multiverse.inventories.MultiverseInventories";
    private static final String VERSION = "5.3.5";
    private static final String EVENT_PREFIX = "org.mvplugins.multiverse.inventories.event.";
    private static final Map<String, String> CRITICAL_CLASS_SHA256 = Map.of(
            "org/mvplugins/multiverse/inventories/handleshare/ShareHandler.class",
            "a342ef60c0aa80d5e401ec9261be6122c4668130da6850eab1016bea06bd63da",
            "org/mvplugins/multiverse/inventories/event/ShareHandlingEvent.class",
            "dce3e84ffd4e18b38d6318a0fa95ad261709ca68de1537cffa441f7df225ae2c",
            "org/mvplugins/multiverse/inventories/event/WorldChangeShareHandlingEvent.class",
            "b59170d5ef1cb1993e3a385ebb5873cfbbfeeaa36afeb673f9007098adb114ee",
            "org/mvplugins/multiverse/inventories/event/GameModeChangeShareHandlingEvent.class",
            "d711e4308045ee6136dd8879135464a8308c58a2e6435cbd15ea8aa61c5a0479",
            "org/mvplugins/multiverse/inventories/event/ReadOnlyShareHandlingEvent.class",
            "e529fbe2e7ab28911ace4f81171b91382d4373293075d26ce5f89074606ac817",
            "org/mvplugins/multiverse/inventories/event/WriteOnlyShareHandlingEvent.class",
            "a4b815add20d98f57eff43aa17d5ba653419a781b340afde820ecda6c34c7b61");
    private static final List<String> EVENT_NAMES = List.of(
            "WorldChangeShareHandlingEvent",
            "GameModeChangeShareHandlingEvent",
            "ReadOnlyShareHandlingEvent",
            "WriteOnlyShareHandlingEvent");

    private MultiverseInventoryIsolationBinding() {}

    public static Guard register(Plugin owner, SessionRegistry sessions) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(sessions, "sessions");
        if (!owner.getServer().isPrimaryThread()) {
            throw new IllegalStateException("MVI isolation binding must be registered on the main thread");
        }
        Guard guard = new Guard(owner, sessions);
        owner.getServer().getPluginManager().registerEvents(guard, owner);
        Plugin provider = owner.getServer().getPluginManager().getPlugin(PLUGIN_NAME);
        if (provider != null) guard.bindInitial(provider);
        return guard;
    }

    public static final class Guard implements Listener {
        private final Plugin owner;
        private final Server server;
        private final PluginManager plugins;
        private final SessionRegistry sessions;
        private final Map<Class<?>, EventBinding> bindings = new LinkedHashMap<>();
        private Plugin provider;
        private boolean unhealthy;
        private String failure = "";

        private Guard(Plugin owner, SessionRegistry sessions) {
            this.owner = owner;
            this.server = owner.getServer();
            this.plugins = server.getPluginManager();
            this.sessions = sessions;
        }

        private void bindInitial(Plugin selected) {
            if (provider != null || unhealthy) return;
            provider = selected;
            try {
                if (!PLUGIN_NAME.equals(selected.getName())
                        || !selected.getClass().getName().equals(PLUGIN_CLASS)
                        || !VERSION.equals(selected.getDescription().getVersion())
                        || !selected.isEnabled()) {
                    throw new IllegalStateException("Unsupported or unavailable Multiverse-Inventories provider");
                }
                ClassLoader loader = selected.getClass().getClassLoader();
                verifyCriticalClasses(loader);
                List<EventBinding> found = new ArrayList<>();
                for (String name : EVENT_NAMES) {
                    Class<?> eventType = Class.forName(EVENT_PREFIX + name, false, loader);
                    found.add(EventBinding.create(eventType));
                }
                if (found.stream().map(binding -> binding.handlerList).distinct().count() != found.size()) {
                    throw new IllegalStateException("Multiverse-Inventories share events must have separate HandlerLists");
                }
                for (EventBinding binding : found) {
                    @SuppressWarnings("unchecked")
                    Class<? extends Event> eventType = (Class<? extends Event>) binding.eventType;
                    EventExecutor executor = (listener, event) -> onShareEvent(binding, event);
                    plugins.registerEvent(eventType, this, EventPriority.LOWEST, executor, owner, false);
                    bindings.put(binding.eventType, binding);
                }
            } catch (Exception | LinkageError error) {
                markUnhealthy("Multiverse-Inventories 5.3.5 share binding is unavailable", error);
            }
        }

        private void verifyCriticalClasses(ClassLoader loader) throws Exception {
            for (var expected : CRITICAL_CLASS_SHA256.entrySet()) {
                Class<?> type = Class.forName(expected.getKey().replace('/', '.').replace(".class", ""), false, loader);
                if (type.getClassLoader() != loader) throw new IllegalStateException("Share API class came from another class loader");
                String resource = expected.getKey();
                try (InputStream input = loader.getResourceAsStream(resource)) {
                    if (input == null) throw new IllegalStateException("Missing pinned share class " + resource);
                    String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
                    if (!expected.getValue().equals(actual)) throw new IllegalStateException("Unrecognized MVI class bytes: " + resource);
                }
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onProviderDisable(PluginDisableEvent event) {
            if (event.getPlugin() == provider && provider != null) {
                markUnhealthy("Multiverse-Inventories was disabled", null);
            }
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onProviderEnable(PluginEnableEvent event) {
            if (!PLUGIN_NAME.equals(event.getPlugin().getName())) return;
            if (provider == null || provider != event.getPlugin()) {
                markUnhealthy("Multiverse-Inventories appeared or was replaced after binding", null);
            }
        }

        public void preflight() {
            requireMainThread();
            if (unhealthy) throw new IllegalStateException(failure);
            Plugin current = plugins.getPlugin(PLUGIN_NAME);
            if (provider == null) {
                if (current != null) {
                    markUnhealthy("Multiverse-Inventories appeared after optional binding", null);
                    throw new IllegalStateException(failure);
                }
                return;
            }
            if (!providerIntact(provider, current, provider.isEnabled(), provider.getDescription().getVersion(), bindings.size())) {
                markUnhealthy("Multiverse-Inventories provider lifecycle or binding was lost", null);
                throw new IllegalStateException(failure);
            }
        }

        private void onShareEvent(EventBinding binding, Event event) throws EventException {
            requireMainThreadForEvent(binding, event);
            dispatchShareEvent(binding, event, sessions, error ->
                    markUnhealthy("Multiverse-Inventories event binding failed", error));
        }

        private void requireMainThreadForEvent(EventBinding binding, Event event) throws EventException {
            if (server.isPrimaryThread()) return;
            cancelFailClosed(binding, event);
            markUnhealthy("Multiverse-Inventories share event was observed off the server thread", null);
            throw new EventException(new IllegalStateException("MVI share events must run on the main thread"));
        }

        private void cancelFailClosed(EventBinding binding, Event event) {
            try {
                binding.cancelled.invoke(event, true);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // The contract's cancellation method itself is unavailable; preflight will stop new isolation work.
            }
        }

        private void requireMainThread() {
            if (!server.isPrimaryThread()) throw new IllegalStateException("MVI isolation preflight must run on the main thread");
        }

        private void markUnhealthy(String message, Throwable cause) {
            unhealthy = true;
            failure = cause == null ? message : message + ": " + cause.getClass().getSimpleName();
        }
    }

    static boolean shouldCancel(UUID playerId, SessionRegistry sessions) {
        return sessions.findByPlayer(Objects.requireNonNull(playerId, "playerId"))
                .map(session -> session.phase().isolationActive()).orElse(false);
    }

    static boolean providerIntact(Plugin expected, Plugin current, boolean enabled, String version, int bindings) {
        return expected != null && expected == current && enabled && VERSION.equals(version)
                && bindings == EVENT_NAMES.size();
    }

    static void applyIsolationPolicy(EventBinding binding, Event event, SessionRegistry sessions)
            throws ReflectiveOperationException {
        if (event.getClass() != binding.eventType) throw new IllegalArgumentException("Unexpected MVI event type");
        if (event.isAsynchronous()) throw new IllegalStateException("MVI share events must be synchronous");
        Object rawPlayer = binding.player.invoke(event);
        if (!(rawPlayer instanceof Player player)) throw new IllegalStateException("MVI event returned no native Bukkit player");
        if (shouldCancel(player.getUniqueId(), sessions)) binding.cancelled.invoke(event, true);
    }

    static void dispatchShareEvent(EventBinding binding, Event event, SessionRegistry sessions,
            Consumer<Throwable> bindingFailure) {
        try {
            applyIsolationPolicy(binding, event, sessions);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            try {
                binding.cancelled.invoke(event, true);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // A broken cancellation method cannot be repaired; the binding failure blocks future isolation.
            }
            bindingFailure.accept(error);
        }
    }

    static final class EventBinding {
        final Class<?> eventType;
        final HandlerList handlerList;
        final Method player;
        final Method cancelled;

        private EventBinding(Class<?> eventType, HandlerList handlerList, Method player, Method cancelled) {
            this.eventType = eventType;
            this.handlerList = handlerList;
            this.player = player;
            this.cancelled = cancelled;
        }

        static EventBinding create(Class<?> eventType) throws ReflectiveOperationException {
            if (!Event.class.isAssignableFrom(eventType) || !Cancellable.class.isAssignableFrom(eventType)) {
                throw new IllegalStateException("MVI share event must be a cancellable Bukkit event");
            }
            Method getHandlerList = eventType.getDeclaredMethod("getHandlerList");
            if (!Modifier.isStatic(getHandlerList.getModifiers()) || getHandlerList.getReturnType() != HandlerList.class) {
                throw new IllegalStateException("MVI event has an incompatible HandlerList accessor");
            }
            HandlerList list = (HandlerList) getHandlerList.invoke(null);
            Method getPlayer = eventType.getMethod("getPlayer");
            if (!Player.class.isAssignableFrom(getPlayer.getReturnType())) {
                throw new IllegalStateException("MVI event getPlayer does not return a Bukkit Player");
            }
            Method setCancelled = eventType.getMethod("setCancelled", boolean.class);
            Method isCancelled = eventType.getMethod("isCancelled");
            if (setCancelled.getReturnType() != void.class || isCancelled.getReturnType() != boolean.class) {
                throw new IllegalStateException("MVI event cancellation API is incompatible");
            }
            return new EventBinding(eventType, Objects.requireNonNull(list), getPlayer, setCancelled);
        }
    }
}
