package com.ciaac.minecraft.platform;

import com.ciaac.minecraft.minigames.bootstrap.ConfiguredModuleAssembler;
import com.ciaac.minecraft.minigames.bootstrap.MinigamePlatformRuntime;
import com.ciaac.minecraft.minigames.configuration.RuntimeConfiguration;
import com.ciaac.minecraft.minigames.configuration.RuntimeConfigurationLoader;
import com.ciaac.minecraft.minigames.core.ModuleCatalog;
import com.ciaac.minecraft.minigames.minecart.MinecartSpeedService;
import com.ciaac.minecraft.minigames.minecart.MinecartSpeedAuditLogger;
import com.ciaac.minecraft.minigames.module.MinigameModuleRegistry;
import com.ciaac.minecraft.minigames.updater.UpdaterConfiguration;
import com.ciaac.minecraft.minigames.updater.UpdaterConfigurationLoader;
import com.ciaac.minecraft.minigames.updater.UpdaterService;
import com.ciaac.minecraft.platform.command.CiaacPlatformCommand;
import com.ciaac.minecraft.platform.recovery.RecoveryAdmissionGate;
import com.ciaac.minecraft.platform.securityevents.SecurityEvent;
import com.ciaac.minecraft.platform.securityevents.SecurityEventLogger;
import java.io.File;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Shared Paper bootstrap for the independently bounded CIAAC platform modules. */
public final class CiaacPlatformPlugin extends JavaPlugin {
    private ModuleCatalog moduleCatalog;
    private MinigamePlatformRuntime minigames;
    private MinecartSpeedService minecarts;
    private UpdaterService updater;
    private SecurityEventLogger securityEvents;
    private RecoveryAdmissionGate recoveryAdmission;

    @Override
    public void onEnable() {
        recoveryAdmission = new RecoveryAdmissionGate();
        try {
            getServer().getPluginManager().registerEvents(recoveryAdmission, this);
        } catch (RuntimeException | LinkageError failure) {
            getLogger().severe("Não foi possível proteger a admissão; foi solicitado o encerramento do servidor.");
            getServer().shutdown();
            return;
        }
        try {
            saveDefaultConfig();
            saveBundledResource("minecarts.yml");
            saveBundledResource("retention.yml");
            securityEvents = new SecurityEventLogger(getLogger(), "ciaac-platform");
            moduleCatalog = ModuleCatalog.foundationCatalog();

            UpdaterConfigurationLoader.LoadResult updaterLoad = new UpdaterConfigurationLoader().load(getConfig());
            updaterLoad.diagnosticPtPt().ifPresent(message -> getLogger().warning(message));
            RuntimeConfiguration configuration = new RuntimeConfigurationLoader().load(getConfig());
            UpdaterConfiguration updaterConfiguration = updaterLoad.configuration();
            updater = UpdaterService.start(this, updaterConfiguration, installedPluginFileName());

            minigames = MinigamePlatformRuntime.start(this, new ConfiguredModuleAssembler());
            configuration.globalProblems().forEach(problem -> getLogger().severe(
                    "A configuração ficou fechada em " + problem.path() + ": " + problem.code()));
        } catch (RuntimeException | LinkageError failure) {
            getLogger().severe("Os minijogos não conseguiram estabelecer o limite de recuperação: "
                    + failure.getClass().getSimpleName());
            emit(SecurityEvent.system(Instant.now(), SecurityEvent.Category.RECOVERY,
                    "MINIGAMES_START_FAILED", SecurityEvent.Severity.HIGH, "FAILED"));
            recoveryAdmission.fail();
            try {
                recoveryAdmission.denyOnlinePlayers(getServer());
            } catch (RuntimeException | LinkageError denialFailure) {
                getLogger().severe("Não foi possível retirar os jogadores após a falha de recuperação; foi solicitado o encerramento do servidor.");
                getServer().shutdown();
            }
            return;
        }

        recoveryAdmission.ready();
        try {
            startMinecarts();
        } catch (RuntimeException | LinkageError failure) {
            getLogger().severe("O módulo de carrinhos ficou fechado: " + failure.getClass().getSimpleName());
        }
        emit(SecurityEvent.system(Instant.now(), SecurityEvent.Category.LIFECYCLE,
                "PLUGIN_ENABLED", SecurityEvent.Severity.INFO, "SUCCESS"));
        getLogger().info("CIAACPlatform carregada; os módulos novos permanecem fechados até validação específica.");
    }

    @Override
    public void onDisable() {
        if (recoveryAdmission != null) recoveryAdmission.fail();
        emit(SecurityEvent.system(Instant.now(), SecurityEvent.Category.LIFECYCLE,
                "PLUGIN_DISABLED", SecurityEvent.Severity.INFO, "SUCCESS"));
        RuntimeException failure = null;
        failure = closeMinecarts(failure);
        failure = closeMinigames(failure);
        failure = closeUpdater(failure);
        securityEvents = null;
        if (failure != null) getLogger().severe("O encerramento da CIAACPlatform requer revisão: "
                + failure.getClass().getSimpleName());
    }

    public ModuleCatalog moduleCatalog() {
        return Objects.requireNonNull(moduleCatalog, "A plataforma ainda não foi ativada.");
    }

    public MinigameModuleRegistry modules() {
        MinigamePlatformRuntime current = minigames;
        return current == null
                ? MinigameModuleRegistry.allUnavailable("Os minijogos estão temporariamente fechados.")
                : current.modules();
    }

    /** Read-only operator view; update staging remains independent from gameplay state. */
    public Optional<UpdaterService.UpdaterOutcome> updaterOutcome() {
        UpdaterService current = updater;
        return current == null ? Optional.empty() : Optional.of(current.lastOutcome());
    }

    public void emit(SecurityEvent event) {
        SecurityEventLogger current = securityEvents;
        if (current == null) return;
        try {
            current.emit(event);
        } catch (RuntimeException failure) {
            getLogger().warning("Não foi possível emitir um evento estruturado de segurança: "
                    + failure.getClass().getSimpleName());
        }
    }

    public void unregisterRuntimeListeners() {
        RecoveryAdmissionGate.unregisterRuntimeListeners(this, recoveryAdmission);
    }

    private void startMinecarts() {
        MinecartSpeedService service = new MinecartSpeedService(this, new File(getDataFolder(), "minecarts.yml"));
        getServer().getPluginManager().registerEvents(service, this);
        MinecartSpeedService.ReloadResult result = service.reload();
        if (!result.accepted()) getLogger().warning("O módulo de carrinhos manteve-se fechado: " + result.messagePtPt());
        minecarts = service;
        PluginCommand command = Objects.requireNonNull(getCommand("ciaac"), "Falta declarar o comando ciaac");
        CiaacPlatformCommand executor = new CiaacPlatformCommand(
                service, new MinecartSpeedAuditLogger(getLogger(), this::emit, this::minecartActor),
                new com.ciaac.minecraft.platform.command.FacilitySetupCommands(getServer(),
                        getDataFolder().toPath().resolve("templates"),
                        () -> new RuntimeConfigurationLoader().load(getConfig()),
                        () -> minigames.modules(),
                        () -> minigames == null || minigames.hasBlockingSessions()));
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private void saveBundledResource(String name) {
        File target = new File(getDataFolder(), name);
        if (!target.isFile()) saveResource(name, false);
    }

    private RuntimeException closeMinecarts(RuntimeException previous) {
        MinecartSpeedService current = minecarts;
        minecarts = null;
        if (current == null) return previous;
        try { current.close(); }
        catch (RuntimeException failure) { return combine(previous, failure); }
        return previous;
    }

    private RuntimeException closeMinigames(RuntimeException previous) {
        MinigamePlatformRuntime current = minigames;
        minigames = null;
        if (current == null) return previous;
        try { current.close(); }
        catch (RuntimeException failure) { return combine(previous, failure); }
        return previous;
    }

    private RuntimeException closeUpdater(RuntimeException previous) {
        UpdaterService current = updater;
        updater = null;
        if (current == null) return previous;
        try { current.close(); }
        catch (RuntimeException failure) { return combine(previous, failure); }
        return previous;
    }

    private static RuntimeException combine(RuntimeException previous, RuntimeException failure) {
        if (previous == null) return failure;
        previous.addSuppressed(failure);
        return previous;
    }

    private String installedPluginFileName() { return getFile().getName(); }

    private Optional<SecurityEvent.Actor> minecartActor(CommandSender sender) {
        if (!(sender instanceof Player player) || minigames == null) return Optional.empty();
        return minigames.authenticatedActor(player);
    }
}
