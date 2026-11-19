package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.application.LanguageRouter.Route;
import io.github.vedmider.dylext.config.LanguageProperties;
import io.github.vedmider.dylext.domain.model.Symbol;
import io.github.vedmider.dylext.domain.port.in.RepoCatalogUseCase.LanguageInfo;
import io.github.vedmider.dylext.domain.port.out.SkeletonExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageRouterTest {

    private static final class FakeExtractor implements SkeletonExtractor {
        private final String language;
        private final boolean healthy;
        private final io.github.vedmider.dylext.domain.model.CapabilityTier tier;
        FakeExtractor(String language, boolean healthy) {
            this(language, healthy, io.github.vedmider.dylext.domain.model.CapabilityTier.T1_STRUCTURE);
        }
        FakeExtractor(String language, boolean healthy,
                      io.github.vedmider.dylext.domain.model.CapabilityTier tier) {
            this.language = language;
            this.healthy = healthy;
            this.tier = tier;
        }
        public List<Symbol> extract(String repo, String filePath, String source) {
            return List.of();
        }
        public String language() {
            return language;
        }
        public boolean healthy() {
            return healthy;
        }
        public io.github.vedmider.dylext.domain.model.CapabilityTier tier() {
            return tier;
        }
    }

    private LanguageRouter router(SkeletonExtractor... extractors) {
        return new LanguageRouter(List.of(extractors), new LanguageProperties());
    }

    @Test
    void healthyParserRoutesToParse() {
        LanguageRouter router = router(new FakeExtractor("python", true));
        LanguageRouter.Routing r = router.route("pkg/mod.py");
        assertThat(r.route()).isEqualTo(Route.PARSE);
        assertThat(r.extractor()).isNotNull();
    }

    @Test
    void unreachableParserIsDistinctFromUnsupported() {
        LanguageRouter router = router(new FakeExtractor("python", false));
        assertThat(router.route("pkg/mod.py").route()).isEqualTo(Route.UNREACHABLE);
        assertThat(router.route("pkg/mod.py").key()).isEqualTo("python");
        assertThat(router.route("pkg/mod.rb").route()).isEqualTo(Route.UNSUPPORTED);
    }

    @Test
    void describeReportsUnreachableParserAsUnavailable() {
        LanguageRouter router = router(new FakeExtractor("python", false));
        LanguageInfo python = router.describe().stream()
                .filter(l -> l.language().equals("python")).findFirst().orElseThrow();
        assertThat(python.available()).isFalse();
    }

    @Test
    void describeReportsCapabilityTierPerLanguage() {
        LanguageRouter router = router(
                new FakeExtractor("python", true, io.github.vedmider.dylext.domain.model.CapabilityTier.T2_EDGES));
        LanguageInfo python = router.describe().stream()
                .filter(l -> l.language().equals("python")).findFirst().orElseThrow();
        assertThat(python.tier()).isEqualTo("T2");
    }

    @Test
    void tierOfReturnsTheParsersTierOrNullWhenUnknown() {
        LanguageRouter router = router(
                new FakeExtractor("go", true, io.github.vedmider.dylext.domain.model.CapabilityTier.T2_EDGES));
        assertThat(router.tierOf("go")).isEqualTo(io.github.vedmider.dylext.domain.model.CapabilityTier.T2_EDGES);
        assertThat(router.tierOf("cobol")).isNull();
    }

    @Test
    void genericFallbackParsesOtherwiseUnsupportedSourceFilesAtT1() {
        // a "generic" extractor stands in for the configured tree-sitter fallback sidecar
        LanguageRouter router = router(new FakeExtractor(LanguageRouter.GENERIC, true));
        LanguageRouter.Routing rb = router.route("pkg/mod.rb");   // ruby: no mapped parser
        assertThat(rb.route()).isEqualTo(Route.PARSE);
        assertThat(rb.key()).isEqualTo(LanguageRouter.GENERIC);
        assertThat(router.route("README.md").route()).isEqualTo(Route.UNSUPPORTED);   // non-source stays unsupported
    }
}
