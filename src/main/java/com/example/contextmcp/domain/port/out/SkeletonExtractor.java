package com.example.contextmcp.domain.port.out;

import com.example.contextmcp.domain.model.Symbol;

import java.util.List;

/** Parses one source file into its symbols. The file-level MODULE symbol carries the file skeleton. */
public interface SkeletonExtractor {

    /** @return symbols for the file; a malformed file degrades to a single MODULE symbol. */
    List<Symbol> extract(String repo, String filePath, String source);

    /** Language id this extractor handles (e.g. {@code "java"}). */
    String language();
}
