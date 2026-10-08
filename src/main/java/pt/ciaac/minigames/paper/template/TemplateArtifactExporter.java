package pt.ciaac.minigames.paper.template;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Create-only writer for checksum-verified template artifacts. */
public final class TemplateArtifactExporter {
    private static final Set<OpenOption> CREATE_ONLY_OPTIONS = Set.of(
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

    private TemplateArtifactExporter() { }

    /** Writes once, then verifies exact readback through the normal repository parser. */
    public static Path export(TemplateArtifact artifact, Path templatesDirectory) throws IOException {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(templatesDirectory, "templatesDirectory");
        if (!artifact.checksumMatches() || !artifact.coversVolume()) {
            throw new IllegalArgumentException("O artefacto não cobre o volume ou tem checksum inválido.");
        }

        Path root = templatesDirectory.toAbsolutePath().normalize();
        ensureSafeDirectory(root);
        Path file = root.resolve(artifact.artifactId() + ".template");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("O destino do template existe e não é um ficheiro regular seguro.");
            }
            throw new IOException("O template já existe; a exportação nunca substitui ficheiros.");
        }
        try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                CREATE_ONLY_OPTIONS.toArray(OpenOption[]::new))) {
            writer.write(serialize(artifact));
        }

        TemplateArtifactRepository repository = new TemplateArtifactRepository(root);
        TemplateArtifact inspected = repository.inspect(artifact.artifactId())
                .orElseThrow(() -> new IOException("O template escrito não passou a validação do repositório: "
                        + repository.lastDiagnostic().messagePtPt()));
        if (!artifact.equals(inspected) || !inspected.checksumMatches()) {
            throw new IOException("A leitura posterior do template não corresponde à captura.");
        }
        return file;
    }

    private static void ensureSafeDirectory(Path directory) throws IOException {
        Path current = directory.getRoot();
        if (current == null) throw new IOException("O diretório de templates tem de ser absoluto.");
        for (Path part : directory) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("O caminho de templates contém um componente que não é diretório seguro.");
                }
            } else {
                Files.createDirectory(current);
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Não foi possível criar um diretório de templates seguro.");
                }
            }
        }
    }

    private static String serialize(TemplateArtifact artifact) {
        StringBuilder text = new StringBuilder();
        text.append("format=ciaac-template-v1\n")
                .append("artifact-id=").append(artifact.artifactId()).append('\n')
                .append("revision=").append(artifact.revision()).append('\n')
                .append("world-uuid=").append(artifact.worldId()).append('\n')
                .append("world-name=").append(artifact.worldName()).append('\n')
                .append("bounds=").append(artifact.volume().worldId()).append(',')
                .append(artifact.volume().minX()).append(',').append(artifact.volume().minY()).append(',')
                .append(artifact.volume().minZ()).append(',').append(artifact.volume().maxX()).append(',')
                .append(artifact.volume().maxY()).append(',').append(artifact.volume().maxZ()).append('\n')
                .append("checksum-sha256=").append(artifact.checksumSha256()).append('\n');
        artifact.blockData().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparingInt(TemplateArtifact.BlockCoordinate::x)
                        .thenComparingInt(TemplateArtifact.BlockCoordinate::y)
                        .thenComparingInt(TemplateArtifact.BlockCoordinate::z)))
                .forEach(entry -> {
                    var coordinate = entry.getKey();
                    text.append("block=").append(coordinate.x()).append(',').append(coordinate.y()).append(',')
                            .append(coordinate.z()).append('|').append(entry.getValue()).append('\n');
                    String color = artifact.colorIds().get(coordinate);
                    if (color != null) {
                        text.append("color=").append(coordinate.x()).append(',').append(coordinate.y()).append(',')
                                .append(coordinate.z()).append('|').append(color).append('\n');
                    }
                });
        return text.toString();
    }
}
