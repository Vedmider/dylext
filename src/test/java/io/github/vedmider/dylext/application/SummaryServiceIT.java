package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.model.Symbol;
import io.github.vedmider.dylext.domain.model.SymbolKind;
import io.github.vedmider.dylext.domain.port.out.DigestStore;
import io.github.vedmider.dylext.domain.port.out.Summarizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class SummaryServiceIT {

    static final AtomicInteger BUILD_COUNT = new AtomicInteger();

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @TestConfiguration
    static class FakeSummarizerConfig {
        @Bean
        @org.springframework.context.annotation.Primary
        Summarizer fakeSummarizer() {
            return new Summarizer() {
                @Override
                public Optional<Result> summarize(Symbol symbol, String sourceSpan) {
                    BUILD_COUNT.incrementAndGet();
                    return Optional.of(new Result("summary of " + symbol.name(), 7));
                }

                @Override
                public boolean enabled() {
                    return true;
                }
            };
        }
    }

    @Autowired
    private SummaryService summaries;

    @Autowired
    private DigestStore store;

    private Symbol symbol(String path, String hash) {
        String id = Symbol.idOf("sum", "A.java", path);
        return new Symbol(id, "sum", "A.java", "java", SymbolKind.METHOD, "m", path,
                "void m()", "", 1, 3, List.of(), hash);
    }

    private void persist(Symbol s) {
        store.upsert(s, new io.github.vedmider.dylext.domain.model.Skeleton(s.id(), s.contentHash(),
                "void m()", 3));
    }

    @Test
    void buildsOnceThenServesFromCache() {
        BUILD_COUNT.set(0);
        Symbol s = symbol("A.cache", "h1");
        persist(s);

        Optional<?> first = summaries.summaryFor(s, "void m() {}");
        Optional<?> second = summaries.summaryFor(s, "void m() {}");

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(BUILD_COUNT.get()).isEqualTo(1);
    }

    @Test
    void rebuildsWhenContentHashChanges() {
        BUILD_COUNT.set(0);
        Symbol v1 = symbol("A.rebuild", "hash-v1");
        persist(v1);
        summaries.summaryFor(v1, "void m() {}");

        Symbol v2 = symbol("A.rebuild", "hash-v2");
        persist(v2);
        summaries.summaryFor(v2, "void m() { changed(); }");

        assertThat(BUILD_COUNT.get()).isEqualTo(2);
        assertThat(store.findSummary(v2.id())).get()
                .satisfies(sm -> assertThat(sm.contentHash()).isEqualTo("hash-v2"));
    }
}
