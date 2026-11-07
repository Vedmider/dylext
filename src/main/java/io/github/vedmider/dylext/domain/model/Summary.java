package io.github.vedmider.dylext.domain.model;

import java.util.Objects;

/** Optional abstractive summary, cached by {@code contentHash}; invalidated when the hash changes. */
public record Summary(String symbolId, String contentHash, String text, int buildTokens) {

    public Summary {
        Objects.requireNonNull(symbolId, "symbolId");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(text, "text");
    }
}
