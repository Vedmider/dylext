package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.model.LedgerEntry;
import io.github.vedmider.dylext.domain.port.in.LedgerUseCase;
import io.github.vedmider.dylext.domain.port.out.LedgerStore;
import io.github.vedmider.dylext.domain.port.out.LedgerStore.Totals;
import io.github.vedmider.dylext.domain.port.out.Tokenizer;
import org.springframework.stereotype.Service;

/** Tokenizes served vs baseline text and records the delta to the ledger. */
@Service
public class LedgerService implements LedgerUseCase {

    private final LedgerStore store;
    private final Tokenizer tokenizer;

    public LedgerService(LedgerStore store, Tokenizer tokenizer) {
        this.store = store;
        this.tokenizer = tokenizer;
    }

    @Override
    public void recordServed(String taskId, String tool, String servedText, String baselineText) {
        store.record(new LedgerEntry(taskId == null ? "" : taskId, tool,
                tokenizer.count(servedText), tokenizer.count(baselineText)));
    }

    @Override
    public void recordCost(String taskId, String tool, int costTokens) {
        store.record(new LedgerEntry(taskId == null ? "" : taskId, tool, costTokens, 0));
    }

    @Override
    public Totals totals() {
        return store.totals();
    }
}
