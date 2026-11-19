package io.github.vedmider.dylext.domain.port.in;

/** Persist an agent-authored purpose/intent note on a symbol (C'): surfaced by get_digest,
 *  stamped with the symbol's current content hash so it auto-invalidates when the code changes. */
public interface AnnotateUseCase {

    /** @param symbolId repo:filePath:symbolPath  @param text the purpose/intent note */
    Annotation annotate(String symbolId, String text);

    record Annotation(String symbolId, String contentHash, boolean stored) {
    }
}
