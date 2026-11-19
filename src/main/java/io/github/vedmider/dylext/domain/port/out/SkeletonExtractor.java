package io.github.vedmider.dylext.domain.port.out;

import io.github.vedmider.dylext.domain.model.Symbol;

import java.util.List;

/** Parses one source file into its symbols. The file-level MODULE symbol carries the file skeleton. */
public interface SkeletonExtractor {

    /** @return symbols for the file; a malformed file degrades to a single MODULE symbol. */
    List<Symbol> extract(String repo, String filePath, String source);

    /** Language id this extractor handles (e.g. {@code "java"}). */
    String language();

    /** Capability tier: how much of the tool surface this parser can answer. Default is structure-only. */
    default io.github.vedmider.dylext.domain.model.CapabilityTier tier() {
        return io.github.vedmider.dylext.domain.model.CapabilityTier.T1_STRUCTURE;
    }

    /** Reachable and ready to parse. In-process extractors are always ready; remote ones probe their sidecar. */
    default boolean healthy() {
        return true;
    }
}
