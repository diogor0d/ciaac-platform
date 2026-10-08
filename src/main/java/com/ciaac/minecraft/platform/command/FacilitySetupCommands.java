package com.ciaac.minecraft.platform.command;

import com.ciaac.minecraft.minigames.configuration.RuntimeConfiguration;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.module.MinigameModuleRegistry;
import com.ciaac.minecraft.minigames.paper.configuration.PaperConfigurationResolver;
import com.ciaac.minecraft.minigames.paper.configuration.ResolvedColorFloorConfiguration;
import com.ciaac.minecraft.minigames.paper.configuration.ResolvedBuildBattleConfiguration;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import pt.ciaac.minigames.paper.template.ColorFloorTemplateCapture;
import pt.ciaac.minigames.paper.template.BuildBattleTemplateCapture;
import pt.ciaac.minigames.paper.template.TemplateArtifactExporter;

/** Local console-only facility inspection and create-only floor/build template captures. */
public final class FacilitySetupCommands {
    private final Server server;
    private final Path templates;
    private final Supplier<RuntimeConfiguration> configuration;
    private final Supplier<MinigameModuleRegistry> modules;
    private final BooleanSupplier hasBlockingSessions;

    public FacilitySetupCommands(Server server, Path templates, Supplier<RuntimeConfiguration> configuration,
            Supplier<MinigameModuleRegistry> modules, BooleanSupplier hasBlockingSessions) {
        this.server = Objects.requireNonNull(server, "server");
        this.templates = Objects.requireNonNull(templates, "templates");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.modules = Objects.requireNonNull(modules, "modules");
        this.hasBlockingSessions = Objects.requireNonNull(hasBlockingSessions, "hasBlockingSessions");
    }

    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) || sender instanceof RemoteConsoleCommandSender) {
            sender.sendMessage("§cA preparação de instalações exige a consola local autorizada.");
            return true;
        }
        if (!server.isPrimaryThread()) {
            sender.sendMessage("§cA preparação exige a thread principal do servidor.");
            return true;
        }
        try {
            if (args.length == 3 && args[1].equalsIgnoreCase("mundo")) {
                var world = server.getWorld(args[2]);
                if (world == null || !world.getName().equals(args[2])) {
                    sender.sendMessage("§cEsse mundo não está carregado com esse nome exato.");
                } else {
                    sender.sendMessage("§bMundo: " + world.getName() + "; UUID: " + world.getUID()
                            + "; alturas: " + world.getMinHeight() + ".." + (world.getMaxHeight() - 1) + ".");
                }
                return true;
            }
            if (args.length == 2 && args[1].equalsIgnoreCase("validar")) {
                var resolved = new PaperConfigurationResolver().resolve(configuration.get(), server);
                for (GameKey game : GameKey.values()) {
                    var value = resolved.module(game).orElseThrow();
                    sender.sendMessage("§b" + game.portugueseName() + "§7: configuração="
                            + (value.admissionAllowed() ? "válida" : "fechada") + "; módulo="
                            + modules.get().get(game).status().availability() + ".");
                    value.diagnostics().forEach(d -> sender.sendMessage("§c" + d.path() + ": " + d.code()));
                }
                sender.sendMessage("§7A validação de configuração não substitui testes de jogo e recuperação.");
                return true;
            }
            if (args.length == 2 && args[1].equalsIgnoreCase("capturar-cores")) {
                if (hasBlockingSessions.getAsBoolean()) {
                    sender.sendMessage("§cA captura exige todas as sessões terminadas, incluindo recuperações e quarentenas.");
                    return true;
                }
                var module = new PaperConfigurationResolver().resolve(configuration.get(), server)
                        .module(GameKey.COLOR_FLOOR).orElseThrow();
                if (!module.admissionAllowed()
                        || !(module.gameConfiguration().orElse(null) instanceof ResolvedColorFloorConfiguration value)) {
                    sender.sendMessage("§cConfigura primeiro o Chão de Cores com mundo, chão, fronteira e cores válidos.");
                    return true;
                }
                var artifact = ColorFloorTemplateCapture.capture(value.world(), value.regions().get("floor"), value.rulesetRevision());
                if (!java.util.Set.copyOf(artifact.colorIds().values()).equals(value.palette().stream()
                        .map(Enum::name).collect(java.util.stream.Collectors.toSet()))) {
                    sender.sendMessage("§cAs cores reais do piso não coincidem com a paleta configurada.");
                    return true;
                }
                ColorFloorTemplateCapture.export(artifact, templates);
                sender.sendMessage("§aTemplate criado e verificado: " + artifact.artifactId()
                        + "; células=" + artifact.blockData().size() + "; SHA-256=" + artifact.checksumSha256() + ".");
                sender.sendMessage("§7O ficheiro existente nunca é substituído. Reinicia depois de terminar a manutenção para carregar o template.");
                return true;
            }
            if (args.length == 2 && args[1].equalsIgnoreCase("capturar-construcao")) {
                if (hasBlockingSessions.getAsBoolean()) {
                    sender.sendMessage("§cA captura exige todas as sessões terminadas, incluindo recuperações e quarentenas.");
                    return true;
                }
                var module = new PaperConfigurationResolver().resolve(configuration.get(), server)
                        .module(GameKey.BUILD_BATTLE).orElseThrow();
                if (!module.admissionAllowed()
                        || !(module.gameConfiguration().orElse(null) instanceof ResolvedBuildBattleConfiguration value)) {
                    sender.sendMessage("§cConfigura primeiro o Build Battle com mundo, lobby e lotes válidos.");
                    return true;
                }
                var plots = value.plots().values().stream().map(plot -> value.regions().get(plot.regionId())).toList();
                var artifact = BuildBattleTemplateCapture.capture(value.world(), boundingVolume(plots),
                        value.worldTemplateMarker(), value.rulesetRevision());
                TemplateArtifactExporter.export(artifact, templates);
                sender.sendMessage("§aTemplate criado e verificado: " + artifact.artifactId()
                        + "; células=" + artifact.blockData().size() + "; SHA-256=" + artifact.checksumSha256() + ".");
                sender.sendMessage("§7Os resets abrangem apenas os lotes configurados; os espaços entre lotes ficam preservados.");
                return true;
            }
        } catch (IOException | RuntimeException failure) {
            sender.sendMessage("§cA preparação foi recusada; verifica mundo, chunks, configuração e template existente.");
            return true;
        }
        sender.sendMessage("§cUso: /ciaac instalações <mundo <nome>|validar|capturar-cores|capturar-construcao>.");
        return true;
    }

    private static com.ciaac.minecraft.minigames.region.CuboidRegion boundingVolume(
            java.util.List<com.ciaac.minecraft.minigames.region.CuboidRegion> plots) {
        if (plots.isEmpty() || plots.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Missing plot geometry");
        var world = plots.getFirst().worldId();
        if (plots.stream().anyMatch(plot -> !world.equals(plot.worldId()))) throw new IllegalArgumentException("Mixed plot worlds");
        return new com.ciaac.minecraft.minigames.region.CuboidRegion(world,
                plots.stream().mapToInt(p -> p.minX()).min().orElseThrow(),
                plots.stream().mapToInt(p -> p.minY()).min().orElseThrow(),
                plots.stream().mapToInt(p -> p.minZ()).min().orElseThrow(),
                plots.stream().mapToInt(p -> p.maxX()).max().orElseThrow(),
                plots.stream().mapToInt(p -> p.maxY()).max().orElseThrow(),
                plots.stream().mapToInt(p -> p.maxZ()).max().orElseThrow());
    }
}
