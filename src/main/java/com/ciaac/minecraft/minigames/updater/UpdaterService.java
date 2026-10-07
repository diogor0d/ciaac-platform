package com.ciaac.minecraft.minigames.updater;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * One-shot startup updater. Paper consumes a staged JAR on a later controlled
 * restart; the optional restart request is never a hot reload or a kick.
 */
public final class UpdaterService implements AutoCloseable {
    private final JavaPlugin plugin;
    private final UpdaterConfiguration configuration;
    private final String installedJarName;
    private final Path updateDirectory;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean restartRequested = new AtomicBoolean();
    private volatile BukkitTask checkTask;
    private volatile BukkitTask restartTask;
    private volatile UpdaterOutcome lastOutcome;

    private UpdaterService(
            JavaPlugin plugin,
            UpdaterConfiguration configuration,
            String installedJarName) throws IOException {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.installedJarName = safeInstalledJarName(installedJarName);
        if (!configuration.enabled()) {
            this.updateDirectory = null;
            this.lastOutcome = new UpdaterOutcome(Status.DISABLED,
                    "A atualização automática está desativada [UPDATER_DISABLED].",
                    Optional.empty(), Instant.now());
            return;
        }
        String configuredFolder = plugin.getServer().getUpdateFolder();
        if (configuredFolder == null || configuredFolder.isBlank()) {
            throw new IOException("Paper did not expose an update folder");
        }
        this.updateDirectory = resolveUpdateDirectory(
                plugin.getServer().getPluginsFolder().toPath(), configuredFolder);
        this.lastOutcome = new UpdaterOutcome(Status.DISABLED,
                "A atualização automática está desativada [UPDATER_DISABLED].",
                Optional.empty(), Instant.now());
    }

    /** Starts exactly one asynchronous check and returns a closeable lifecycle handle. */
    public static UpdaterService start(
            JavaPlugin plugin,
            UpdaterConfiguration configuration,
            String installedJarName) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(configuration, "configuration");
        try {
            UpdaterService service = new UpdaterService(plugin, configuration, installedJarName);
            if (!configuration.enabled()) return service;
            service.setOutcome(Status.CHECKING, "A atualização automática está a consultar a versão publicada.",
                    Optional.empty());
            service.checkTask = plugin.getServer().getScheduler()
                    .runTaskAsynchronously(plugin, service::checkAndStage);
            return service;
        } catch (IOException | RuntimeException failed) {
            plugin.getLogger().warning("A atualização automática ficou fechada [UPDATER_START_FAILED].");
            try {
                UpdaterService service = new UpdaterService(
                        plugin,
                        UpdaterConfiguration.disabled(),
                        "ciaac-platform.jar");
                service.setOutcome(Status.FAILED,
                        "A atualização automática ficou fechada [UPDATER_START_FAILED].", Optional.empty());
                return service;
            } catch (IOException impossible) {
                throw new IllegalStateException("Updater lifecycle could not be created", impossible);
            }
        }
    }

    private void checkAndStage() {
        try {
            if (closed.get()) return;
            UpdaterCache cache = new UpdaterCache(plugin.getDataFolder().toPath().resolve("updater-cache"));
            Optional<UpdaterCache.RestartAttempt> previousRestart;
            try {
                previousRestart = cache.readRestartAttempt();
            } catch (IOException | RuntimeException invalidGuard) {
                setFailure("UPDATER_RESTART_GUARD_INVALID");
                return;
            }
            SemanticVersion current = SemanticVersion.parse(plugin.getPluginMeta().getVersion());
            RestartGuardDecision guardDecision = restartGuardDecision(previousRestart, current);
            if (guardDecision == RestartGuardDecision.BLOCKED) {
                UpdaterCache.RestartAttempt attempt = previousRestart.orElseThrow();
                String diagnostic = "O reinício automático foi bloqueado [UPDATER_RESTART_GUARD]: "
                        + "a versão preparada (release " + attempt.releaseId()
                        + ") ainda não foi consumida pelo Paper. Verifica o arranque antes de remover o marcador.";
                setOutcome(Status.RESTART_BLOCKED, diagnostic, Optional.of(attempt.version()));
                plugin.getLogger().warning(diagnostic);
                return;
            }
            if (guardDecision == RestartGuardDecision.CONSUMED) {
                try {
                    cache.clearRestartAttempt();
                } catch (IOException | RuntimeException clearFailure) {
                    setFailure("UPDATER_RESTART_GUARD_CLEAR_FAILED");
                    return;
                }
                setOutcome(Status.RESTART_CONSUMED,
                        "A versão preparada foi consumida; o marcador de reinício foi limpo.",
                        Optional.of(current));
            }
            GitHubReleaseClient client = new GitHubReleaseClient(configuration);
            String cacheSelector = client.cacheSelector();
            UpdaterCache.CacheSnapshot cached = cache.read(cacheSelector);
            Optional<String> requestEtag = cached.rawJson().isPresent() ? cached.etag() : Optional.empty();
            GitHubReleaseClient.LatestResponse latest = client.latest(requestEtag);

            GitHubRelease release;
            if (latest.status() == GitHubReleaseClient.LatestResponse.Status.FOUND) {
                release = latest.release().orElseThrow(() -> new IOException("GitHub omitted release data"));
                String raw = latest.rawJson().orElseThrow(() -> new IOException("GitHub omitted release JSON"));
                cache.store(cacheSelector, raw, latest.etag());
            } else {
                if (requestEtag.isEmpty()) {
                    throw new IOException("GitHub returned 304 without a paired conditional request");
                }
                String raw = cached.rawJson()
                        .orElseThrow(() -> new IOException("GitHub returned 304 without a cached release"));
                release = client.parseResponse(raw);
            }
            if (closed.get()) return;

            SemanticVersion remote = SemanticVersion.parse(release.tag());
            if (remote.compareTo(current) <= 0) {
                setOutcome(Status.NO_UPDATE, previousRestart.isPresent()
                                ? "A versão preparada foi consumida; a versão instalada já é a mais recente publicada."
                                : "A versão instalada já é a mais recente publicada.",
                        Optional.of(remote));
                return;
            }

            UpdateVerifier.ReleaseAssets assets = UpdateVerifier.selectAssets(release, configuration);
            byte[] manifestBytes = client.downloadMetadata(assets.manifest());
            byte[] signatureBytes = client.downloadMetadata(assets.signature());
            ReleaseManifest manifest = UpdateVerifier.verifyManifest(
                    release, configuration, manifestBytes, signatureBytes);
            if (manifest.version().compareTo(current) <= 0) {
                setOutcome(Status.NO_UPDATE, previousRestart.isPresent()
                                ? "A versão preparada foi consumida; a versão assinada já não é mais recente do que a instalada."
                                : "A versão assinada já não é mais recente do que a instalada.",
                        Optional.of(manifest.version()));
                return;
            }
            if (closed.get()) return;

            Path temporaryJar = temporaryJarPath();
            Path target = targetPath();
            UpdateVerifier.VerificationResult verification;
            try {
                try (InputStream jar = client.downloadJar(assets.jar())) {
                    verification = UpdateVerifier.verifyJar(assets.jar(), configuration, manifest, jar, temporaryJar);
                }
                if (!stageIfOpen(temporaryJar, target)) return;
            } finally {
                // The random CREATE_NEW path belongs exclusively to this check;
                // remove untrusted partial downloads as well as verified leftovers.
                Files.deleteIfExists(temporaryJar);
            }
            if (configuration.restartEmptyServerAfterStaging()) {
                try {
                    cache.storeRestartAttempt(new UpdaterCache.RestartAttempt(
                            verification.manifest().releaseId(), verification.pluginVersion(),
                            verification.sha256(), Instant.now()));
                } catch (IOException | RuntimeException guardFailure) {
                    setFailure("UPDATER_RESTART_GUARD_WRITE_FAILED");
                    return;
                }
            }
            setOutcome(Status.STAGED,
                    configuration.restartEmptyServerAfterStaging()
                            ? "Atualização verificada; o servidor será reiniciado quando ficar vazio."
                            : "Atualização verificada e preparada para o próximo reinício controlado.",
                    Optional.of(manifest.version()));
            if (configuration.restartEmptyServerAfterStaging()
                    && !scheduleRestartWhenEmpty()) return;
        } catch (GitHubReleaseClient.NoEligibleReleaseException noEligibleRelease) {
            setOutcome(Status.NO_UPDATE,
                    "Não há uma versão publicada elegível para o canal configurado.", Optional.empty());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            setFailure("UPDATER_INTERRUPTED");
        } catch (IOException | RuntimeException failed) {
            setFailure("UPDATER_CHECK_FAILED");
        } finally {
            checkTask = null;
        }
    }

    static RestartGuardDecision restartGuardDecision(
            Optional<UpdaterCache.RestartAttempt> attempt, SemanticVersion installed) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(installed, "installed");
        if (attempt.isEmpty()) return RestartGuardDecision.NONE;
        return installed.compareTo(attempt.orElseThrow().version()) < 0
                ? RestartGuardDecision.BLOCKED : RestartGuardDecision.CONSUMED;
    }

    static Path resolveUpdateDirectory(Path pluginsDirectory, String configuredFolder) throws IOException {
        Path plugins = Objects.requireNonNull(pluginsDirectory, "pluginsDirectory")
                .toAbsolutePath().normalize();
        if (configuredFolder == null || configuredFolder.isBlank()) {
            throw new IOException("Paper update-folder name is empty");
        }
        final Path relative;
        try {
            relative = Path.of(configuredFolder);
        } catch (RuntimeException invalidPath) {
            throw new IOException("Paper update-folder name is invalid", invalidPath);
        }
        if (relative.isAbsolute()) throw new IOException("Paper update folder must be relative to the plugins folder");
        Path resolved = plugins.resolve(relative).normalize();
        if (resolved.equals(plugins) || !resolved.startsWith(plugins)) {
            throw new IOException("Paper update folder escapes the plugins folder");
        }
        return resolved;
    }

    private Path temporaryJarPath() throws IOException {
        if (updateDirectory == null) throw new IOException("updater is disabled");
        prepareUpdateDirectory();
        for (int attempt = 0; attempt < 3; attempt++) {
            Path candidate = updateDirectory.resolve(".ciaac-updater-" + UUID.randomUUID() + ".tmp")
                    .normalize();
            if (candidate.getParent().equals(updateDirectory) && Files.notExists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
        throw new IOException("could not reserve a bounded updater temporary path");
    }

    private Path targetPath() throws IOException {
        if (updateDirectory == null) throw new IOException("updater is disabled");
        prepareUpdateDirectory();
        Path target = updateDirectory.resolve(installedJarName).normalize();
        if (!updateDirectory.equals(target.getParent())) throw new IOException("unsafe updater target path");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(target)) throw new IOException("updater target must not be a symbolic link");
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("updater target is not a regular file");
            }
        }
        return target;
    }

    private static String safeInstalledJarName(String value) {
        value = Objects.requireNonNull(value, "installedJarName").trim();
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
                || !value.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            throw new IllegalArgumentException("installed plugin filename is unsafe");
        }
        return value;
    }

    private void prepareUpdateDirectory() throws IOException {
        if (updateDirectory == null) throw new IOException("updater is disabled");
        rejectSymlinkAncestors(updateDirectory);
        if (Files.exists(updateDirectory, LinkOption.NOFOLLOW_LINKS)
                && Files.isSymbolicLink(updateDirectory)) {
            throw new IOException("Paper update folder must not be a symbolic link");
        }
        Files.createDirectories(updateDirectory);
        if (!Files.isDirectory(updateDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Paper update folder is not a directory");
        }
    }

    private static void rejectSymlinkAncestors(Path path) throws IOException {
        Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IOException("Paper update folder must not contain symbolic-link ancestors");
            }
            current = current.getParent();
        }
    }

    private synchronized boolean stageIfOpen(Path temporary, Path target) throws IOException {
        if (closed.get()) return false;
        atomicallyStage(temporary, target);
        return true;
    }

    private void atomicallyStage(Path temporary, Path target) throws IOException {
        if (Files.isSymbolicLink(temporary)
                || !Files.isRegularFile(temporary, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("verified updater temporary file is not a regular file");
        }
        if (Files.isSymbolicLink(target)) throw new IOException("updater target became a symbolic link");
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            throw new IOException("atomic updater staging is unavailable", unsupported);
        }
        if (Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("staged updater target is not a regular file");
        }
    }

    private synchronized boolean scheduleRestartWhenEmpty() {
        if (closed.get() || restartTask != null || restartRequested.get()) return true;
        try {
            restartTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
                if (closed.get()) {
                    cancelRestartTask();
                    return;
                }
                if (restartRequested.get()) return;
                if (!plugin.getServer().getOnlinePlayers().isEmpty()) return;
                cancelRestartTask();
                if (!restartRequested.compareAndSet(false, true)) return;
                setOutcome(Status.RESTART_REQUESTED,
                        "Foi solicitado o reinício do servidor para carregar a atualização verificada.",
                        lastOutcome.version());
                try {
                    plugin.getServer().restart();
                } catch (RuntimeException failed) {
                    setFailure("UPDATER_RESTART_REQUEST_FAILED");
                }
            }, 20L, 20L);
        } catch (RuntimeException failed) {
            setFailure("UPDATER_RESTART_SCHEDULE_FAILED");
            return false;
        }
        setOutcome(Status.WAITING_FOR_EMPTY_SERVER,
                "A atualização está preparada; o servidor será reiniciado quando ficar vazio.", lastOutcome.version());
        return true;
    }

    private synchronized void cancelRestartTask() {
        if (restartTask != null) {
            restartTask.cancel();
            restartTask = null;
        }
    }

    private void setFailure(String code) {
        if (closed.get()) return;
        setOutcome(Status.FAILED, "A atualização automática ficou fechada [" + code + "].", Optional.empty());
        plugin.getLogger().warning("A atualização automática ficou fechada [" + code + "].");
    }

    private synchronized void setOutcome(Status status, String diagnosticPtPt, Optional<SemanticVersion> version) {
        lastOutcome = new UpdaterOutcome(status, diagnosticPtPt, version, Instant.now());
        if (status == Status.NO_UPDATE || status == Status.STAGED
                || status == Status.WAITING_FOR_EMPTY_SERVER
                || status == Status.RESTART_REQUESTED
                || status == Status.RESTART_CONSUMED) {
            plugin.getLogger().info(diagnosticPtPt);
        }
    }

    public UpdaterOutcome lastOutcome() {
        return lastOutcome;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (!closed.compareAndSet(false, true)) return;
            BukkitTask check = checkTask;
            if (check != null) check.cancel();
            cancelRestartTask();
            setOutcome(Status.CLOSED, "A atualização automática foi encerrada com o plugin.", Optional.empty());
        }
    }

    public enum Status {
        DISABLED,
        CHECKING,
        NO_UPDATE,
        STAGED,
        WAITING_FOR_EMPTY_SERVER,
        RESTART_REQUESTED,
        RESTART_BLOCKED,
        RESTART_CONSUMED,
        FAILED,
        CLOSED
    }

    enum RestartGuardDecision {
        NONE,
        BLOCKED,
        CONSUMED
    }

    public record UpdaterOutcome(
            Status status,
            String diagnosticPtPt,
            Optional<SemanticVersion> version,
            Instant at) {
        public UpdaterOutcome {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(diagnosticPtPt, "diagnosticPtPt");
            if (diagnosticPtPt.length() > 256) throw new IllegalArgumentException("diagnostic is too long");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(at, "at");
        }
    }
}
