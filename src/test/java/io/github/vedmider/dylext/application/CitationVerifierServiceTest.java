package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.model.Digest;
import io.github.vedmider.dylext.domain.model.VerifiedAnswer;
import io.github.vedmider.dylext.domain.model.VerifiedAnswer.Status;
import io.github.vedmider.dylext.domain.port.in.RetrieveUseCase;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CitationVerifierServiceTest {

    /** Fake index: only the symbol names in the map exist, each as a single search hit. */
    private static RetrieveUseCase indexWith(Map<String, RetrieveUseCase.SymbolDigest> known) {
        return new RetrieveUseCase() {
            public List<SymbolDigest> outline(String repo, String filePath) { return List.of(); }
            public List<SymbolDigest> searchSymbols(String repo, String query, int topK) {
                SymbolDigest d = known.get(query);
                return d == null ? List.of() : List.of(d);
            }
            public Digest getDigest(String symbolId) { return null; }
            public ExpandedSpan expand(String symbolId) { return null; }
        };
    }

    private static RetrieveUseCase.SymbolDigest digest(String id, String name, String sig, int line) {
        return new RetrieveUseCase.SymbolDigest(id, "METHOD", name, sig, "", line, line + 3);
    }

    @Test
    void realSymbolIsVerifiedWithSignatureAndLocation() {
        var idx = indexWith(Map.of(
                "allow", digest("r:Rate.java:Rate#allow", "allow", "boolean allow(String client)", 42),
                "Rate", digest("r:Rate.java:Rate", "Rate", "class Rate", 1)));
        var svc = new CitationVerifierService(idx);

        VerifiedAnswer result = svc.verify("rate limiting goes through Rate#allow", "r");

        assertThat(result.unverifiedCount()).isZero();
        assertThat(result.citations())
                .anySatisfy(c -> {
                    assertThat(c.token()).isEqualTo("allow");
                    assertThat(c.status()).isEqualTo(Status.VERIFIED);
                    assertThat(c.signature()).isEqualTo("boolean allow(String client)");
                    assertThat(c.filePath()).isEqualTo("Rate.java");
                    assertThat(c.startLine()).isEqualTo(42);
                });
    }

    @Test
    void unknownSymbolIsUnverified() {
        var svc = new CitationVerifierService(indexWith(Map.of()));

        VerifiedAnswer result = svc.verify("call NonExistentThing#ghostMethod now", "r");

        assertThat(result.unverifiedCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.citations()).allSatisfy(c ->
                assertThat(c.status()).isEqualTo(Status.UNVERIFIED));
    }

    @Test
    void mixedAnswerCountsOnlyUnverified() {
        var idx = indexWith(Map.of("retrieve",
                digest("r:Store.java:Store#retrieve", "retrieve", "List retrieve()", 10)));
        var svc = new CitationVerifierService(idx);

        VerifiedAnswer result = svc.verify("it calls retrieve() then GhostController#handle()", "r");

        assertThat(result.citations()).extracting(VerifiedAnswer.Citation::token).contains("retrieve");
        assertThat(result.unverifiedCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void proseOnlyAnswerHasNoCitations() {
        var svc = new CitationVerifierService(indexWith(Map.of()));
        VerifiedAnswer result = svc.verify("The service returns a grounded answer from documents.", "r");
        assertThat(result.citations()).isEmpty();
        assertThat(result.unverifiedCount()).isZero();
    }
}
