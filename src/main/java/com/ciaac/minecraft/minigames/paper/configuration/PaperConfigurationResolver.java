package com.ciaac.minecraft.minigames.paper.configuration;

import com.ciaac.minecraft.minigames.anvildodge.AnvilDodgeConfig;
import com.ciaac.minecraft.minigames.arena.ArenaFormat;
import com.ciaac.minecraft.minigames.arena.ArenaFormatPolicy;
import com.ciaac.minecraft.minigames.arena.ArenaKitMode;
import com.ciaac.minecraft.minigames.archeryrange.ArcheryConfig;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattleConfig;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattleTheme;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattleThemePool;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattleTiePolicy;
import com.ciaac.minecraft.minigames.buildbattle.BuildBattleVotingCompletionPolicy;
import com.ciaac.minecraft.minigames.checkpointparkour.ParkourConfig;
import com.ciaac.minecraft.minigames.colorfloor.ColorFloorConfig;
import com.ciaac.minecraft.minigames.colorfloor.FloorColor;
import com.ciaac.minecraft.minigames.configuration.ConfigurationProblem;
import com.ciaac.minecraft.minigames.configuration.ConfigValues;
import com.ciaac.minecraft.minigames.configuration.ModuleConfiguration;
import com.ciaac.minecraft.minigames.configuration.RegionSpec;
import com.ciaac.minecraft.minigames.configuration.RuntimeConfiguration;
import com.ciaac.minecraft.minigames.configuration.WorldReference;
import com.ciaac.minecraft.minigames.core.GameKey;
import com.ciaac.minecraft.minigames.elytrarings.ElytraCourseRevision;
import com.ciaac.minecraft.minigames.elytrarings.ElytraRingsConfig;
import com.ciaac.minecraft.minigames.elytrarings.RingCheckpoint;
import com.ciaac.minecraft.minigames.hotpotato.HotPotatoConfig;
import com.ciaac.minecraft.minigames.isolation.IsolationPolicy;
import com.ciaac.minecraft.minigames.knockbacksumo.SumoConfig;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;

/**
 * Resolves immutable configuration into Paper-facing values without registering
 * listeners, regions, sessions, or any other runtime state.
 *
 * <p>The only live lookup is {@code Server#getWorld(UUID)} followed by an exact
 * world-name comparison. No block, entity, chunk, or template is read here.
 */
public final class PaperConfigurationResolver {
    private static final int MAX_LIST_ITEMS = 256;
    private static final int MAX_DIAGNOSTICS = 512;
    private static final Set<String> COMMON_KEYS = Set.of(
            "enabled", "world", "world.name", "world.uuid", "locations", "regions", "ruleset-revision", "seed");

    private static final Map<GameKey, Set<String>> EXACT_KEYS = exactKeys();

    public ConfigurationResolutionResult resolve(RuntimeConfiguration source, Server server) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(server, "server");
        List<ResolutionDiagnostic> global = new ArrayList<>();
        for (ConfigurationProblem problem : source.globalProblems()) global.add(fromProblem(problem));
        if (!source.globalAdmissionEnabled()) {
            global.add(new ResolutionDiagnostic("GLOBAL_ADMISSION_DISABLED", "admission.enabled",
                    "A admissão global está desativada; todos os módulos permanecem fechados."));
        }

        EnumMap<GameKey, ResolvedModuleConfiguration> modules = new EnumMap<>(GameKey.class);
        for (GameKey game : GameKey.values()) {
            ModuleConfiguration module = source.modules().get(game);
            modules.put(game, resolveModule(source, module, game, server));
        }
        boolean globalAllowed = source.globalAdmissionEnabled() && source.globalProblems().isEmpty();
        return new ConfigurationResolutionResult(source, globalAllowed, modules, allDiagnostics(global, modules));
    }

    private ResolvedModuleConfiguration resolveModule(
            RuntimeConfiguration source, ModuleConfiguration module, GameKey game, Server server) {
        String root = "modules." + game.id();
        List<ResolutionDiagnostic> diagnostics = new ArrayList<>();
        if (module == null) {
            diagnostics.add(new ResolutionDiagnostic("MODULE_MISSING", root,
                    "A configuração deste módulo não existe; a admissão foi fechada."));
            return closed(game, diagnostics);
        }
        diagnostics.addAll(module.problems().stream().limit(MAX_DIAGNOSTICS)
                .map(PaperConfigurationResolver::fromProblem).toList());
        ResolvedValues values = new ResolvedValues(module.values(), root);
        validateKeys(game, module.values(), root, diagnostics);
        validatePlaceholders(module.values(), root, diagnostics);
        if (!module.enabled()) {
            diagnostics.add(new ResolutionDiagnostic("MODULE_DISABLED", root + ".enabled",
                    "O módulo está desativado; a admissão permanece fechada."));
            return new ResolvedModuleConfiguration(game, false, false, module.world(), Optional.empty(),
                    Map.of(), Map.of(), Optional.empty(), diagnostics);
        }

        Optional<World> world = resolveWorld(module.world(), server, root, diagnostics);
        if (world.isEmpty()) {
            return new ResolvedModuleConfiguration(game, true, false, module.world(), Optional.empty(),
                    Map.of(), Map.of(), Optional.empty(), diagnostics);
        }
        World loadedWorld = world.orElseThrow();
        validatePlacement(game, loadedWorld, server, root, diagnostics);
        Map<String, Location> locations = resolveLocations(module, loadedWorld, server, root, diagnostics);
        Map<String, CuboidRegion> regions = resolveRegions(module, loadedWorld, server, root, diagnostics);
        validateGeometry(game, locations, regions, root, diagnostics);
        Context context = new Context(game, root, module, values, loadedWorld, locations, regions, diagnostics);
        Optional<ResolvedGameConfiguration> gameConfiguration = resolveGame(context);
        diagnostics.addAll(values.diagnostics());
        boolean allowed = source.globalAdmissionEnabled() && source.globalProblems().isEmpty()
                && diagnostics.isEmpty() && gameConfiguration.isPresent();
        return new ResolvedModuleConfiguration(game, true, allowed, module.world(), Optional.of(loadedWorld),
                locations, regions, gameConfiguration, diagnostics);
    }

    private Optional<World> resolveWorld(
            Optional<WorldReference> reference, Server server, String root, List<ResolutionDiagnostic> diagnostics) {
        if (reference.isEmpty()) {
            diagnostics.add(new ResolutionDiagnostic("WORLD_MISSING", root + ".world",
                    "É obrigatório indicar o nome e o UUID do mundo."));
            return Optional.empty();
        }
        try {
            Optional<World> world = reference.orElseThrow().resolve(server);
            if (world.isEmpty()) {
                diagnostics.add(new ResolutionDiagnostic("WORLD_NOT_LOADED", root + ".world",
                        "O mundo não está carregado ou o nome e o UUID não correspondem exatamente."));
            }
            return world;
        } catch (RuntimeException failure) {
            diagnostics.add(new ResolutionDiagnostic("WORLD_LOOKUP_FAILED", root + ".world",
                    "Não foi possível validar a identidade do mundo; a admissão foi fechada."));
            return Optional.empty();
        }
    }

    private void validatePlacement(GameKey game, World world, Server server, String root,
                                   List<ResolutionDiagnostic> diagnostics) {
        List<World> worlds;
        try {
            worlds = server.getWorlds();
        } catch (RuntimeException failure) {
            diagnostics.add(new ResolutionDiagnostic("WORLD_LIST_UNAVAILABLE", root + ".world",
                    "Não foi possível determinar o mundo principal."));
            return;
        }
        if (worlds == null || worlds.isEmpty() || worlds.get(0) == null) {
            diagnostics.add(new ResolutionDiagnostic("WORLD_LIST_UNAVAILABLE", root + ".world",
                    "Não existe um mundo principal carregado para validar a colocação."));
            return;
        }
        World primary = worlds.get(0);
        boolean sameAsPrimary = primary.getUID().equals(world.getUID());
        if (game.locationPolicy().name().equals("SPAWN_SAFEZONE") && !sameAsPrimary) {
            diagnostics.add(new ResolutionDiagnostic("SPAWN_WORLD_MISMATCH", root + ".world",
                    "Este módulo de zona segura tem de usar o mundo principal."));
        }
        if (game.locationPolicy().name().equals("DEDICATED_WORLD") && sameAsPrimary) {
            diagnostics.add(new ResolutionDiagnostic("DEDICATED_WORLD_MISMATCH", root + ".world",
                    "Este módulo exige um mundo dedicado separado do mundo principal."));
        }
    }

    private Map<String, Location> resolveLocations(ModuleConfiguration module, World world, Server server,
                                                    String root, List<ResolutionDiagnostic> diagnostics) {
        Map<String, Location> result = new LinkedHashMap<>();
        module.locations().forEach((id, specification) -> {
            String path = root + ".locations." + id;
            try {
                Optional<Location> location = specification.resolve(server);
                if (location.isEmpty()) {
                    diagnostics.add(new ResolutionDiagnostic("LOCATION_WORLD_UNAVAILABLE", path,
                            "A localização não pertence a um mundo carregado com a identidade configurada."));
                    return;
                }
                Location resolved = location.orElseThrow();
                if (resolved.getWorld() == null || !world.getUID().equals(resolved.getWorld().getUID())) {
                    diagnostics.add(new ResolutionDiagnostic("LOCATION_WORLD_MISMATCH", path,
                            "A localização está noutro mundo que não o módulo."));
                    return;
                }
                result.put(id, resolved.clone());
            } catch (RuntimeException failure) {
                diagnostics.add(new ResolutionDiagnostic("LOCATION_INVALID", path,
                        "A localização não pôde ser resolvida com segurança."));
            }
        });
        return Map.copyOf(result);
    }

    private Map<String, CuboidRegion> resolveRegions(ModuleConfiguration module, World world, Server server,
                                                     String root, List<ResolutionDiagnostic> diagnostics) {
        Map<String, CuboidRegion> result = new LinkedHashMap<>();
        module.regions().forEach((id, specification) -> {
            String path = root + ".regions." + id;
            try {
                CuboidRegion region = specification.fullHeight()
                        ? specification.toRegion(world.getMinHeight(), world.getMaxHeight())
                        : specification.toRegion();
                if (!world.getUID().equals(region.worldId())) {
                    diagnostics.add(new ResolutionDiagnostic("REGION_WORLD_MISMATCH", path,
                            "A região está associada a outro mundo que não o módulo."));
                    return;
                }
                if (region.minY() < -2048 || region.maxY() > 2048) {
                    diagnostics.add(new ResolutionDiagnostic("GEOMETRY_UNSAFE", path,
                            "Os limites verticais da região não são seguros para um adaptador Paper."));
                    return;
                }
                result.put(id, region);
            } catch (RuntimeException failure) {
                diagnostics.add(new ResolutionDiagnostic("REGION_INVALID", path,
                        "Os limites da região não são seguros."));
            }
        });
        return Map.copyOf(result);
    }

    private void validateGeometry(GameKey game, Map<String, Location> locations, Map<String, CuboidRegion> regions,
                                  String root, List<ResolutionDiagnostic> diagnostics) {
        if (game == GameKey.ARENA && regions.containsKey("combat-floor") && regions.containsKey("spectator-benches")
                && regions.get("combat-floor").intersects(regions.get("spectator-benches"))) {
            diagnostics.add(new ResolutionDiagnostic("GEOMETRY_OVERLAP", root + ".regions",
                    "A arena de combate e a zona de espectadores não podem sobrepor-se."));
        }
        switch (game) {
            case ARENA -> {
                requireInside(locations, regions, "team-a", "combat-floor", root, diagnostics);
                requireInside(locations, regions, "team-b", "combat-floor", root, diagnostics);
                requireInside(locations, regions, "recovery", "spectator-benches", root, diagnostics);
            }
            case HOT_POTATO -> requireInside(locations, regions, "arena", "arena", root, diagnostics);
            case KNOCKBACK_SUMO -> {
                requireInside(locations, regions, "side-a", "platform", root, diagnostics);
                requireInside(locations, regions, "side-b", "platform", root, diagnostics);
                requireRegion(regions, "boundary", root, diagnostics);
            }
            case CHECKPOINT_PARKOUR -> requireInside(locations, regions, "start", "course-boundary", root, diagnostics);
            case ARCHERY_RANGE -> {
                if (!locations.containsKey("exit")) diagnostics.add(new ResolutionDiagnostic("LOCATION_MISSING",
                        root + ".locations.exit", "É necessário configurar a saída do campo de tiro."));
                if (!regions.containsKey("range-boundary")) diagnostics.add(new ResolutionDiagnostic("REGION_MISSING",
                        root + ".regions.range-boundary", "É necessário configurar o limite do campo de tiro."));
            }
            case ANVIL_DODGE, COLOR_FLOOR -> {
                requireInside(locations, regions, "start", "floor", root, diagnostics);
                requireRegion(regions, "boundary", root, diagnostics);
            }
            case ELYTRA_RINGS -> requireInside(locations, regions, "start", "course-boundary", root, diagnostics);
            default -> { }
        }
    }

    private void requireInside(Map<String, Location> locations, Map<String, CuboidRegion> regions, String locationId,
                               String regionId, String root, List<ResolutionDiagnostic> diagnostics) {
        Location location = locations.get(locationId);
        CuboidRegion region = regions.get(regionId);
        if (location == null) {
            diagnostics.add(new ResolutionDiagnostic("LOCATION_MISSING", root + ".locations." + locationId,
                    "É necessário configurar o ponto de entrada do módulo."));
            return;
        }
        if (region == null) {
            diagnostics.add(new ResolutionDiagnostic("REGION_MISSING", root + ".regions." + regionId,
                    "É necessário configurar a região participante do módulo."));
            return;
        }
        if (!region.contains(location)) {
            diagnostics.add(new ResolutionDiagnostic("LOCATION_OUTSIDE_REGION",
                    root + ".locations." + locationId,
                    "O ponto de entrada tem de estar dentro da região participante validada."));
        }
    }

    private void requireRegion(Map<String, CuboidRegion> regions, String regionId, String root,
                               List<ResolutionDiagnostic> diagnostics) {
        if (!regions.containsKey(regionId)) diagnostics.add(new ResolutionDiagnostic("REGION_MISSING",
                root + ".regions." + regionId, "É necessário configurar a região de segurança do módulo."));
    }

    private Optional<ResolvedGameConfiguration> resolveGame(Context context) {
        try {
            return switch (context.game) {
                case ARENA -> resolveArena(context).map(value -> (ResolvedGameConfiguration) value);
                case BUILD_BATTLE -> resolveBuildBattle(context).map(value -> (ResolvedGameConfiguration) value);
                case HOT_POTATO -> resolveHotPotato(context).map(value -> (ResolvedGameConfiguration) value);
                case KNOCKBACK_SUMO -> resolveSumo(context).map(value -> (ResolvedGameConfiguration) value);
                case CHECKPOINT_PARKOUR -> resolveParkour(context).map(value -> (ResolvedGameConfiguration) value);
                case ARCHERY_RANGE -> resolveArchery(context).map(value -> (ResolvedGameConfiguration) value);
                case ANVIL_DODGE -> resolveAnvil(context).map(value -> (ResolvedGameConfiguration) value);
                case COLOR_FLOOR -> resolveColorFloor(context).map(value -> (ResolvedGameConfiguration) value);
                case ELYTRA_RINGS -> resolveElytra(context).map(value -> (ResolvedGameConfiguration) value);
            };
        } catch (RuntimeException failure) {
            context.fail("DOMAIN_CONFIG_INVALID", context.root,
                    "As regras do módulo não podem ser construídas a partir da configuração atual.");
            return Optional.empty();
        }
    }

    private Optional<ResolvedColiseumConfiguration> resolveArena(Context c) {
        int minimum = c.values.requiredInteger("minimum-players", 2, 64);
        int maxTeam = c.values.requiredInteger("max-team-size", 1, 3);
        boolean asymmetric = c.values.requiredBool("allow-asymmetric-formats");
        Set<ArenaFormat> asymmetricFormats = parseFormats(c, c.values.stringList("allowed-asymmetric-formats", 32, 16), asymmetric);
        ArenaFormatPolicy policy = new ArenaFormatPolicy(maxTeam, asymmetric, asymmetricFormats);
        Set<ArenaKitMode> kitModes = parseKitModes(c, c.values.requiredStringList("kit-modes", 8, 32));
        Map<String, List<Material>> fixedKits = parseFixedKits(c);
        if (kitModes.contains(ArenaKitMode.FIXED) && fixedKits.isEmpty()) {
            c.fail("KIT_MISSING", c.root + ".fixed-kits", "O modo de kits fixos exige pelo menos um kit definido.");
        }
        Set<Material> protectedMaterials = new LinkedHashSet<>(c.values.materials("protected-survival.prohibited-materials", 256));
        Duration round = c.values.requiredDurationSeconds("round-seconds", 1, 86_400);
        Duration reconnect = c.values.requiredDurationSeconds("reconnect-grace-seconds", 0, 3_600);
        boolean friendlyFire = c.values.requiredBool("friendly-fire");
        boolean staked = c.values.requiredBool("staked-survival.enabled");
        Set<ArenaFormat> stakedFormats = parseFormats(c, c.values.stringList("staked-survival.allowed-formats", 16, 16), false);
        Set<Material> stakedMaterials = new LinkedHashSet<>(c.values.materials("staked-survival.prohibited-materials", 256));
        Duration consent = c.values.requiredDurationSeconds("staked-survival.consent-timeout-seconds", 1, 3_600);
        String policyName = c.values.requiredString("staked-survival.no-contest-policy", 64);
        if (staked && !stakedFormats.isEmpty() && stakedFormats.stream().anyMatch(ArenaFormat::isAsymmetric)) {
            c.fail("FORMAT_INVALID", c.root + ".staked-survival.allowed-formats",
                    "O formato com equipamento apostado tem de ser simétrico.");
        }
        return Optional.of(new ResolvedColiseumConfiguration(c.world, c.locations, c.regions, optionalRevision(c, "arena-1"),
                IsolationPolicy.strictNoProgress(), minimum, policy, kitModes, fixedKits, protectedMaterials, friendlyFire, round, reconnect,
                staked, stakedFormats, stakedMaterials, consent, policyName));
    }

    private Optional<ResolvedBuildBattleConfiguration> resolveBuildBattle(Context c) {
        int minimum = c.values.requiredInteger("minimum-players", 2, 64);
        int maximum = c.values.requiredInteger("maximum-players", minimum, 64);
        Duration queue = c.values.requiredDurationSeconds("queue-seconds", 1, 3_600);
        Duration themeVote = c.values.requiredDurationSeconds("theme-vote-seconds", 1, 3_600);
        Duration build = c.values.requiredDurationSeconds("build-seconds", 1, 86_400);
        int plotSize = c.values.requiredInteger("plot-size", 3, 128);
        int plotSpacing = c.values.requiredInteger("plot-spacing", 0, 64);
        List<BuildBattleTheme> themeValues = new ArrayList<>();
        for (String raw : c.values.requiredStringList("theme-pool", 256, 128)) {
            String[] parts = raw.split("[:|]", 2);
            String id = parts[0].trim();
            String display = parts.length == 2 ? parts[1].trim() : id;
            try { themeValues.add(new BuildBattleTheme(id, display)); }
            catch (RuntimeException invalid) { c.fail("THEME_INVALID", "modules.build-battle.theme-pool", "A lista de temas contém um item inválido."); }
        }
        BuildBattleThemePool themes;
        try {
            themes = new BuildBattleThemePool(themeValues);
        } catch (RuntimeException invalid) {
            c.fail("THEMES_INVALID", c.root + ".theme-pool", "É necessário definir uma lista de temas única e não vazia.");
            themes = new BuildBattleThemePool(List.of(new BuildBattleTheme("unresolved", "Tema por configurar")));
        }
        int minimumScore = c.values.requiredInteger("voting.minimum-score", 1, 10);
        int maximumScore = c.values.requiredInteger("voting.maximum-score", minimumScore, 10);
        int secondsPerPlot = c.values.requiredInteger("voting.seconds-per-plot", 1, 600);
        BuildBattleVotingCompletionPolicy completion = parseCompletionPolicy(c,
                c.values.requiredString("voting.completion-policy", 128));
        BuildBattleTiePolicy ties = parseTiePolicy(c, c.values.requiredString("voting.tie-policy", 128));
        BuildBattleConfig domain = new BuildBattleConfig(minimum, maximum, minimumScore, maximumScore,
                themes.themes().get(0), completion, ties);
        Map<String, ResolvedBuildBattleConfiguration.PlotDefinition> plots = parsePlots(c);
        if (plots.size() < maximum) {
            c.fail("PLOTS_UNRESOLVED", c.root + ".plots",
                    "Cada participante precisa de um lote com região, ponto de entrada e reset validáveis.");
        }
        String marker = c.values.requiredString("world-template-marker", 128);
        TemplateResolution reset = TemplateResolution.readyForAdapter(marker);
        Duration resetTimeout = c.values.requiredDurationSeconds("reset.timeout-seconds", 1, 3_600);
        String resetStrategy = c.values.requiredString("reset.strategy", 64);
        if (!resetStrategy.equalsIgnoreCase("template-copy")) {
            c.fail("RESET_STRATEGY_UNKNOWN", c.root + ".reset.strategy", "A estratégia de reset não é permitida.");
        }
        return Optional.of(new ResolvedBuildBattleConfiguration(c.world, c.locations, c.regions,
                optionalRevision(c, "build-battle-1"), IsolationPolicy.strictNoProgress(), domain, themes, marker, plots, queue, themeVote, build,
                Duration.ofSeconds(secondsPerPlot), plotSize, plotSpacing, secondsPerPlot, reset, resetTimeout));
    }

    private Optional<ResolvedHotPotatoConfiguration> resolveHotPotato(Context c) {
        int minimum = c.values.requiredInteger("minimum-players", 2, 64);
        int maximum = c.values.requiredInteger("maximum-players", minimum, 64);
        Duration initial = c.values.requiredDurationSeconds("initial-fuse-seconds", 1, 3_600);
        Duration minimumFuse = c.values.requiredDurationSeconds("minimum-fuse-seconds", 1, 3_600);
        Duration reduction = c.values.requiredDurationSeconds("fuse-reduction-per-round-seconds", 0, 3_600);
        Duration cooldown = c.values.requiredDurationMilliseconds("pass-cooldown-milliseconds", 0, 60_000);
        double range = c.values.requiredDecimal("pass-range-blocks", 0.1, 32);
        Duration timeout = c.values.requiredDurationSeconds("match-timeout-seconds", 1, 86_400);
        if (minimumFuse.compareTo(initial) > 0) c.fail("FUSE_RULE_INVALID", c.root + ".minimum-fuse-seconds", "O fusível mínimo não pode exceder o inicial.");
        if (reduction.compareTo(initial) > 0) c.fail("FUSE_RULE_INVALID", c.root + ".fuse-reduction-per-round-seconds", "A redução do fusível excede o fusível inicial.");
        List<Location> spawns = parseSpawns(c);
        if (spawns.size() < maximum) c.fail("SPAWNS_UNRESOLVED", c.root + ".spawns", "Faltam pontos de entrada individuais para todos os participantes.");
        HotPotatoConfig domain = new HotPotatoConfig(minimum, maximum, initial, minimumFuse, cooldown, range, timeout,
                optionalRevision(c, "hot-potato-1"), reduction);
        return Optional.of(new ResolvedHotPotatoConfiguration(c.world, c.locations, c.regions, domain, spawns,
                initial, minimumFuse, reduction, Duration.ofSeconds(20), Duration.ofMinutes(10)));
    }

    private Optional<ResolvedSumoConfiguration> resolveSumo(Context c) {
        int minimum = c.values.requiredInteger("minimum-players", 2, 2);
        int maximum = c.values.requiredInteger("maximum-players", minimum, 2);
        int rounds = c.values.requiredInteger("best-of-rounds", 1, 99);
        Duration timeout = c.values.requiredDurationSeconds("round-timeout-seconds", 1, 3_600);
        int fall = c.values.requiredInteger("fall-threshold-y", -64, 320);
        int level = c.values.requiredInteger("knockback-level", 1, 10);
        String materialName = c.values.requiredString("knockback-item-material", 128);
        Material material = Material.matchMaterial(materialName.toUpperCase(Locale.ROOT));
        if (material == null) {
            c.fail("MATERIAL_UNKNOWN", c.root + ".knockback-item-material", "O material de knockback é desconhecido.");
            material = Material.STICK;
        }
        SumoConfig domain = new SumoConfig(optionalRevision(c, "sumo-1"), minimum, maximum, rounds, timeout);
        return Optional.of(new ResolvedSumoConfiguration(c.world, c.locations, c.regions, domain,
                IsolationPolicy.strictNoProgress(), material, level, fall));
    }

    private Optional<ResolvedParkourConfiguration> resolveParkour(Context c) {
        List<String> order = c.values.requiredStringList("checkpoint-order", MAX_LIST_ITEMS, 64);
        if (new LinkedHashSet<>(order).size() != order.size()) c.fail("DUPLICATE_ID", c.root + ".checkpoint-order", "A ordem contém IDs repetidos.");
        Map<String, CuboidRegion> checkpointRegions = parseDynamicRegions(c, "checkpoint-regions.");
        rejectOverlaps(c, checkpointRegions, "checkpoint-regions");
        if (!checkpointRegions.keySet().equals(new LinkedHashSet<>(order))) {
            c.fail("CHECKPOINT_ORDER_INVALID", c.root + ".checkpoint-regions", "Cada checkpoint ordenado precisa de exatamente uma região.");
        }
        Duration timeout = c.values.requiredDurationSeconds("run-timeout-seconds", 1, 86_400);
        Duration penalty = c.values.requiredDurationMilliseconds("fall-penalty-milliseconds", 0, 600_000);
        int concurrent = c.values.requiredInteger("concurrent-runners", 1, 64);
        boolean hide = c.values.requiredBool("hide-other-runners");
        ParkourConfig domain = new ParkourConfig(optionalRevision(c, "parkour-1"), order.size(), penalty, timeout);
        return Optional.of(new ResolvedParkourConfiguration(c.world, c.locations, c.regions, domain,
                IsolationPolicy.strictNoProgress(), order, checkpointRegions, concurrent, hide));
    }

    private Optional<ResolvedArcheryConfiguration> resolveArchery(Context c) {
        int shots = c.values.requiredInteger("shots-per-attempt", 1, 128);
        Duration timeout = c.values.requiredDurationSeconds("attempt-timeout-seconds", 1, 3_600);
        Map<String, Integer> scoreBands = new LinkedHashMap<>();
        for (String id : List.of("bullseye", "inner", "middle", "outer")) {
            scoreBands.put(id, c.values.requiredInteger("target-scores." + id, 0, 100));
        }
        String bowName = c.values.requiredString("bow-material", 128);
        Material bow = Material.matchMaterial(bowName.toUpperCase(Locale.ROOT));
        if (bow == null) {
            c.fail("MATERIAL_UNKNOWN", c.root + ".bow-material", "O material do arco é desconhecido.");
            bow = Material.BOW;
        }
        Map<Integer, ResolvedArcheryConfiguration.LaneDefinition> lanes = new LinkedHashMap<>();
        for (String id : dynamicIds(c.values.all(), "lanes.")) {
            int laneId = parseLaneId(c, id);
            String base = "lanes." + id;
            String regionId = optionalString(c, base + ".region-id", optionalString(c, base + ".region", id));
            String targetId = optionalString(c, base + ".target-id", "target-" + id);
            if (!c.regions.containsKey(regionId)) {
                c.fail("REGION_MISSING", c.root + "." + base, "A lane tem de referir uma região configurada.");
            }
            Optional<Location> spawn = parseNestedLocation(c, base + ".spawn", base);
            if (spawn.isEmpty()) {
                c.fail("SPAWN_UNRESOLVED", c.root + "." + base, "A lane precisa de um ponto de entrada configurado.");
                continue;
            }
            TemplateResolution target = TemplateResolution.readyForAdapter(targetId);
            ArcheryConfig domain = new ArcheryConfig(optionalRevision(c, "archery-1"), laneId, shots,
                    scoreBands.getOrDefault("bullseye", 1));
            try {
                if (lanes.put(laneId, new ResolvedArcheryConfiguration.LaneDefinition(laneId, regionId,
                        spawn.orElseThrow(), targetId, target, domain)) != null) {
                    c.fail("DUPLICATE_ID", c.root + ".lanes", "Existem lanes com o mesmo ID numérico.");
                }
            } catch (RuntimeException invalid) {
                c.fail("LANE_INVALID", c.root + "." + base, "A definição da lane é inválida.");
            }
        }
        if (lanes.isEmpty()) c.fail("LANES_MISSING", c.root + ".lanes", "É necessário configurar pelo menos uma lane.");
        return Optional.of(new ResolvedArcheryConfiguration(c.world, c.locations, c.regions,
                optionalRevision(c, "archery-1"), IsolationPolicy.strictNoProgress(), lanes, scoreBands, shots, timeout, bow));
    }

    private Optional<ResolvedAnvilDodgeConfiguration> resolveAnvil(Context c) {
        int minimum = c.values.requiredInteger("minimum-players", 1, 64);
        int maximum = c.values.requiredInteger("maximum-players", minimum, 64);
        int waves = c.values.requiredInteger("wave-count", 1, 10_000);
        Duration warning = c.values.requiredDurationTicks("warning-ticks", 1, 72_000);
        Duration interval = c.values.requiredDurationTicks("wave-interval-ticks", 2, 144_000);
        int hazards = c.values.requiredInteger("hazards-per-wave-start", 1, 4_096);
        int increment = c.values.requiredInteger("hazards-per-wave-increment", 0, 4_096);
        String revision = optionalRevision(c, "anvil-dodge-1");
        long seed = optionalSeed(c, "anvil-dodge", revision);
        CuboidRegion floor = c.regions.get("floor");
        int width = 1;
        int depth = 1;
        if (floor == null) {
            c.fail("REGION_MISSING", c.root + ".regions.floor", "A grelha da bigornas precisa de uma região de chão.");
        } else {
            width = floor.maxX() - floor.minX() + 1;
            depth = floor.maxZ() - floor.minZ() + 1;
            if (width * (long) depth > 4_096) c.fail("GEOMETRY_UNSAFE", c.root + ".regions.floor", "A grelha excede o limite seguro de células.");
        }
        AnvilDodgeConfig domain = new AnvilDodgeConfig(minimum, maximum, waves, warning, interval,
                seed, revision, IsolationPolicy.strictNoProgress(), width * depth, hazards, increment);
        return Optional.of(new ResolvedAnvilDodgeConfiguration(c.world, c.locations, c.regions, revision, domain,
                hazards, increment, width, depth, TemplateResolution.readyForAdapter("tagged-anvil-hazard")));
    }

    private Optional<ResolvedColorFloorConfiguration> resolveColorFloor(Context c) {
        int minimum = c.values.requiredInteger("minimum-players", 1, 64);
        int maximum = c.values.requiredInteger("maximum-players", minimum, 64);
        int rounds = c.values.requiredInteger("rounds", 1, 10_000);
        Duration announce = c.values.requiredDurationTicks("announce-ticks", 1, 72_000);
        Duration unsafe = c.values.requiredDurationTicks("unsafe-ticks", 1, 72_000);
        List<FloorColor> palette = parsePalette(c, c.values.requiredStringList("palette", 16, 32));
        String strategy = c.values.requiredString("restore-strategy", 64);
        if (!strategy.equalsIgnoreCase("immutable-template")) c.fail("RESET_STRATEGY_UNKNOWN",
                c.root + ".restore-strategy", "O chão só pode usar o template imutável validado.");
        String revision = optionalRevision(c, "color-floor-1");
        ColorFloorConfig domain = new ColorFloorConfig(minimum, maximum, rounds, unsafe,
                optionalSeed(c, "color-floor", revision), revision,
                IsolationPolicy.strictNoProgress(), palette);
        TemplateResolution template = TemplateResolution.readyForAdapter("immutable-floor-template");
        return Optional.of(new ResolvedColorFloorConfiguration(c.world, c.locations, c.regions, revision, domain,
                palette, announce, unsafe, strategy, template));
    }

    private Optional<ResolvedElytraRingsConfiguration> resolveElytra(Context c) {
        String marker = c.values.requiredString("world-template-marker", 128);
        List<String> order = c.values.requiredStringList("ring-order", MAX_LIST_ITEMS, 64);
        if (new LinkedHashSet<>(order).size() != order.size()) c.fail("DUPLICATE_ID", c.root + ".ring-order", "A ordem contém IDs repetidos.");
        Map<String, CuboidRegion> ringRegions = parseDynamicRegions(c, "ring-regions.");
        rejectOverlaps(c, ringRegions, "ring-regions");
        if (!ringRegions.keySet().equals(new LinkedHashSet<>(order))) c.fail("RING_ORDER_INVALID",
                c.root + ".ring-regions", "Cada anel ordenado precisa de exatamente uma região.");
        String revision = c.values.requiredString("course-revision", 32);
        Duration timeout = c.values.requiredDurationSeconds("run-timeout-seconds", 1, 86_400);
        int preload = c.values.requiredInteger("preload-radius-chunks", 0, 8);
        int rockets = c.values.requiredInteger("firework-rockets", 0, 64);
        boolean allowRockets = c.values.requiredBool("allow-rockets");
        int concurrent = c.values.requiredInteger("concurrent-runners", 1, 64);
        if (concurrent != 1) c.fail("CONCURRENCY_UNSUPPORTED", c.root + ".concurrent-runners",
                "A implementação atual suporta apenas uma corrida de Elytra por vez.");
        List<RingCheckpoint> checkpoints = new ArrayList<>();
        for (int index = 0; index < order.size(); index++) {
            CuboidRegion region = ringRegions.get(order.get(index));
            if (region == null) continue;
            checkpoints.add(new RingCheckpoint(index + 1,
                    (region.minX() + region.maxX()) / 2.0,
                    (region.minY() + region.maxY()) / 2.0,
                    (region.minZ() + region.maxZ()) / 2.0));
        }
        ElytraCourseRevision course = new ElytraCourseRevision(revision, c.world.getUID().toString(), checkpoints);
        ElytraRingsConfig domain = ElytraRingsConfig.dedicatedWorld(timeout, course);
        TemplateResolution rings = TemplateResolution.readyForAdapter("ordered-ring-targets");
        return Optional.of(new ResolvedElytraRingsConfiguration(c.world, c.locations, c.regions, domain, course,
                marker, order, ringRegions, preload, rockets, allowRockets, concurrent, 3.0, rings));
    }

    private Set<ArenaKitMode> parseKitModes(Context c, List<String> raw) {
        Set<ArenaKitMode> result = EnumSet.noneOf(ArenaKitMode.class);
        for (String item : raw) {
            try { result.add(ArenaKitMode.valueOf(item.toUpperCase(Locale.ROOT).replace('-', '_'))); }
            catch (IllegalArgumentException invalid) { c.fail("ENUM_UNKNOWN", c.root + ".kit-modes", "A lista contém um modo de equipamento desconhecido."); }
        }
        return result;
    }

    private Set<ArenaFormat> parseFormats(Context c, List<String> raw, boolean asymmetric) {
        Set<ArenaFormat> result = new LinkedHashSet<>();
        for (String item : raw) {
            try { result.add(ArenaFormat.parse(item, asymmetric)); }
            catch (IllegalArgumentException invalid) { c.fail("FORMAT_INVALID", c.root + ".allowed-asymmetric-formats", "A lista contém um formato inválido ou não permitido."); }
        }
        return result;
    }

    private BuildBattleVotingCompletionPolicy parseCompletionPolicy(Context c, String raw) {
        String normalized = raw.toUpperCase(Locale.ROOT).replace('-', '_');
        if (normalized.equals("EVERY_ELIGIBLE_VOTER_RATES_EVERY_OTHER_PLOT")) {
            return BuildBattleVotingCompletionPolicy.EVERY_ELIGIBLE_VOTER_RATES_EVERY_OTHER_PLOT;
        }
        c.fail("ENUM_UNKNOWN", c.root + ".voting.completion-policy", "A política de votação é desconhecida.");
        return BuildBattleVotingCompletionPolicy.EVERY_ELIGIBLE_VOTER_RATES_EVERY_OTHER_PLOT;
    }

    private BuildBattleTiePolicy parseTiePolicy(Context c, String raw) {
        String normalized = raw.toUpperCase(Locale.ROOT).replace('-', '_');
        if (normalized.equals("AVERAGE_THEN_TOTAL_THEN_STABLE_PLOT_ID") || normalized.equals("AVERAGE_THEN_TOTAL_THEN_PLOT_ID")) {
            return BuildBattleTiePolicy.AVERAGE_THEN_TOTAL_THEN_PLOT_ID;
        }
        c.fail("ENUM_UNKNOWN", c.root + ".voting.tie-policy", "A política de desempate é desconhecida.");
        return BuildBattleTiePolicy.AVERAGE_THEN_TOTAL_THEN_PLOT_ID;
    }

    private List<FloorColor> parsePalette(Context c, List<String> raw) {
        List<FloorColor> result = new ArrayList<>();
        for (String item : raw) {
            try { result.add(FloorColor.valueOf(item.toUpperCase(Locale.ROOT).replace('-', '_'))); }
            catch (IllegalArgumentException invalid) { c.fail("ENUM_UNKNOWN", c.root + ".palette", "A paleta contém uma cor desconhecida."); }
        }
        return List.copyOf(new LinkedHashSet<>(result));
    }

    private Map<String, List<Material>> parseFixedKits(Context c) {
        Map<String, List<Material>> kits = new LinkedHashMap<>();
        for (String id : dynamicIds(c.values.all(), "fixed-kits.")) {
            Object raw = c.values.raw("fixed-kits." + id);
            if (!(raw instanceof List<?>)) {
                c.fail("KIT_INVALID", c.root + ".fixed-kits." + id, "Cada kit tem de ser uma lista de materiais.");
                continue;
            }
            List<Material> parsed = c.values.materials("fixed-kits." + id, 128);
            if (parsed.isEmpty()) c.fail("KIT_INVALID", c.root + ".fixed-kits." + id, "O kit não pode estar vazio.");
            else kits.put(id, parsed);
        }
        return Map.copyOf(kits);
    }

    private Map<String, ResolvedBuildBattleConfiguration.PlotDefinition> parsePlots(Context c) {
        Map<String, ResolvedBuildBattleConfiguration.PlotDefinition> plots = new LinkedHashMap<>();
        for (String id : dynamicIds(c.values.all(), "plots.")) {
            String base = "plots." + id;
            String region = optionalString(c, base + ".region-id", id);
            Optional<Location> spawn = parseNestedLocation(c, base + ".spawn", base);
            if (spawn.isEmpty()) continue;
            plots.put(id, new ResolvedBuildBattleConfiguration.PlotDefinition(id, region, spawn.orElseThrow(),
                    TemplateResolution.unresolved("plot-" + id, "O reset do lote depende do template validado.")));
        }
        return Map.copyOf(plots);
    }

    private List<Location> parseSpawns(Context c) {
        List<String> ids = dynamicIds(c.values.all(), "spawns.").stream().sorted(Comparator.naturalOrder()).toList();
        List<Location> result = new ArrayList<>();
        for (String id : ids) parseNestedLocation(c, "spawns." + id, "spawns." + id).ifPresent(result::add);
        return List.copyOf(result);
    }

    private Map<String, CuboidRegion> parseDynamicRegions(Context c, String prefix) {
        Map<String, CuboidRegion> result = new LinkedHashMap<>();
        for (String id : dynamicIds(c.values.all(), prefix)) {
            String base = prefix + id;
            List<Integer> minimum = c.values.integerList(base + ".min", 3, -30_000_000, 30_000_000);
            List<Integer> maximum = c.values.integerList(base + ".max", 3, -30_000_000, 30_000_000);
            if (minimum.size() != 3 || maximum.size() != 3) {
                c.fail("REGION_INVALID", c.root + "." + base, "A região dinâmica precisa de min e max com três inteiros.");
                continue;
            }
            try {
                result.put(id, new CuboidRegion(c.world.getUID(), minimum.get(0), minimum.get(1), minimum.get(2),
                        maximum.get(0), maximum.get(1), maximum.get(2)));
            } catch (RuntimeException invalid) {
                c.fail("GEOMETRY_UNSAFE", c.root + "." + base, "A geometria dinâmica não é segura.");
            }
        }
        return Map.copyOf(result);
    }

    private void rejectOverlaps(Context c, Map<String, CuboidRegion> regions, String path) {
        List<Map.Entry<String, CuboidRegion>> entries = new ArrayList<>(regions.entrySet());
        for (int first = 0; first < entries.size(); first++) {
            for (int second = first + 1; second < entries.size(); second++) {
                if (entries.get(first).getValue().intersects(entries.get(second).getValue())) {
                    c.fail("GEOMETRY_OVERLAP", c.root + "." + path,
                            "As regiões ordenadas não podem sobrepor-se, porque isso permite validar dois pontos ao mesmo tempo.");
                }
            }
        }
    }

    private Optional<Location> parseNestedLocation(Context c, String primaryBase, String fallbackBase) {
        String base = hasLocationValues(c, primaryBase) ? primaryBase : fallbackBase;
        if (!hasLocationValues(c, base)) return Optional.empty();
        double x = c.values.requiredDecimal(base + ".x", -30_000_000, 30_000_000);
        double y = c.values.requiredDecimal(base + ".y", -2_048, 2_048);
        double z = c.values.requiredDecimal(base + ".z", -30_000_000, 30_000_000);
        double yaw = optionalDecimal(c, base + ".yaw", 0, -36_000, 36_000);
        double pitch = optionalDecimal(c, base + ".pitch", 0, -90, 90);
        return Optional.of(new Location(c.world, x, y, z, (float) yaw, (float) pitch));
    }

    private boolean hasLocationValues(Context c, String base) {
        return c.values.raw(base + ".x") != null || c.values.raw(base + ".y") != null || c.values.raw(base + ".z") != null
                || c.values.raw(base + ".yaw") != null || c.values.raw(base + ".pitch") != null;
    }

    private int parseLaneId(Context c, String id) {
        try {
            String digits = id.replaceFirst("^.*?([0-9]+)$", "$1");
            return Integer.parseInt(digits);
        } catch (RuntimeException invalid) {
            c.fail("LANE_INVALID", c.root + ".lanes." + id, "O ID da lane tem de terminar num número.");
            return 0;
        }
    }

    private String optionalRevision(Context c, String fallback) {
        return optionalString(c, "ruleset-revision", fallback);
    }

    private long optionalSeed(Context c, String game, String revision) {
        Object raw = c.values.raw("seed");
        if (raw != null) return c.values.requiredLong("seed", Long.MIN_VALUE, Long.MAX_VALUE);
        return UUID.nameUUIDFromBytes((game + ":" + c.world.getUID() + ":" + revision)
                .getBytes(StandardCharsets.UTF_8)).getMostSignificantBits();
    }

    private String optionalString(Context c, String path, String fallback) {
        return c.values.string(path, 128).orElse(fallback);
    }

    private double optionalDecimal(Context c, String path, double fallback, double min, double max) {
        return c.values.decimal(path, min, max).orElse(fallback);
    }

    private static List<String> dynamicIds(Map<String, Object> values, String prefix) {
        Set<String> result = new LinkedHashSet<>();
        for (String key : values.keySet()) {
            if (!key.startsWith(prefix)) continue;
            String rest = key.substring(prefix.length());
            int dot = rest.indexOf('.');
            String id = dot < 0 ? rest : rest.substring(0, dot);
            if (!id.isBlank()) result.add(id);
        }
        return List.copyOf(result);
    }

    private static ResolvedModuleConfiguration closed(GameKey game, List<ResolutionDiagnostic> diagnostics) {
        return new ResolvedModuleConfiguration(game, false, false, Optional.empty(), Optional.empty(),
                Map.of(), Map.of(), Optional.empty(), diagnostics);
    }

    private static List<ResolutionDiagnostic> allDiagnostics(List<ResolutionDiagnostic> global,
                                                              Map<GameKey, ResolvedModuleConfiguration> modules) {
        List<ResolutionDiagnostic> result = new ArrayList<>(global);
        for (ResolvedModuleConfiguration module : modules.values()) {
            for (ResolutionDiagnostic diagnostic : module.diagnostics()) {
                if (result.size() >= 4_096) return List.copyOf(result);
                result.add(diagnostic);
            }
        }
        return List.copyOf(result);
    }

    private static ResolutionDiagnostic fromProblem(ConfigurationProblem problem) {
        String message = switch (problem.code()) {
            case "SCHEMA_UNSUPPORTED" -> "A versão da configuração não é suportada.";
            case "LOCALE_UNSUPPORTED" -> "A localidade suportada é pt-PT.";
            case "WORLD_UNSET", "WORLD_INVALID" -> "O mundo precisa de nome e UUID válidos.";
            case "MODULE_SECTION_MISSING" -> "Falta a secção de configuração do módulo.";
            case "REGION_INVALID" -> "A geometria da região é inválida.";
            case "LOCATION_INVALID" -> "As coordenadas da localização são inválidas.";
            default -> "A configuração contém um problema e o módulo foi fechado.";
        };
        return new ResolutionDiagnostic(problem.code(), problem.path(), message);
    }

    private static void validateKeys(GameKey game, ConfigValues values, String root,
                                     List<ResolutionDiagnostic> diagnostics) {
        Set<String> exact = EXACT_KEYS.getOrDefault(game, Set.of());
        int emitted = 0;
        for (String key : values.all().keySet()) {
            if (COMMON_KEYS.contains(key) || exact.contains(key) || knownPrefix(game, key)) continue;
            if (emitted++ >= MAX_DIAGNOSTICS) break;
            diagnostics.add(new ResolutionDiagnostic("UNKNOWN_KEY", root + "." + key,
                    "A opção não pertence ao esquema conhecido deste módulo."));
        }
    }

    private static boolean knownPrefix(GameKey game, String key) {
        if (key.startsWith("locations.") || key.startsWith("regions.")) return true;
        return switch (game) {
            case ARENA -> key.startsWith("fixed-kits.") || key.startsWith("protected-survival.") || key.startsWith("staked-survival.");
            case BUILD_BATTLE -> key.startsWith("theme-pool.") || key.startsWith("voting.") || key.startsWith("reset.") || key.startsWith("plots.");
            case HOT_POTATO -> key.startsWith("spawns.");
            case CHECKPOINT_PARKOUR -> key.startsWith("checkpoint-regions.");
            case ARCHERY_RANGE -> key.startsWith("lanes.") || key.startsWith("target-scores.");
            case ELYTRA_RINGS -> key.startsWith("ring-regions.");
            default -> false;
        };
    }

    private static void validatePlaceholders(ConfigValues values, String root,
                                             List<ResolutionDiagnostic> diagnostics) {
        int[] emitted = {0};
        values.all().forEach((key, value) -> {
            if (emitted[0] < MAX_DIAGNOSTICS && containsPlaceholder(value)) {
                emitted[0]++;
                diagnostics.add(new ResolutionDiagnostic("PLACEHOLDER_UNRESOLVED",
                        root + "." + key, "A configuração ainda contém um marcador __SET_ME__."));
            }
        });
    }

    private static boolean containsPlaceholder(Object value) {
        if (value instanceof String text) return text.contains("__SET_ME__");
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) if (containsPlaceholder(item)) return true;
        }
        return false;
    }

    private static Map<GameKey, Set<String>> exactKeys() {
        EnumMap<GameKey, Set<String>> keys = new EnumMap<>(GameKey.class);
        keys.put(GameKey.ARENA, Set.of("minimum-players", "max-team-size", "allow-asymmetric-formats",
                "allowed-asymmetric-formats", "round-seconds", "reconnect-grace-seconds", "friendly-fire",
                "kit-modes", "staked-survival", "fixed-kits", "protected-survival", "staked-survival.enabled",
                "staked-survival.allowed-formats", "staked-survival.prohibited-materials", "staked-survival.consent-timeout-seconds",
                "staked-survival.no-contest-policy"));
        keys.put(GameKey.BUILD_BATTLE, Set.of("world-template-marker", "plots", "minimum-players", "maximum-players", "queue-seconds",
                "theme-vote-seconds", "build-seconds", "plot-size", "plot-spacing", "theme-pool", "voting", "voting.seconds-per-plot",
                "voting.minimum-score", "voting.maximum-score", "voting.completion-policy", "voting.tie-policy", "reset",
                "reset.strategy", "reset.timeout-seconds"));
        keys.put(GameKey.HOT_POTATO, Set.of("minimum-players", "maximum-players", "initial-fuse-seconds", "minimum-fuse-seconds",
                "fuse-reduction-per-round-seconds", "pass-cooldown-milliseconds", "pass-range-blocks", "match-timeout-seconds", "spawns"));
        keys.put(GameKey.KNOCKBACK_SUMO, Set.of("minimum-players", "maximum-players", "best-of-rounds", "round-timeout-seconds",
                "fall-threshold-y", "knockback-item-material", "knockback-level"));
        keys.put(GameKey.CHECKPOINT_PARKOUR, Set.of("checkpoint-order", "checkpoint-regions", "run-timeout-seconds",
                "fall-penalty-milliseconds", "concurrent-runners", "hide-other-runners"));
        keys.put(GameKey.ARCHERY_RANGE, Set.of("lanes", "shots-per-attempt", "attempt-timeout-seconds", "target-scores",
                "target-scores.bullseye", "target-scores.inner", "target-scores.middle", "target-scores.outer", "bow-material"));
        keys.put(GameKey.ANVIL_DODGE, Set.of("minimum-players", "maximum-players", "wave-count", "warning-ticks",
                "wave-interval-ticks", "hazards-per-wave-start", "hazards-per-wave-increment"));
        keys.put(GameKey.COLOR_FLOOR, Set.of("minimum-players", "maximum-players", "rounds", "announce-ticks", "unsafe-ticks",
                "palette", "restore-strategy"));
        keys.put(GameKey.ELYTRA_RINGS, Set.of("world-template-marker", "ring-order", "ring-regions", "course-revision",
                "run-timeout-seconds", "preload-radius-chunks", "firework-rockets", "allow-rockets", "concurrent-runners"));
        return Map.copyOf(keys);
    }

    private static final class Context {
        private final GameKey game;
        private final String root;
        private final ModuleConfiguration module;
        private final ResolvedValues values;
        private final World world;
        private final Map<String, Location> locations;
        private final Map<String, CuboidRegion> regions;
        private final List<ResolutionDiagnostic> diagnostics;

        private Context(GameKey game, String root, ModuleConfiguration module, ResolvedValues values, World world,
                        Map<String, Location> locations, Map<String, CuboidRegion> regions,
                        List<ResolutionDiagnostic> diagnostics) {
            this.game = game; this.root = root; this.module = module; this.values = values; this.world = world;
            this.locations = locations; this.regions = regions; this.diagnostics = diagnostics;
        }

        private void fail(String code, String path, String message) {
            if (diagnostics.size() < MAX_DIAGNOSTICS) diagnostics.add(new ResolutionDiagnostic(code, path, message));
        }
    }
}
