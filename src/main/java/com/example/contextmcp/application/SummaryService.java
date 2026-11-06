package com.example.contextmcp.application;

import com.example.contextmcp.domain.model.Summary;
import com.example.contextmcp.domain.model.Symbol;
import com.example.contextmcp.domain.port.in.LedgerUseCase;
import com.example.contextmcp.domain.port.out.DigestStore;
import com.example.contextmcp.domain.port.out.Summarizer;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Lazy, content-hash-cached summaries. Returns the cached summary when fresh; otherwise (when the
 * summarizer is enabled) builds one, records its build cost to the ledger, and caches it.
 */
@Service
public class SummaryService {

    private final DigestStore store;
    private final Summarizer summarizer;
    private final LedgerUseCase ledger;

    public SummaryService(DigestStore store, Summarizer summarizer, LedgerUseCase ledger) {
        this.store = store;
        this.summarizer = summarizer;
        this.ledger = ledger;
    }

    /** Fresh summary for a symbol, building + caching on demand. Empty when summarization is off. */
    public Optional<Summary> summaryFor(Symbol symbol, String sourceSpan) {
        Optional<Summary> cached = store.findSummary(symbol.id());
        if (cached.isPresent() && cached.get().contentHash().equals(symbol.contentHash())) {
            return cached;
        }
        if (!summarizer.enabled()) {
            return Optional.empty();
        }
        return summarizer.summarize(symbol, sourceSpan).map(result -> {
            Summary summary = new Summary(symbol.id(), symbol.contentHash(),
                    result.text(), result.buildTokens());
            store.saveSummary(summary);
            ledger.recordCost(null, "summarize:build", result.buildTokens());
            return summary;
        });
    }
}
