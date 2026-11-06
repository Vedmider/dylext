package com.example.contextmcp.domain.model;

import java.util.Optional;

/** Serving view: the symbol's skeleton, an optional summary, and the handle to expand to raw source. */
public record Digest(Symbol symbol, Skeleton skeleton, Optional<Summary> summary) {

    /** The id a client passes to {@code expand} to fetch the raw source span. */
    public String expandHandle() {
        return symbol.id();
    }
}
