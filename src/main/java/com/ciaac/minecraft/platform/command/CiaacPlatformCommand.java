package com.ciaac.minecraft.platform.command;

import com.ciaac.minecraft.minigames.minecart.MinecartSpeedAuditLogger;
import com.ciaac.minecraft.minigames.minecart.MinecartSpeedAuditSink;
import com.ciaac.minecraft.minigames.minecart.MinecartSpeedControl;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Comando limitado da plataforma; nunca invoca o recarregamento global do Paper. */
public final class CiaacPlatformCommand implements CommandExecutor, org.bukkit.command.TabCompleter {
    private static final String TEST_PERMISSION = "ciaac.minecarts.test";
    private final MinecartSpeedControl minecarts;
    private final MinecartSpeedAuditSink audit;

    public CiaacPlatformCommand(MinecartSpeedControl minecarts, MinecartSpeedAuditSink audit) {
        this.minecarts = Objects.requireNonNull(minecarts, "minecarts");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§bCIAACPlatform§7 — usa /" + label + " carrinhos <ação>.");
            return true;
        }
        if (!"carrinhos".equals(args[0].toLowerCase(Locale.ROOT))) {
            sender.sendMessage("§cMódulo desconhecido. Usa /" + label + " carrinhos.");
            return true;
        }
        if (args.length < 2 || "estado".equalsIgnoreCase(args[1])) return status(sender);
        return switch (args[1].toLowerCase(Locale.ROOT)) {
            case "recarregar" -> reload(sender, args);
            case "definir" -> setWorld(sender, args);
            case "predefinir" -> setDefault(sender, args);
            case "repor" -> clearWorld(sender, args);
            case "repor-tudo" -> clearAll(sender, args);
            default -> usage(sender, label);
        };
    }

    private boolean status(CommandSender sender) {
        if (!sender.hasPermission("ciaac.minecarts.view")) {
            sender.sendMessage("§cNão tens permissão para consultar os carrinhos.");
            return true;
        }
        sender.sendMessage("§b" + minecarts.statusPtPt());
        return true;
    }

    private boolean reload(CommandSender sender, String[] args) {
        if (args.length != 2) return usage(sender, "ciaac");
        if (!sender.hasPermission("ciaac.minecarts.reload")) {
            sender.sendMessage("§cNão tens permissão para recarregar os carrinhos.");
            return true;
        }
        MinecartSpeedControl.ReloadResult result = minecarts.reload();
        result.clearedOverrides().ifPresent(cleared ->
                audit.emit(MinecartSpeedAuditLogger.Action.RELOAD_CLEAR, sender, cleared));
        sender.sendMessage((result.accepted() ? "§a" : "§c") + result.messagePtPt());
        return true;
    }

    private boolean setWorld(CommandSender sender, String[] args) {
        if (!testPermission(sender)) return true;
        if (args.length < 3 || args.length > 4) {
            sender.sendMessage("§cUso: /ciaac carrinhos definir <velocidade> [mundo].");
            return true;
        }
        Optional<Double> speed = parseSpeed(sender, args[2]);
        if (speed.isEmpty()) return true;
        Optional<MinecartSpeedControl.WorldIdentity> world = targetWorld(sender, args, 3);
        if (world.isEmpty()) return true;
        return respond(sender, MinecartSpeedAuditLogger.Action.SET_WORLD,
                minecarts.setWorldOverride(world.orElseThrow(), speed.orElseThrow()));
    }

    private boolean setDefault(CommandSender sender, String[] args) {
        if (!testPermission(sender)) return true;
        if (args.length != 3) {
            sender.sendMessage("§cUso: /ciaac carrinhos predefinir <velocidade>.");
            return true;
        }
        Optional<Double> speed = parseSpeed(sender, args[2]);
        if (speed.isEmpty()) return true;
        return respond(sender, MinecartSpeedAuditLogger.Action.SET_DEFAULT,
                minecarts.setDefaultOverride(speed.orElseThrow()));
    }

    private boolean clearWorld(CommandSender sender, String[] args) {
        if (!testPermission(sender)) return true;
        if (args.length > 3) {
            sender.sendMessage("§cUso: /ciaac carrinhos repor [mundo].");
            return true;
        }
        Optional<MinecartSpeedControl.WorldIdentity> world = targetWorld(sender, args, 2);
        if (world.isEmpty()) return true;
        return respond(sender, MinecartSpeedAuditLogger.Action.CLEAR_WORLD,
                minecarts.clearWorldOverride(world.orElseThrow()));
    }

    private boolean clearAll(CommandSender sender, String[] args) {
        if (!testPermission(sender)) return true;
        if (args.length != 2) {
            sender.sendMessage("§cUso: /ciaac carrinhos repor-tudo.");
            return true;
        }
        return respond(sender, MinecartSpeedAuditLogger.Action.CLEAR_ALL, minecarts.clearAllOverrides());
    }

    private boolean respond(
            CommandSender sender,
            MinecartSpeedAuditLogger.Action action,
            MinecartSpeedControl.ChangeResult result) {
        if (result.accepted()) audit.emit(action, sender, result);
        sender.sendMessage((result.accepted() ? "§a" : "§c") + result.messagePtPt()
                + " §7[operação " + result.operationId() + "]");
        return true;
    }

    private Optional<MinecartSpeedControl.WorldIdentity> targetWorld(
            CommandSender sender, String[] args, int worldArgument) {
        if (args.length > worldArgument) {
            Optional<MinecartSpeedControl.WorldIdentity> world = minecarts.loadedWorldExact(args[worldArgument]);
            if (world.isEmpty()) sender.sendMessage("§cEsse mundo não está carregado com esse nome exato.");
            return world;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cA consola tem de indicar explicitamente um mundo carregado.");
            return Optional.empty();
        }
        return Optional.of(new MinecartSpeedControl.WorldIdentity(
                player.getWorld().getName(), player.getWorld().getUID()));
    }

    private static Optional<Double> parseSpeed(CommandSender sender, String source) {
        try {
            return Optional.of(Double.parseDouble(source.replace(',', '.')));
        } catch (NumberFormatException failure) {
            sender.sendMessage("§cA velocidade deve ser um número entre 0,1 e 64,0 blocos/s.");
            return Optional.empty();
        }
    }

    private static boolean testPermission(CommandSender sender) {
        if (sender.hasPermission(TEST_PERMISSION)) return true;
        sender.sendMessage("§cNão tens permissão para testar velocidades dos carrinhos.");
        return false;
    }

    private static boolean usage(CommandSender sender, String label) {
        sender.sendMessage("§cUso: /" + label
                + " carrinhos <estado|recarregar|definir|predefinir|repor|repor-tudo>.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return prefix(List.of("carrinhos"), args[0]);
        if (args.length == 2 && "carrinhos".equalsIgnoreCase(args[0])) {
            ArrayList<String> options = new ArrayList<>();
            if (sender.hasPermission("ciaac.minecarts.view")) options.add("estado");
            if (sender.hasPermission("ciaac.minecarts.reload")) options.add("recarregar");
            if (sender.hasPermission(TEST_PERMISSION)) {
                options.addAll(List.of("definir", "predefinir", "repor", "repor-tudo"));
            }
            return prefix(options, args[1]);
        }
        if (sender.hasPermission(TEST_PERMISSION)
                && args.length == 3
                && "carrinhos".equalsIgnoreCase(args[0])
                && "repor".equalsIgnoreCase(args[1])) {
            return prefix(minecarts.loadedWorldNames(), args[2]);
        }
        if (sender.hasPermission(TEST_PERMISSION)
                && args.length == 4
                && "carrinhos".equalsIgnoreCase(args[0])
                && "definir".equalsIgnoreCase(args[1])) {
            return prefix(minecarts.loadedWorldNames(), args[3]);
        }
        return List.of();
    }

    private static List<String> prefix(List<String> values, String input) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
