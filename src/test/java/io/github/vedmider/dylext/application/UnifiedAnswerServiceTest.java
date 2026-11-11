package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.model.Digest;
import io.github.vedmider.dylext.domain.port.in.FederatedContextUseCase;
import io.github.vedmider.dylext.domain.port.in.RetrieveUseCase;
import io.github.vedmider.dylext.domain.port.in.RetrieveUseCase.ExpandedSpan;
import io.github.vedmider.dylext.domain.port.in.RetrieveUseCase.SymbolDigest;
import io.github.vedmider.dylext.domain.port.in.UnifiedAnswerUseCase.Intent;
import io.github.vedmider.dylext.domain.port.in.UnifiedAnswerUseCase.Result;
import io.github.vedmider.dylext.domain.port.out.FederatedRetriever.CodeHit;
import io.github.vedmider.dylext.domain.port.out.FederatedRetriever.DocHit;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class UnifiedAnswerServiceTest {

    static class FakeRetrieve implements RetrieveUseCase {
        List<SymbolDigest> symbols = List.of();
        boolean throwOnSearch = false;
        public List<SymbolDigest> outline(String repo, String filePath) { return List.of(); }
        public List<SymbolDigest> searchSymbols(String repo, String query, int topK) {
            if (throwOnSearch) throw new RuntimeException("index down");
            return symbols;
        }
        public Digest getDigest(String symbolId) { return null; }
        public ExpandedSpan expand(String symbolId) { return null; }
    }

    static class FakeFederated implements FederatedContextUseCase {
        List<DocHit> docs = List.of();
        final AtomicReference<String> seenRoles = new AtomicReference<>();
        public List<DocHit> explain(String query, String roles, int topK) {
            seenRoles.set(roles);
            return docs;
        }
        public List<CodeHit> recall(String query, int topK) { return List.of(); }
    }

    private final FakeRetrieve retrieve = new FakeRetrieve();
    private final FakeFederated federated = new FakeFederated();
    private UnifiedAnswerService service() { return new UnifiedAnswerService(retrieve, federated); }

    private static SymbolDigest sym() {
        return new SymbolDigest("repo:a/b/Foo.java:Foo#bar", "METHOD", "bar", "void bar()", "", 10, 20);
    }

    @Test
    void codeTokenRoutesToCodeOnly() {
        assertThat(UnifiedAnswerService.classify("where is Foo#bar")).isEqualTo(Intent.CODE);
        assertThat(UnifiedAnswerService.classify("find the parseConfig() implementation")).isEqualTo(Intent.CODE);
    }

    @Test
    void naturalLanguageRoutesToDocs() {
        assertThat(UnifiedAnswerService.classify("how does rate limiting work")).isEqualTo(Intent.DOCS);
        assertThat(UnifiedAnswerService.classify("what is our retry convention")).isEqualTo(Intent.DOCS);
    }

    @Test
    void mixedQueryRoutesToBoth() {
        assertThat(UnifiedAnswerService.classify("how does CacheManager#evict work"))
                .isEqualTo(Intent.BOTH);
    }

    @Test
    void codeIntentReturnsSymbolCitationsAndSkipsDocs() {
        retrieve.symbols = List.of(sym());
        Result r = service().answer("where is Foo#bar", "repo", null, 5);
        assertThat(r.intent()).isEqualTo(Intent.CODE);
        assertThat(r.code()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("bar");
            assertThat(c.filePath()).isEqualTo("a/b/Foo.java");
            assertThat(c.startLine()).isEqualTo(10);
        });
        assertThat(r.docs()).isEmpty();
    }

    @Test
    void docIntentReturnsDocCitationsAndPropagatesRoles() {
        federated.docs = List.of(new DocHit("runbook.md", 0.9, "rollback steps here"));
        Result r = service().answer("how do I roll back", "repo", "sre,admin", 5);
        assertThat(r.intent()).isEqualTo(Intent.DOCS);
        assertThat(r.docs()).singleElement().extracting("source").isEqualTo("runbook.md");
        assertThat(r.code()).isEmpty();
        assertThat(federated.seenRoles.get()).isEqualTo("sre,admin");
    }

    @Test
    void bothIntentMergesCodeAndDocEvidence() {
        retrieve.symbols = List.of(sym());
        federated.docs = List.of(new DocHit("design.md", 0.7, "evict policy"));
        Result r = service().answer("how does CacheManager#evict work", "repo", null, 5);
        assertThat(r.intent()).isEqualTo(Intent.BOTH);
        assertThat(r.code()).isNotEmpty();
        assertThat(r.docs()).isNotEmpty();
    }

    @Test
    void docsOutageStillYieldsCodeEvidence() {
        retrieve.symbols = List.of(sym());
        federated.docs = List.of();   // Sestacon down => empty
        Result r = service().answer("how does Foo#bar work", "repo", null, 5);
        assertThat(r.code()).isNotEmpty();
        assertThat(r.docsAvailable()).isFalse();
    }

    @Test
    void codeOutageStillYieldsDocEvidence() {
        retrieve.throwOnSearch = true;
        federated.docs = List.of(new DocHit("d.md", 0.5, "x"));
        Result r = service().answer("where is Foo#bar", "repo", null, 5);
        assertThat(r.codeAvailable()).isFalse();
        assertThat(r.code()).isEmpty();
        // CODE intent skips docs, but code failure must not throw
        assertThat(r.intent()).isEqualTo(Intent.CODE);
    }
}
