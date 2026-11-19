package io.github.vedmider.dylext.domain.model;

/** Version of the derived index (symbols, ids, edges); bump on any extractor or resolution change. */
public final class IndexGeneration {

    /** 4: Java ctor edges; no field targets; same language; type/this receivers; exact Java arity (F-024). */
    public static final int CURRENT = 4;

    private IndexGeneration() {
    }
}
