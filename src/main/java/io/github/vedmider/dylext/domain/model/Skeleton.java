package io.github.vedmider.dylext.domain.model;

import java.util.Objects;

/** Extractive digest of a symbol: names + signatures + imports + call targets. Always present. */
public record Skeleton(String symbolId, String contentHash, String text, int tokenCount) {

    public Skeleton {
        Objects.requireNonNull(symbolId, "symbolId");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(text, "text");
        if (tokenCount < 0) {
            throw new IllegalArgumentException("tokenCount must be >= 0");
        }
    }
}
