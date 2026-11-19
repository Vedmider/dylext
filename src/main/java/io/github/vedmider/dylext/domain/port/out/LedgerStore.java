package io.github.vedmider.dylext.domain.port.out;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.vedmider.dylext.domain.model.LedgerEntry;

import java.util.List;

/** Persists token-savings ledger entries and answers running totals. */
public interface LedgerStore {

    void record(LedgerEntry entry);

    Totals totals();

    record Totals(long calls, long tokensServed, long baselineTokens,
                  @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ToolTotal> byTool) {

        /** Totals without a per-tool breakdown (convenience for callers/tests). */
        public Totals(long calls, long tokensServed, long baselineTokens) {
            this(calls, tokensServed, baselineTokens, List.of());
        }

        public long saved() {
            return baselineTokens - tokensServed;
        }

        public double savedPercent() {
            return baselineTokens == 0 ? 0.0 : 100.0 * saved() / baselineTokens;
        }
    }

    /** Per-tool slice of the ledger, so savings can be attributed to search/outline/digest/expand/etc. */
    record ToolTotal(String tool, long calls, long tokensServed, long baselineTokens) {
    }
}
