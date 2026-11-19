package io.github.vedmider.dylext.adapter.out.store;

import io.github.vedmider.dylext.domain.model.Skeleton;
import io.github.vedmider.dylext.domain.model.Summary;
import io.github.vedmider.dylext.domain.model.Symbol;
import io.github.vedmider.dylext.domain.model.SymbolKind;
import io.github.vedmider.dylext.domain.port.out.DigestStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class PgDigestStoreIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private DigestStore store;

    private static Symbol symbol(String path, String hash) {
        String id = Symbol.idOf("demo", "A.java", path);
        return new Symbol(id, "demo", "A.java", "java", SymbolKind.METHOD, "m", path,
                "void m()", "does a thing", 10, 20, List.of("B.other"), hash);
    }

    @Test
    void upsertThenReadBackSymbolAndSkeleton() {
        Symbol s = symbol("A.m", "h1");
        store.upsert(s, new Skeleton(s.id(), "h1", "void m()", 3));

        assertThat(store.findSymbol(s.id())).get()
                .satisfies(r -> {
                    assertThat(r.name()).isEqualTo("m");
                    assertThat(r.startLine()).isEqualTo(10);
                    assertThat(r.endLine()).isEqualTo(20);
                });
        assertThat(store.findSkeleton(s.id())).get()
                .satisfies(r -> assertThat(r.text()).isEqualTo("void m()"));
    }

    @Test
    void upsertIsIdempotentAndUpdatesInPlace() {
        Symbol s = symbol("A.upd", "h1");
        store.upsert(s, new Skeleton(s.id(), "h1", "old", 1));
        store.upsert(s, new Skeleton(s.id(), "h2", "new", 2));

        assertThat(store.findSkeleton(s.id())).get()
                .satisfies(r -> {
                    assertThat(r.text()).isEqualTo("new");
                    assertThat(r.contentHash()).isEqualTo("h2");
                });
    }

    @Test
    void symbolsInFileAreOrderedByStartLine() {
        store.upsert(symbol("A.late", "h"), new Skeleton(Symbol.idOf("demo", "A.java", "A.late"), "h", "x", 1));
        Symbol early = new Symbol(Symbol.idOf("demo", "A.java", "A.early"), "demo", "A.java", "java",
                SymbolKind.METHOD, "e", "A.early", "void e()", "", 1, 2, List.of(), "h");
        store.upsert(early, new Skeleton(early.id(), "h", "void e()", 2));

        List<Symbol> syms = store.symbolsInFile("demo", "A.java");
        assertThat(syms).extracting(Symbol::startLine).isSorted();
    }

    @Test
    void summaryRoundTrips() {
        Symbol s = symbol("A.sum", "h1");
        store.upsert(s, new Skeleton(s.id(), "h1", "void m()", 3));
        store.saveSummary(new Summary(s.id(), "h1", "Summarises m", 42));

        Optional<Summary> got = store.findSummary(s.id());
        assertThat(got).get().satisfies(sm -> {
            assertThat(sm.text()).isEqualTo("Summarises m");
            assertThat(sm.buildTokens()).isEqualTo(42);
        });
    }

    @Test
    void deleteCascadesToSkeletonAndSummary() {
        Symbol s = symbol("A.del", "h1");
        store.upsert(s, new Skeleton(s.id(), "h1", "void m()", 3));
        store.saveSummary(new Summary(s.id(), "h1", "s", 1));

        store.deleteSymbol(s.id());

        assertThat(store.findSymbol(s.id())).isEmpty();
        assertThat(store.findSkeleton(s.id())).isEmpty();
        assertThat(store.findSummary(s.id())).isEmpty();
    }
}
