package pt.ciaac.minigames.paper.template;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.ciaac.minecraft.minigames.region.CuboidRegion;
import org.bukkit.World;

/** Read-only artifact repository. It never captures or mutates a live world. */
public final class TemplateArtifactRepository {
    private static final long MAX_FILE_BYTES = 64L * 1024L * 1024L;
    private final Path root;
    private volatile LoadDiagnostic lastDiagnostic = new LoadDiagnostic("NOT_ATTEMPTED", "Ainda não foi carregado nenhum artefacto.");

    public TemplateArtifactRepository(Path root) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public boolean hasArtifact(String artifactId) {
        try {
            Path file = artifactPath(artifactId);
            return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file)
                    && Files.size(file) <= MAX_FILE_BYTES;
        } catch (RuntimeException | IOException failure) {
            return false;
        }
    }

    /** Validates the immutable artifact without requiring a loaded world. */
    public Optional<TemplateArtifact> inspect(String artifactId) {
        try {
            requireSafeRoot();
            Path file = artifactPath(artifactId);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
                return fail("ARTIFACT_MISSING", "O artefacto de template não existe ou é um link simbólico.");
            }
            if (Files.size(file) > MAX_FILE_BYTES) {
                return fail("ARTIFACT_TOO_LARGE", "O artefacto de template excede o limite de leitura.");
            }
            TemplateArtifact artifact = parse(file);
            if (!artifact.artifactId().equals(artifactId)) {
                return fail("ARTIFACT_ID_MISMATCH", "O identificador do template não coincide.");
            }
            if (!artifact.checksumMatches()) {
                return fail("TEMPLATE_CHECKSUM_MISMATCH", "O checksum do template não coincide com o conteúdo.");
            }
            lastDiagnostic = new LoadDiagnostic("READY", "Artefacto validado sem aceder a blocos do mundo.");
            return Optional.of(artifact);
        } catch (RuntimeException | IOException failure) {
            return fail("ARTIFACT_INVALID", "O artefacto de template não passou a validação.");
        }
    }

    public LoadDiagnostic lastDiagnostic() {
        return lastDiagnostic;
    }

    public Optional<TemplateArtifact> load(
            String artifactId, World world, CuboidRegion expectedVolume, String expectedRevision) {
        try {
            requireSafeRoot();
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(expectedVolume, "expectedVolume");
            Objects.requireNonNull(expectedRevision, "expectedRevision");
            if (!expectedVolume.worldId().equals(world.getUID())) {
                return fail("WORLD_UUID_MISMATCH", "A região do template pertence a outro mundo.");
            }
            Path file = artifactPath(artifactId);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
                return fail("ARTIFACT_MISSING", "O artefacto de template não existe ou é um link simbólico.");
            }
            if (Files.size(file) > MAX_FILE_BYTES) {
                return fail("ARTIFACT_TOO_LARGE", "O artefacto de template excede o limite de leitura.");
            }
            TemplateArtifact artifact = parse(file);
            if (!artifact.artifactId().equals(artifactId)) {
                return fail("ARTIFACT_ID_MISMATCH", "O identificador do template não coincide.");
            }
            if (!artifact.revision().equals(expectedRevision)) {
                return fail("TEMPLATE_REVISION_MISMATCH", "A revisão do template não coincide com as regras.");
            }
            if (!artifact.worldId().equals(world.getUID()) || !artifact.worldName().equals(world.getName())) {
                return fail("WORLD_IDENTITY_MISMATCH", "UUID e nome do mundo não coincidem com o artefacto.");
            }
            if (!artifact.volume().equals(expectedVolume)) {
                return fail("TEMPLATE_VOLUME_MISMATCH", "O volume do template não coincide com a região configurada.");
            }
            if (!artifact.checksumMatches()) {
                return fail("TEMPLATE_CHECKSUM_MISMATCH", "O checksum do template não coincide com o conteúdo.");
            }
            lastDiagnostic = new LoadDiagnostic("READY", "Artefacto validado sem aceder a blocos do mundo.");
            return Optional.of(artifact);
        } catch (RuntimeException | IOException failure) {
            return fail("ARTIFACT_INVALID", "O artefacto de template não passou a validação.");
        }
    }

    private TemplateArtifact parse(Path file) throws IOException {
        Map<String, String> fields = new LinkedHashMap<>();
        Map<TemplateArtifact.BlockCoordinate, String> blocks = new LinkedHashMap<>();
        Map<TemplateArtifact.BlockCoordinate, String> colors = new LinkedHashMap<>();
        int lines = 0;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (++lines > TemplateArtifact.MAX_BLOCKS * 2L + 32L) throw new IOException("too many artifact lines");
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int separator = line.indexOf('=');
            if (separator <= 0 || separator == line.length() - 1) throw new IOException("malformed artifact line");
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (key.equals("block") || key.equals("color")) {
                int split = value.indexOf('|');
                if (split <= 0 || split == value.length() - 1) throw new IOException("malformed block entry");
                TemplateArtifact.BlockCoordinate coordinate = coordinate(value.substring(0, split));
                String data = value.substring(split + 1);
                Map<TemplateArtifact.BlockCoordinate, String> target = key.equals("block") ? blocks : colors;
                if (target.put(coordinate, data) != null) throw new IOException("duplicate artifact coordinate");
            } else {
                if (!Set.of("format", "artifact-id", "revision", "world-uuid", "world-name",
                        "bounds", "checksum-sha256").contains(key)) {
                    throw new IOException("unknown artifact field");
                }
                if (fields.put(key, value) != null) throw new IOException("duplicate artifact field");
            }
        }
        if (!"ciaac-template-v1".equals(fields.get("format"))) throw new IOException("unsupported artifact format");
        return new TemplateArtifact(
                required(fields, "artifact-id"), required(fields, "revision"),
                UUID.fromString(required(fields, "world-uuid")), required(fields, "world-name"),
                volume(required(fields, "bounds")), required(fields, "checksum-sha256"), blocks, colors);
    }

    private Path artifactPath(String artifactId) {
        if (artifactId == null || !artifactId.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("artifactId is invalid");
        }
        Path path = root.resolve(artifactId + ".template").normalize();
        if (!path.startsWith(root)) throw new IllegalArgumentException("artifact path escapes repository root");
        return path;
    }

    private void requireSafeRoot() throws IOException {
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("template repository root is not a regular directory");
            }
        }
    }

    private static TemplateArtifact.BlockCoordinate coordinate(String value) {
        String[] parts = value.split(",", -1);
        if (parts.length != 3) throw new IllegalArgumentException("coordinate must have three values");
        return new TemplateArtifact.BlockCoordinate(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    private static CuboidRegion volume(String value) {
        String[] parts = value.split(",", -1);
        if (parts.length != 7) throw new IllegalArgumentException("bounds must have world UUID and six coordinates");
        return new CuboidRegion(UUID.fromString(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]),
                Integer.parseInt(parts[4]), Integer.parseInt(parts[5]), Integer.parseInt(parts[6]));
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing artifact field " + key);
        return value;
    }

    private <T> Optional<T> fail(String code, String message) {
        lastDiagnostic = new LoadDiagnostic(code, message);
        return Optional.empty();
    }

    public record LoadDiagnostic(String code, String messagePtPt) {
        public LoadDiagnostic {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(messagePtPt, "messagePtPt");
        }
    }
}
