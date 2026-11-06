package com.example.contextmcp.domain.port.out;

import com.example.contextmcp.domain.model.LedgerEntry;

/** Persists token-savings ledger entries and answers running totals. */
public interface LedgerStore {

    void record(LedgerEntry entry);

    Totals totals();

    record Totals(long calls, long tokensServed, long baselineTokens) {
        public long saved() {
            return baselineTokens - tokensServed;
        }

        public double savedPercent() {
            return baselineTokens == 0 ? 0.0 : 100.0 * saved() / baselineTokens;
        }
    }
}
