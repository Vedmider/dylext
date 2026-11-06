package com.example.contextmcp.domain.port.in;

/** Inbound port: incrementally refresh a repo's index from its git cursor to HEAD. */
public interface SyncUseCase {

    SyncResult sync(String repo, String root);

    record SyncResult(String repo, String fromCursor, String toCursor, int filesChanged,
                      boolean fullIndex) {
    }
}
