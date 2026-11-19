package io.github.vedmider.dylext.domain.model;

/** Version of the derived index (symbols, ids, edges); bump on any extractor or resolution change. */
public final class IndexGeneration {

    /** 6: F-024 resolution, F-026 re-resolution, Java method references as call edges. */
    public static final int CURRENT = 6;

    private IndexGeneration() {
    }
}
