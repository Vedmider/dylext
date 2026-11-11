package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.config.LanguageProperties;
import io.github.vedmider.dylext.domain.model.Symbol;
import io.github.vedmider.dylext.domain.port.out.SkeletonExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageRouterTest {

    private static SkeletonExtractor extractor(String language) {
        return new SkeletonExtractor() {
            public List<Symbol> extract(String repo, String filePath, String source) { return List.of(); }
            public String language() { return language; }
        };
    }

    private static LanguageProperties props(Map<String, String> extToLang) {
        LanguageProperties p = new LanguageProperties();
        p.setLanguages(extToLang);
        return p;
    }

    @Test
    void routesByExtensionToTheMatchingLanguageExtractor() {
        var java = extractor("java");
        var go = extractor("go");
        var router = new LanguageRouter(List.of(java, go),
                props(Map.of("java", "java", "go", "go")));

        assertThat(router.extractorFor("src/Foo.java")).containsSame(java);
        assertThat(router.extractorFor("pkg/main.go")).containsSame(go);
        assertThat(router.supports("src/Foo.java")).isTrue();
        assertThat(router.supports("pkg/main.go")).isTrue();
    }

    @Test
    void unconfiguredExtensionIsUnsupported() {
        var router = new LanguageRouter(List.of(extractor("java")), props(Map.of("java", "java")));
        assertThat(router.supports("README.md")).isFalse();
        assertThat(router.supports("noext")).isFalse();
        assertThat(router.extractorFor("data.csv")).isEmpty();
    }

    @Test
    void configuredExtensionWithNoAvailableExtractorIsUnsupported() {
        // python mapped in config, but no python extractor bean present -> skipped, not an error
        var router = new LanguageRouter(List.of(extractor("java")),
                props(Map.of("java", "java", "py", "python")));
        assertThat(router.supports("app.py")).isFalse();
        assertThat(router.supports("Main.java")).isTrue();
    }

    @Test
    void addingALanguageIsPurelyDataDriven() {
        // same router code; a new extractor + config entry is all it takes to support Go
        var router = new LanguageRouter(List.of(extractor("java"), extractor("go"), extractor("python")),
                props(Map.of("java", "java", "go", "go", "py", "python")));
        assertThat(router.supports("a.java")).isTrue();
        assertThat(router.supports("b.go")).isTrue();
        assertThat(router.supports("c.py")).isTrue();
    }

    @Test
    void extensionMatchIsCaseInsensitiveAndIgnoresDotsInPath() {
        var router = new LanguageRouter(List.of(extractor("java")), props(Map.of("java", "java")));
        assertThat(router.supports("my.pkg.dir/Foo.JAVA")).isTrue();
        assertThat(router.supports("dir.with.dots/noextfile")).isFalse();
    }
}
