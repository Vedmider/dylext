package io.github.vedmider.dylext.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps file extensions to language ids. This is the only place a new language is "known" to the
 * core — the matching {@link io.github.vedmider.dylext.domain.port.out.SkeletonExtractor} is
 * selected purely by its {@code language()}, so adding a language is config + a new adapter, no
 * change to indexing code.
 */
@ConfigurationProperties(prefix = "dylext")
public class LanguageProperties {

    /** extension (without dot, lowercase) -> language id. */
    private Map<String, String> languages = defaults();

    /** language id -> out-of-process parser base URL (e.g. python -> http://localhost:9091). */
    private Map<String, String> parsers = new LinkedHashMap<>();

    /** Optional generic parser base URL (e.g. a tree-sitter sidecar); parses otherwise-unsupported source files at T1. */
    private String fallbackParser = "";

    private static Map<String, String> defaults() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("java", "java");
        m.put("py", "python");
        m.put("go", "go");
        m.put("ts", "typescript");
        m.put("tsx", "typescript");
        m.put("js", "typescript");
        m.put("jsx", "typescript");
        m.put("mjs", "typescript");
        m.put("cjs", "typescript");
        return m;
    }

    public Map<String, String> getLanguages() {
        return languages;
    }

    public void setLanguages(Map<String, String> languages) {
        this.languages = (languages == null || languages.isEmpty()) ? defaults() : languages;
    }

    public Map<String, String> getParsers() {
        return parsers;
    }

    public void setParsers(Map<String, String> parsers) {
        this.parsers = parsers == null ? new LinkedHashMap<>() : parsers;
    }

    public String getFallbackParser() {
        return fallbackParser;
    }

    public void setFallbackParser(String fallbackParser) {
        this.fallbackParser = fallbackParser == null ? "" : fallbackParser;
    }
}
