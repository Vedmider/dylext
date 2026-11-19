package io.github.vedmider.dylext.domain.port.in;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Inbound port: incrementally refresh a repo's index from its git cursor to HEAD. */
public interface SyncUseCase {

    SyncResult sync(String repo, String root);

    record SyncResult(String repo, String fromCursor, String toCursor, int filesChanged,
                      boolean fullIndex,
                      @JsonInclude(JsonInclude.Include.NON_NULL) String branch) {

        public SyncResult(String repo, String fromCursor, String toCursor, int filesChanged, boolean fullIndex) {
            this(repo, fromCursor, toCursor, filesChanged, fullIndex, null);
        }
    }
}
