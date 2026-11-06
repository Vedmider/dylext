package com.example.contextmcp.domain.model;

/** One served tool call: tokens actually served vs the whole-file baseline a naive agent would read. */
public record LedgerEntry(String taskId, String tool, int tokensServed, int baselineTokens) {

    public int saved() {
        return baselineTokens - tokensServed;
    }
}
