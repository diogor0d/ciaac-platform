package com.ciaac.minecraft.minigames.configuration;

import com.ciaac.minecraft.minigames.core.GameKey;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public final class RuntimeConfigurationLoader {
    public static final int SUPPORTED_SCHEMA = 2;
    private static final List<String> REQUIRED_SAFETY_INVARIANTS = List.of(
            "admission.require-authenticated-adapter",
            "admission.block-while-recovery-pending",
            "progress-isolation.inventory",
            "progress-isolation.item-metadata-and-durability",
            "progress-isolation.armor",
            "progress-isolation.off-hand",
            "progress-isolation.cursor",
            "progress-isolation.ender-chest",
            "progress-isolation.experience",
            "progress-isolation.health-food-effects",
            "progress-isolation.location-flight-gamemode",
            "progress-isolation.vanilla-statistics",
            "progress-isolation.advancements",
            "progress-isolation.economy-permissions",
            "progress-isolation.claims-homes",
            "progress-isolation.scoreboards-cooldowns",
            "progress-isolation.temporary-world-blocks-and-entities");

    public RuntimeConfiguration load(FileConfiguration source) {
        List<ConfigurationProblem> global = new ArrayList<>();
        int schema = source.getInt("schema-version", -1);
        if (schema != SUPPORTED_SCHEMA) {
            global.add(problem("SCHEMA_UNSUPPORTED", "schema-version",
                    "Era esperado o esquema de configuração " + SUPPORTED_SCHEMA + "."));
        }
        String locale = source.getString("locale", "");
        if (!"pt-PT".equals(locale)) {
            global.add(problem("LOCALE_UNSUPPORTED", "locale", "Apenas pt-PT é suportado."));
            locale = "pt-PT";
        }
        boolean admission = source.getBoolean("admission.enabled", false);
        requireSafetyInvariants(source, global);
        Path sqlite = safeRelativePath(source.getString("persistence.sqlite-file", "platform.sqlite"),
                "persistence.sqlite-file", global);
        Path recovery = safeRelativePath(source.getString(
                "persistence.snapshot-journal-directory", "recovery"),
                "persistence.snapshot-journal-directory", global);
        AnnouncementConfiguration announcements = new AnnouncementConfiguration(
                Duration.ofSeconds(boundedLong(source, "announcements.minecraft.waiting-cooldown-seconds", 300, 0, 86_400, global)),
                source.getBoolean("announcements.discord.enabled", false),
                source.getString("announcements.discord.channel-name", "minigames"),
                Duration.ofSeconds(boundedLong(source, "announcements.discord.waiting-cooldown-seconds", 900, 0, 86_400, global)),
                source.getBoolean("announcements.discord.announce-starting", true),
                source.getBoolean("announcements.discord.announce-winner", true));

        EnumMap<GameKey, ModuleConfiguration> modules = new EnumMap<>(GameKey.class);
        for (GameKey game : GameKey.values()) {
            modules.put(game, loadModule(source, game));
        }
        return new RuntimeConfiguration(
                Math.max(schema, 1), admission, locale, sqlite, recovery, announcements, modules, global);
    }

    private ModuleConfiguration loadModule(FileConfiguration source, GameKey game) {
        String root = "modules." + game.id();
        ConfigurationSection section = source.getConfigurationSection(root);
        List<ConfigurationProblem> problems = new ArrayList<>();
        if (section == null) {
            problems.add(problem("MODULE_SECTION_MISSING", root, "Falta a secção de configuração do módulo."));
            return new ModuleConfiguration(
                    game, false, Optional.empty(), Map.of(), Map.of(), new ConfigValues(Map.of()), problems);
        }
        boolean enabled = section.getBoolean("enabled", false);
        Optional<WorldReference> world = parseWorld(section, root, enabled, problems);
        Map<String, LocationSpec> locations = new LinkedHashMap<>();
        Map<String, RegionSpec> regions = new LinkedHashMap<>();
        if (world.isPresent()) {
            parseLocations(section.getConfigurationSection("locations"), root, world.orElseThrow(), locations, problems);
            parseRegions(section.getConfigurationSection("regions"), root, world.orElseThrow(), regions, problems);
        }
        Map<String, Object> flattened = section.getValues(true);
        return new ModuleConfiguration(
                game, enabled, world, locations, regions, new ConfigValues(flattened), problems);
    }

    private static Optional<WorldReference> parseWorld(
            ConfigurationSection section,
            String root,
            boolean required,
            List<ConfigurationProblem> problems) {
        String name = section.getString("world.name", "");
        String rawUuid = section.getString("world.uuid", "");
        if (name.isBlank() || name.contains("__SET_ME__") || rawUuid.isBlank()
                || rawUuid.contains("__SET_ME__")) {
            if (required) {
                problems.add(problem("WORLD_UNSET", root + ".world",
                        "Os módulos ativos exigem nome e UUID explícitos do mundo."));
            }
            return Optional.empty();
        }
        try {
            return Optional.of(new WorldReference(name, UUID.fromString(rawUuid)));
        } catch (IllegalArgumentException exception) {
            problems.add(problem("WORLD_INVALID", root + ".world",
                    "O nome ou UUID do mundo é inválido."));
            return Optional.empty();
        }
    }

    private static void parseLocations(
            ConfigurationSection section,
            String root,
            WorldReference world,
            Map<String, LocationSpec> output,
            List<ConfigurationProblem> problems) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            String path = root + ".locations." + key;
            ConfigurationSection value = section.getConfigurationSection(key);
            if (value == null) continue;
            try {
                LocationSpec previous = output.put(key, new LocationSpec(
                        world,
                        value.getDouble("x"), value.getDouble("y"), value.getDouble("z"),
                        (float) value.getDouble("yaw", 0), (float) value.getDouble("pitch", 0)));
                if (previous != null) throw new IllegalArgumentException("duplicate location");
            } catch (IllegalArgumentException exception) {
                problems.add(problem("LOCATION_INVALID", path, "As coordenadas da localização são inválidas."));
            }
        }
    }

    private static void parseRegions(
            ConfigurationSection section,
            String root,
            WorldReference world,
            Map<String, RegionSpec> output,
            List<ConfigurationProblem> problems) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            String path = root + ".regions." + key;
            ConfigurationSection value = section.getConfigurationSection(key);
            if (value == null) continue;
            boolean fullHeight = false;
            if (value.isSet("full-height")) {
                if (!value.isBoolean("full-height")) {
                    problems.add(problem("REGION_INVALID", path + ".full-height",
                            "A opção full-height tem de ser um valor booleano explícito."));
                    continue;
                }
                fullHeight = value.getBoolean("full-height");
            }
            List<Integer> minimum = value.getIntegerList("min");
            List<Integer> maximum = value.getIntegerList("max");
            if (minimum.size() != 3 || maximum.size() != 3) {
                problems.add(problem("REGION_INVALID", path,
                        "Os limites mínimo e máximo da região têm de conter três coordenadas inteiras."));
                continue;
            }
            try {
                RegionSpec previous = output.put(key, new RegionSpec(
                        world,
                        minimum.get(0), minimum.get(1), minimum.get(2),
                        maximum.get(0), maximum.get(1), maximum.get(2), fullHeight));
                if (previous != null) throw new IllegalArgumentException("duplicate region");
            } catch (IllegalArgumentException exception) {
                problems.add(problem("REGION_INVALID", path, "Os limites da região são inválidos."));
            }
        }
    }

    private static Path safeRelativePath(String raw, String path, List<ConfigurationProblem> problems) {
        try {
            Path value = Path.of(raw).normalize();
            if (value.isAbsolute() || value.startsWith("..") || value.toString().isBlank()) {
                throw new IllegalArgumentException("unsafe path");
            }
            return value;
        } catch (RuntimeException exception) {
            problems.add(problem("PATH_INVALID", path,
                    "O caminho tem de ser relativo e permanecer no diretório de dados do plugin."));
            return Path.of(path.endsWith("sqlite-file") ? "platform.sqlite" : "recovery");
        }
    }

    private static long boundedLong(
            FileConfiguration source,
            String path,
            long fallback,
            long minimum,
            long maximum,
            List<ConfigurationProblem> problems) {
        long value = source.getLong(path, fallback);
        if (value < minimum || value > maximum) {
            problems.add(problem("NUMBER_OUT_OF_RANGE", path,
                    "O valor tem de estar entre " + minimum + " e " + maximum + "."));
            return fallback;
        }
        return value;
    }

    private static void requireSafetyInvariants(
            FileConfiguration source, List<ConfigurationProblem> problems) {
        for (String path : REQUIRED_SAFETY_INVARIANTS) {
            if (!source.isBoolean(path) || !source.getBoolean(path)) {
                problems.add(problem("SAFETY_INVARIANT_DISABLED", path,
                        "Esta invariante de segurança tem de ser explicitamente true; a plataforma não permite enfraquecê-la."));
            }
        }
    }

    private static ConfigurationProblem problem(String code, String path, String message) {
        return new ConfigurationProblem(code, path, message);
    }
}
