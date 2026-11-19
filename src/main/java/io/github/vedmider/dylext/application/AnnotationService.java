package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.model.Summary;
import io.github.vedmider.dylext.domain.model.Symbol;
import io.github.vedmider.dylext.domain.port.in.AnnotateUseCase;
import io.github.vedmider.dylext.domain.port.out.DigestStore;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;

/**
 * Stores an agent-authored purpose/intent note as the symbol's {@link Summary}, stamped with the
 * symbol's current content hash. get_digest surfaces it; it auto-invalidates when the hash changes,
 * so a stale note is never served (the agent re-authors it on the next analyze-before-ingest pass).
 */
@Service
public class AnnotationService implements AnnotateUseCase {

    private final DigestStore store;
    private final FreshnessGate freshness;

    public AnnotationService(DigestStore store, FreshnessGate freshness) {
        this.store = store;
        this.freshness = freshness;
    }

    @Override
    public Annotation annotate(String symbolId, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("annotation text must not be blank");
        }
        freshness.ensureFresh(repoOf(symbolId));
        Symbol symbol = store.findSymbol(symbolId)
                .orElseThrow(() -> new NoSuchElementException("unknown symbol: " + symbolId));
        store.saveSummary(new Summary(symbolId, symbol.contentHash(), text.strip(), 0));
        return new Annotation(symbolId, symbol.contentHash(), true);
    }

    private static String repoOf(String symbolId) {
        int i = symbolId == null ? -1 : symbolId.indexOf(':');
        return i > 0 ? symbolId.substring(0, i) : null;
    }
}
