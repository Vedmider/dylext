package com.example.contextmcp.domain.model;

import java.util.List;
import java.util.Objects;

/**
 * A code symbol. Identity is {@code repo:filePath:symbolPath}; two symbols with the same
 * {@code contentHash} are interchangeable for caching (skeleton/summary reuse).
 */
public record Symbol(
        String id,
        String repo,
        String filePath,
        String language,
        SymbolKind kind,
        String name,
        String symbolPath,
        String signature,
        String docFirstLine,
        int startLine,
        int endLine,
        List<String> callTargets,
        String contentHash) {

    public Symbol {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(repo, "repo");
        Objects.requireNonNull(filePath, "filePath");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(contentHash, "contentHash");
        if (!id.equals(repo + ":" + filePath + ":" + symbolPath)) {
            throw new IllegalArgumentException(
                    "id must equal repo:filePath:symbolPath but was " + id);
        }
        callTargets = callTargets == null ? List.of() : List.copyOf(callTargets);
    }

    public static String idOf(String repo, String filePath, String symbolPath) {
        return repo + ":" + filePath + ":" + symbolPath;
    }
}
