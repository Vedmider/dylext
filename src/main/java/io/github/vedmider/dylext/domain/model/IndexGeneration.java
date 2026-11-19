package io.github.vedmider.dylext.domain.model;

/** Version of the derived index (symbols, ids, edges); bump on any extractor or resolution change. */
public final class IndexGeneration {

    /** 5: F-024 call resolution + re-resolve callers when a same-name candidate is added/removed (F-026). */
    public static final int CURRENT = 5;

    private IndexGeneration() {
    }
}
