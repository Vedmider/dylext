package io.github.vedmider.dylext.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SymbolTokenExtractorTest {

    private final SymbolTokenExtractor extractor = new SymbolTokenExtractor();

    @Test
    void extractsQualifiedTypeAndMember() {
        assertThat(extractor.extract("call RateLimitInterceptor#allow to gate traffic"))
                .contains("RateLimitInterceptor", "allow");
    }

    @Test
    void extractsDotQualifiedReference() {
        assertThat(extractor.extract("configured in RetryConfig.maxAttempts"))
                .contains("RetryConfig", "maxAttempts");
    }

    @Test
    void extractsMethodCallWithParens() {
        assertThat(extractor.extract("then it invokes retrieve() on the store"))
                .contains("retrieve");
    }

    @Test
    void extractsMultiHumpCamelCaseType() {
        assertThat(extractor.extract("the HybridPgDocumentStore merges results"))
                .contains("HybridPgDocumentStore");
    }

    @Test
    void ignoresOrdinaryProse() {
        assertThat(extractor.extract("The system retrieves data and returns a grounded answer."))
                .isEmpty();
    }

    @Test
    void doesNotFlagSentenceLeadingSingleCapitalWords() {
        assertThat(extractor.extract("Kafka handles partitioning. Spring wires the beans."))
                .isEmpty();
    }

    @Test
    void emptyOrNullIsEmpty() {
        assertThat(extractor.extract("")).isEmpty();
        assertThat(extractor.extract(null)).isEmpty();
    }

    @Test
    void deduplicatesRepeatedTokens() {
        assertThat(extractor.extract("retrieve() then retrieve() again"))
                .containsExactly("retrieve");
    }

    @Test
    void ignoresUrls() {
        assertThat(extractor.extract("see https://example.com/docs/Guide for details"))
                .doesNotContain("https", "example", "Guide");
    }

    @Test
    void ignoresAbbreviations() {
        assertThat(extractor.extract("use the cache, e.g. for hot reads, i.e. repeated queries"))
                .isEmpty();
    }

    @Test
    void extractorItselfDoesNotCap() {
        // The 50-token cap lives in the service, not the extractor — the extractor returns all
        // candidates it finds. (Service-level capping is pinned in CitationVerifierServiceTest.)
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            sb.append("Type").append(i).append("#method").append(i).append(" ");
        }
        assertThat(extractor.extract(sb.toString())).hasSizeGreaterThan(100);
    }

    @Test
    void sentenceLeadingCapitalizedWordIsNotFlagged() {
        assertThat(extractor.extract("System design matters. Kafka is a log."))
                .doesNotContain("System", "Kafka", "design");
    }
}
