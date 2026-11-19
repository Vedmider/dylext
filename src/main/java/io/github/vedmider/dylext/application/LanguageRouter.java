package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.config.LanguageProperties;
import io.github.vedmider.dylext.adapter.out.parser.HttpSkeletonExtractor;
import io.github.vedmider.dylext.domain.port.out.SkeletonExtractor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Selects a {@link SkeletonExtractor} for a file by its extension, using the configured
 * extension→language map. In-process extractors (e.g. Java) arrive as beans; out-of-process
 * parsers are built from {@code dylext.parsers.<language>=<url>}. Adding a language is a config
 * line (+ a running sidecar) — this router and the indexing code never name a language.
 */
@Component
public class LanguageRouter {

    private final Map<String, String> extToLanguage;   // extension -> language id
    private final Map<String, SkeletonExtractor> byLanguage;
    private final Map<String, String> remoteUrls = new java.util.TreeMap<>();

    public LanguageRouter(List<SkeletonExtractor> extractors, LanguageProperties properties) {
        this.extToLanguage = properties.getLanguages().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().toLowerCase(), Map.Entry::getValue,
                        (a, b) -> a));

        List<SkeletonExtractor> all = new ArrayList<>(extractors);
        properties.getParsers().forEach((language, url) -> {
            if (url != null && !url.isBlank()) {
                remoteUrls.put(language, url);
                all.add(new HttpSkeletonExtractor(language, url));
            }
        });
        String fallbackUrl = properties.getFallbackParser();
        if (fallbackUrl != null && !fallbackUrl.isBlank()) {
            remoteUrls.put(GENERIC, fallbackUrl);
            all.add(new HttpSkeletonExtractor(GENERIC, fallbackUrl,
                    io.github.vedmider.dylext.domain.model.CapabilityTier.T1_STRUCTURE));
        }
        this.byLanguage = all.stream()
                .collect(Collectors.toMap(SkeletonExtractor::language, Function.identity(),
                        (inProcess, remote) -> inProcess));   // in-process wins on conflict
    }

    /** Pseudo-language id of the configured generic (tree-sitter) fallback parser. */
    public static final String GENERIC = "generic";

    public List<io.github.vedmider.dylext.domain.port.in.RepoCatalogUseCase.LanguageInfo> describe() {
        java.util.Set<String> languages = new java.util.TreeSet<>(extToLanguage.values());
        languages.addAll(byLanguage.keySet());
        List<io.github.vedmider.dylext.domain.port.in.RepoCatalogUseCase.LanguageInfo> out = new ArrayList<>();
        for (String language : languages) {
            List<String> exts = extToLanguage.entrySet().stream().filter(e -> e.getValue().equals(language))
                    .map(Map.Entry::getKey).sorted().toList();
            SkeletonExtractor ex = byLanguage.get(language);
            boolean available = ex != null && ex.healthy();
            String parser = ex == null ? "none" : ex instanceof HttpSkeletonExtractor
                    ? remoteUrls.getOrDefault(language, "remote") + (available ? "" : " (unreachable)")
                    : "in-process";
            String tier = ex == null ? null : ex.tier().id();
            out.add(new io.github.vedmider.dylext.domain.port.in.RepoCatalogUseCase.LanguageInfo(
                    language, exts, available, parser, tier));
        }
        return out;
    }

    /** The capability tier of a language's parser, or null when no parser handles it. */
    public io.github.vedmider.dylext.domain.model.CapabilityTier tierOf(String language) {
        SkeletonExtractor ex = language == null ? null : byLanguage.get(language);
        return ex == null ? null : ex.tier();
    }

    /** True if some configured + available extractor can parse this path. */
    public boolean supports(String path) {
        return extractorFor(path).isPresent();
    }

    /** How a file should be ingested: by a healthy parser, skipped as unreachable, or unsupported. */
    public enum Route { PARSE, UNREACHABLE, UNSUPPORTED }

    public record Routing(Route route, SkeletonExtractor extractor, String key) {
    }

    /**
     * Resolve the ingest route for a file. A configured-but-unreachable sidecar is UNREACHABLE (reported
     * distinctly), not UNSUPPORTED (no parser) and not a silent degraded parse.
     */
    public Routing route(String path) {
        String ext = extension(path);
        String language = ext == null ? null : extToLanguage.get(ext);
        SkeletonExtractor ex = language == null ? null : byLanguage.get(language);
        if (ex != null) {
            return ex.healthy()
                    ? new Routing(Route.PARSE, ex, language)
                    : new Routing(Route.UNREACHABLE, null, language);
        }
        // No mapped+running parser: a configured generic fallback parses any recognizable source file at T1.
        SkeletonExtractor fallback = byLanguage.get(GENERIC);
        if (fallback != null && unsupportedKey(path) != null && fallback.healthy()) {
            return new Routing(Route.PARSE, fallback, GENERIC);
        }
        return new Routing(Route.UNSUPPORTED, null, unsupportedKey(path));
    }

    private static final java.util.Set<String> KNOWN_SOURCE_EXTENSIONS = java.util.Set.of(
            "ts", "tsx", "js", "jsx", "mjs", "cjs", "rs", "c", "h", "cc", "cpp", "cxx", "hpp", "cs", "rb",
            "php", "kt", "kts", "scala", "swift", "dart", "lua", "groovy", "sql", "ex", "exs", "erl", "hs",
            "clj", "pl", "r", "vue", "svelte");

    /**
     * For a source file Dylext cannot parse, the key to report it under: the configured language id
     * (e.g. "python" when no parser is available) or the bare extension of a well-known source language.
     * Null for non-source files (docs, configs, images) which are not worth mentioning.
     */
    public String unsupportedKey(String path) {
        String ext = extension(path);
        if (ext == null) {
            return null;
        }
        String language = extToLanguage.get(ext);
        if (language != null) {
            return language;
        }
        return KNOWN_SOURCE_EXTENSIONS.contains(ext) ? ext : null;
    }

    /** The extractor for this path, if its extension maps to an available language. */
    public Optional<SkeletonExtractor> extractorFor(String path) {
        String ext = extension(path);
        if (ext == null) {
            return Optional.empty();
        }
        String language = extToLanguage.get(ext);
        if (language == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byLanguage.get(language));
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (dot <= slash || dot == path.length() - 1) {
            return null;
        }
        return path.substring(dot + 1).toLowerCase();
    }
}
