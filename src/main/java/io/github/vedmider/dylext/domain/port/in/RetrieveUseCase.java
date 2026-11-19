package io.github.vedmider.dylext.domain.port.in;

import io.github.vedmider.dylext.domain.model.Digest;

import java.util.List;

/**
 * Inbound port for digest-first retrieval with progressive disclosure:
 * outline/search surface cheap skeletons; getDigest returns one symbol's digest;
 * expand returns the raw source span only on demand.
 */
public interface RetrieveUseCase {

    /** File outline: every symbol's one-line skeleton, ordered by position. */
    List<SymbolDigest> outline(String repo, String filePath);

    /** Lexical symbol search over name/signature/doc; cheapest entry when the file is unknown. */
    List<SymbolDigest> searchSymbols(String repo, String query, int topK);

    /** Search restricted to files whose repo-relative path starts with {@code pathPrefix} (e.g. a module folder). */
    default List<SymbolDigest> searchSymbols(String repo, String query, int topK, String pathPrefix) {
        if (pathPrefix == null || pathPrefix.isBlank()) {
            return searchSymbols(repo, query, topK);
        }
        throw new UnsupportedOperationException("path-scoped search not supported");
    }

    /** Full digest (skeleton + optional summary) for one symbol. */
    Digest getDigest(String symbolId);

    /** Raw source span for exactly one symbol, read from disk by its stored line range. */
    ExpandedSpan expand(String symbolId);

    /** One-line serving view surfaced by outline/search. */
    record SymbolDigest(String id, String kind, String name, String signature, String docFirstLine,
                        int startLine, int endLine) {
    }

    record ExpandedSpan(String symbolId, String filePath, int startLine, int endLine, String source) {
    }
}
