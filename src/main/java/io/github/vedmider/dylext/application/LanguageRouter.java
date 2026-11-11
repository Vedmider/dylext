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

    public LanguageRouter(List<SkeletonExtractor> extractors, LanguageProperties properties) {
        this.extToLanguage = properties.getLanguages().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().toLowerCase(), Map.Entry::getValue,
                        (a, b) -> a));

        List<SkeletonExtractor> all = new ArrayList<>(extractors);
        properties.getParsers().forEach((language, url) -> {
            if (url != null && !url.isBlank()) {
                all.add(new HttpSkeletonExtractor(language, url));
            }
        });
        this.byLanguage = all.stream()
                .collect(Collectors.toMap(SkeletonExtractor::language, Function.identity(),
                        (inProcess, remote) -> inProcess));   // in-process wins on conflict
    }

    /** True if some configured + available extractor can parse this path. */
    public boolean supports(String path) {
        return extractorFor(path).isPresent();
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
