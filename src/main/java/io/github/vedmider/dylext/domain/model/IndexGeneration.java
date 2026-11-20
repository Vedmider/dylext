package io.github.vedmider.dylext.domain.model;

/** Version of the derived index (symbols, ids, edges); bump on any extractor or resolution change. */
public final class IndexGeneration {

    /** 7: F-024 resolution, F-026 re-resolution, Java method references, PT-06 varargs caller matching. */
    public static final int CURRENT = 7;

    private IndexGeneration() {
    }
}
