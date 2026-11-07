package io.github.vedmider.dylext.domain.port.out;

import io.github.vedmider.dylext.domain.model.Symbol;

import java.util.Optional;

/** Builds an abstractive summary for a symbol. The NoOp default returns empty (extractive-only). */
public interface Summarizer {

    /** @return summary text + build-token cost, or empty when summarization is disabled/unavailable. */
    Optional<Result> summarize(Symbol symbol, String sourceSpan);

    /** True when this summarizer actually produces summaries (NoOp returns false). */
    boolean enabled();

    record Result(String text, int buildTokens) {
    }
}
