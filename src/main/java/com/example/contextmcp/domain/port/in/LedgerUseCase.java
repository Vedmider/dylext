package com.example.contextmcp.domain.port.in;

import com.example.contextmcp.domain.port.out.LedgerStore.Totals;

/** Inbound port: record what a tool served vs the whole-file baseline, and read running totals. */
public interface LedgerUseCase {

    /** Record a served response of {@code servedText} against reading whole {@code baselineText}. */
    void recordServed(String taskId, String tool, String servedText, String baselineText);

    /** Record a pure cost (e.g. summary build tokens): served=cost, baseline=0 → negative saving. */
    void recordCost(String taskId, String tool, int costTokens);

    Totals totals();
}
