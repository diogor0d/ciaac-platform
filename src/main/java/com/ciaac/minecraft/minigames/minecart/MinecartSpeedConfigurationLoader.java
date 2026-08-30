package com.ciaac.minecraft.minigames.minecart;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/** Strict loader for the independently reloadable minecart policy. */
public final class MinecartSpeedConfigurationLoader {
    public LoadResult load(File file) {
        Objects.requireNonNull(file, "file");
        try {
            String source = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            if (source.length() > 65_536) {
                return LoadResult.rejected("O ficheiro minecarts.yml excede o limite de 65536 caracteres.");
            }
            LoaderOptions strict = new LoaderOptions();
            strict.setAllowDuplicateKeys(false);
            strict.setMaxAliasesForCollections(16);
            strict.setNestingDepthLimit(16);
            strict.setCodePointLimit(65_536);
            new Yaml(new SafeConstructor(strict)).load(source);

            YamlConfiguration configuration = new YamlConfiguration();
            configuration.loadFromString(source);
            return load(configuration);
        } catch (IOException failure) {
            return LoadResult.rejected("Não foi possível ler minecarts.yml.");
        } catch (InvalidConfigurationException | YAMLException failure) {
            return LoadResult.rejected("minecarts.yml contém YAML inválido ou chaves repetidas.");
        }
    }

    public LoadResult load(ConfigurationSection source) {
        Objects.requireNonNull(source, "source");
        try {
            int schema = source.getInt("schema-version", -1);
            boolean enabled = source.getBoolean("enabled", false);
            double defaultSpeed = number(source, "default-terminal-speed-blocks-per-second",
                    MinecartSpeedConfiguration.DEFAULT_BLOCKS_PER_SECOND);
            Map<String, MinecartSpeedConfiguration.WorldOverride> worlds = new LinkedHashMap<>();
            ConfigurationSection entries = source.getConfigurationSection("worlds");
            if (entries != null) {
                for (String name : entries.getKeys(false)) {
                    ConfigurationSection entry = entries.getConfigurationSection(name);
                    if (entry == null) throw new IllegalArgumentException("A configuração do mundo " + name + " é inválida.");
                    String uuidText = requiredText(entry, "uuid", name);
                    double speed = number(entry, "terminal-speed-blocks-per-second", Double.NaN);
                    MinecartSpeedConfiguration.WorldOverride previous = worlds.putIfAbsent(name,
                            new MinecartSpeedConfiguration.WorldOverride(UUID.fromString(uuidText), speed));
                    if (previous != null) throw new IllegalArgumentException("O mundo " + name + " está repetido.");
                }
            }
            return LoadResult.accepted(new MinecartSpeedConfiguration(schema, enabled, defaultSpeed, worlds));
        } catch (IllegalArgumentException failure) {
            String message = failure.getMessage();
            return LoadResult.rejected(message == null || message.isBlank()
                    ? "A configuração dos carrinhos é inválida." : message);
        }
    }

    private static double number(ConfigurationSection source, String path, double fallback) {
        Object value = source.get(path);
        if (value == null) return fallback;
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("O valor " + path + " deve ser numérico.");
        }
        return number.doubleValue();
    }

    private static String requiredText(ConfigurationSection source, String path, String world) {
        String value = source.getString(path);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("O mundo " + world + " precisa de um UUID.");
        }
        return value.strip();
    }

    public record LoadResult(MinecartSpeedConfiguration configuration, String diagnosticPtPt) {
        public LoadResult {
            if ((configuration == null) == (diagnosticPtPt == null)) {
                throw new IllegalArgumentException("O resultado deve conter configuração ou diagnóstico.");
            }
        }
        static LoadResult accepted(MinecartSpeedConfiguration configuration) {
            return new LoadResult(Objects.requireNonNull(configuration, "configuration"), null);
        }
        static LoadResult rejected(String diagnostic) {
            return new LoadResult(null, Objects.requireNonNull(diagnostic, "diagnostic"));
        }
        public boolean accepted() { return configuration != null; }
    }
}
