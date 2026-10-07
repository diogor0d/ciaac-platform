package com.ciaac.minecraft.minigames.updater;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Read-only, bounded GitHub Releases client for one configured public repository. */
public final class GitHubReleaseClient {
    private static final String API_VERSION = "2026-03-10";
    private static final int MAXIMUM_RELEASE_JSON_BYTES = 2_000_000;
    private static final int MAXIMUM_METADATA_ASSET_BYTES = 128 * 1024;
    private final UpdaterConfiguration configuration;
    private final HttpClient client;

    public GitHubReleaseClient(UpdaterConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.client = HttpClient.newBuilder()
                .connectTimeout(configuration.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public LatestResponse latest(Optional<String> etag) throws IOException, InterruptedException {
        URI uri = releaseUri();
        HttpRequest.Builder request = request(uri, "application/vnd.github+json");
        etag.filter(value -> value.length() <= 512 && !value.contains("\r") && !value.contains("\n")
                && value.chars().allMatch(character -> character >= 0x21 && character <= 0x7e))
                .ifPresent(value -> request.header("If-None-Match", value));
        HttpResponse<InputStream> response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() == 304) {
            close(response.body());
            return LatestResponse.notModified(response.headers().firstValue("etag"));
        }
        if (response.statusCode() != 200) {
            close(response.body());
            throw new IOException("GitHub release request failed with HTTP " + response.statusCode());
        }
        if (response.headers().firstValue("link")
                .filter(value -> value.contains("rel=\"next\"")).isPresent()) {
            close(response.body());
            throw new IOException("GitHub release history exceeds the bounded discovery window");
        }
        byte[] body = readBounded(response.body(), MAXIMUM_RELEASE_JSON_BYTES);
        String json = strictUtf8(body);
        return LatestResponse.found(parseResponse(json), json, response.headers().firstValue("etag"));
    }

    String cacheSelector() {
        return configuration.allowPrereleases() ? "signed-prereleases-v1" : "stable-v1";
    }

    GitHubRelease parseResponse(String json) {
        return selectLatestRelease(json, configuration.allowPrereleases());
    }

    private URI releaseUri() {
        String base = "https://api.github.com/repos/" + configuration.owner() + "/"
                + configuration.repository() + "/releases";
        return URI.create(base + "?per_page=100");
    }

    public byte[] downloadMetadata(GitHubRelease.Asset asset) throws IOException, InterruptedException {
        Objects.requireNonNull(asset, "asset");
        if (asset.size() > MAXIMUM_METADATA_ASSET_BYTES) throw new IOException("Metadata asset is too large");
        try (InputStream input = download(asset, MAXIMUM_METADATA_ASSET_BYTES)) {
            return readBounded(input, MAXIMUM_METADATA_ASSET_BYTES);
        }
    }

    /** Caller owns and must close the bounded release-asset stream. */
    public InputStream downloadJar(GitHubRelease.Asset asset) throws IOException, InterruptedException {
        Objects.requireNonNull(asset, "asset");
        if (asset.size() < UpdaterConfiguration.MINIMUM_JAR_BYTES
                || asset.size() > configuration.maximumJarBytes()) {
            throw new IOException("JAR asset size is outside configured bounds");
        }
        return download(asset, configuration.maximumJarBytes());
    }

    private InputStream download(GitHubRelease.Asset asset, long maximum)
            throws IOException, InterruptedException {
        URI uri = assetUri(asset.id());
        for (int redirects = 0; redirects <= 3; redirects++) {
            HttpResponse<InputStream> response = client.send(
                    request(uri, "application/octet-stream").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                close(response.body());
                String location = response.headers().firstValue("location")
                        .orElseThrow(() -> new IOException("GitHub asset redirect omitted Location"));
                uri = validatedRedirect(uri.resolve(location));
                continue;
            }
            if (status != 200) {
                close(response.body());
                throw new IOException("GitHub asset request failed with HTTP " + status);
            }
            long declared = response.headers().firstValueAsLong("content-length").orElse(-1L);
            if (declared > maximum) {
                close(response.body());
                throw new IOException("GitHub asset exceeds the configured size bound");
            }
            return response.body();
        }
        throw new IOException("Too many GitHub asset redirects");
    }

    private HttpRequest.Builder request(URI uri, String accept) throws IOException {
        validateApiOrAssetUri(uri);
        return HttpRequest.newBuilder(uri)
                .timeout(configuration.requestTimeout())
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("User-Agent", "CIAACPlatform-Updater")
                .header("Cache-Control", "no-cache");
    }

    private URI assetUri(long assetId) {
        return URI.create("https://api.github.com/repos/" + configuration.owner() + "/"
                + configuration.repository() + "/releases/assets/" + assetId);
    }

    private URI validatedRedirect(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1) {
            throw new IOException("Unsafe GitHub asset redirect");
        }
        String host = Optional.ofNullable(uri.getHost()).orElse("").toLowerCase(Locale.ROOT);
        if (!(host.equals("github.com") || host.equals("api.github.com")
                || host.endsWith(".githubusercontent.com"))) {
            throw new IOException("GitHub asset redirect left the trusted host set");
        }
        return uri;
    }

    private static void validateApiOrAssetUri(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1) {
            throw new IOException("Unsafe GitHub request URI");
        }
        String host = Optional.ofNullable(uri.getHost()).orElse("").toLowerCase(Locale.ROOT);
        if (!(host.equals("api.github.com") || host.equals("github.com")
                || host.endsWith(".githubusercontent.com"))) {
            throw new IOException("GitHub request URI is outside the trusted host set");
        }
    }

    @SuppressWarnings("unchecked")
    static GitHubRelease parseRelease(String json) {
        Object root = StrictJson.parse(json);
        if (!(root instanceof Map<?, ?> map)) throw new IllegalArgumentException("release JSON is not an object");
        return parseReleaseMap(map);
    }

    static GitHubRelease selectLatestRelease(String json, boolean allowPrereleases) {
        Object root = StrictJson.parse(json);
        if (!(root instanceof List<?> values)) {
            throw new IllegalArgumentException("release JSON is not an array");
        }
        if (values.size() > 100) {
            throw new IllegalArgumentException("release list is outside its bound");
        }
        GitHubRelease selected = null;
        SemanticVersion selectedVersion = null;
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("release list contains an invalid entry");
            }
            GitHubRelease candidate = parseReleaseMap(map);
            if (candidate.draft() || !candidate.immutable()
                    || (candidate.prerelease() && !allowPrereleases)) continue;
            SemanticVersion version;
            try {
                version = SemanticVersion.parse(candidate.tag());
            } catch (RuntimeException invalid) {
                continue;
            }
            if (candidate.prerelease() != !version.prerelease().isEmpty()
                    || !candidate.tag().equals("v" + version)) {
                continue;
            }
            int comparison = selectedVersion == null ? 1 : version.compareTo(selectedVersion);
            if (comparison == 0) {
                throw new IllegalArgumentException("release list contains an ambiguous semantic version");
            }
            if (comparison > 0) {
                selected = candidate;
                selectedVersion = version;
            }
        }
        if (selected == null) {
            throw new NoEligibleReleaseException("release list contains no eligible version");
        }
        return selected;
    }

    static final class NoEligibleReleaseException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        NoEligibleReleaseException(String message) {
            super(message);
        }
    }

    private static GitHubRelease parseReleaseMap(Map<?, ?> map) {
        long id = integer(map, "id");
        String tag = string(map, "tag_name");
        boolean draft = bool(map, "draft");
        boolean prerelease = bool(map, "prerelease");
        boolean immutable = optionalBool(map, "immutable").orElse(false);
        Object rawAssets = map.get("assets");
        if (!(rawAssets instanceof List<?> values)) throw new IllegalArgumentException("assets are absent");
        List<GitHubRelease.Asset> assets = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> asset)) throw new IllegalArgumentException("invalid asset");
            Object digest = asset.get("digest");
            if (asset.containsKey("digest") && digest != null && !(digest instanceof String)) {
                throw new IllegalArgumentException("invalid asset digest");
            }
            assets.add(new GitHubRelease.Asset(
                    integer(asset, "id"), string(asset, "name"), integer(asset, "size"),
                    string(asset, "state"), digest instanceof String text && !text.isBlank()
                            ? Optional.of(text) : Optional.empty()));
        }
        return new GitHubRelease(id, tag, draft, prerelease, immutable, assets);
    }

    private static long integer(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) throw new IllegalArgumentException("missing numeric " + key);
        double asDouble = number.doubleValue();
        long asLong = number.longValue();
        if (!Double.isFinite(asDouble) || asDouble != asLong) throw new IllegalArgumentException("invalid numeric " + key);
        return asLong;
    }

    private static String string(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String text)) throw new IllegalArgumentException("missing text " + key);
        return text;
    }

    private static boolean bool(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Boolean result)) throw new IllegalArgumentException("missing boolean " + key);
        return result;
    }

    private static Optional<Boolean> optionalBool(Map<?, ?> map, String key) {
        if (!map.containsKey(key)) return Optional.empty();
        Object value = map.get(key);
        if (!(value instanceof Boolean result)) throw new IllegalArgumentException("invalid boolean " + key);
        return Optional.of(result);
    }

    static byte[] readBounded(InputStream input, long maximum) throws IOException {
        Objects.requireNonNull(input, "input");
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > maximum) throw new IOException("Response exceeded its size bound");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String strictUtf8(byte[] bytes) throws IOException {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("GitHub response is not strict UTF-8", invalid);
        }
    }

    private static void close(InputStream input) {
        try { input.close(); } catch (IOException ignored) { }
    }

    public record LatestResponse(
            Status status,
            Optional<GitHubRelease> release,
            Optional<String> rawJson,
            Optional<String> etag) {
        public enum Status { FOUND, NOT_MODIFIED }

        public LatestResponse {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(release, "release");
            Objects.requireNonNull(rawJson, "rawJson");
            Objects.requireNonNull(etag, "etag");
        }

        static LatestResponse found(GitHubRelease release, String rawJson, Optional<String> etag) {
            return new LatestResponse(Status.FOUND, Optional.of(release), Optional.of(rawJson), etag);
        }

        static LatestResponse notModified(Optional<String> etag) {
            return new LatestResponse(Status.NOT_MODIFIED, Optional.empty(), Optional.empty(), etag);
        }
    }
}
