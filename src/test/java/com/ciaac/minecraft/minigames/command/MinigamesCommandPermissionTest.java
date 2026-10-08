package com.ciaac.minecraft.minigames.command;

import static org.junit.jupiter.api.Assertions.*;

import com.ciaac.minecraft.minigames.module.MinigameModuleRegistry;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

class MinigamesCommandPermissionTest {
    private static final Command ROOT_COMMAND = new Command("minijogos") {
        @Override public boolean execute(CommandSender sender, String label, String[] args) {
            throw new AssertionError("The test invokes the command executor directly");
        }
    };

    @Test void updaterRouteDeniesBeforeReadingUpdaterState() {
        AtomicInteger updaterReads = new AtomicInteger();
        List<String> messages = new ArrayList<>();
        MinigamesCommand command = command(updaterReads);

        command.onCommand(sender(false, messages), ROOT_COMMAND, "minijogos", new String[] {"atualizacao"});

        assertEquals(0, updaterReads.get());
        assertTrue(messages.stream().anyMatch(message -> message.contains("Não tens permissão")));
    }

    @Test void updaterRouteReadsStateWhenExactAdminPermissionIsGranted() {
        AtomicInteger updaterReads = new AtomicInteger();
        MinigamesCommand command = command(updaterReads);

        command.onCommand(sender(true, new ArrayList<>()), ROOT_COMMAND, "minijogos", new String[] {"atualizacao"});

        assertEquals(1, updaterReads.get());
    }

    @Test void updaterSuggestionUsesAdminPermissionAndKeepsPublicCommands() {
        MinigamesCommand command = command(new AtomicInteger());
        List<String> publicOptions = List.of("menu", "estado", "ajuda", "top", "estatisticas");

        assertEquals(publicOptions, command.onTabComplete(sender(false, new ArrayList<>()), ROOT_COMMAND,
                "minijogos", new String[] {""}));
        assertEquals(List.of("menu", "estado", "ajuda", "top", "estatisticas", "atualizacao"),
                command.onTabComplete(sender(true, new ArrayList<>()), ROOT_COMMAND,
                        "minijogos", new String[] {""}));
    }

    private static MinigamesCommand command(AtomicInteger updaterReads) {
        return new MinigamesCommand(MinigameModuleRegistry.allUnavailable("indisponível"), Optional.empty(),
                ignored -> "tester", Clock.systemUTC(), () -> {
                    updaterReads.incrementAndGet();
                    return Optional.empty();
                });
    }

    private static CommandSender sender(boolean adminPermission, List<String> messages) {
        return (CommandSender) Proxy.newProxyInstance(MinigamesCommandPermissionTest.class.getClassLoader(),
                new Class<?>[] {CommandSender.class}, (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) {
                        return adminPermission && "ciaac.minigames.admin".equals(args[0]);
                    }
                    if (method.getName().equals("sendMessage") && args != null && args.length > 0) {
                        Object message = args[0];
                        messages.add(message instanceof Component component
                                ? PlainTextComponentSerializer.plainText().serialize(component)
                                : String.valueOf(message));
                        return null;
                    }
                    if (method.getName().equals("toString")) return "test-sender";
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getName().equals("equals")) return proxy == args[0];
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
    }
}
