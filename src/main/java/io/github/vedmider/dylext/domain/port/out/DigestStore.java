package io.github.vedmider.dylext.domain.port.out;

import io.github.vedmider.dylext.domain.model.CodeEdge;
import io.github.vedmider.dylext.domain.model.Skeleton;
import io.github.vedmider.dylext.domain.model.Summary;
import io.github.vedmider.dylext.domain.model.Symbol;

import java.util.List;
import java.util.Optional;

/** Persists static context (symbols + skeletons + optional summaries) and answers digest lookups. */
public interface DigestStore {

    /** Upsert a symbol and its skeleton together (one indexed unit). */
    void upsert(Symbol symbol, Skeleton skeleton);

    Optional<Symbol> findSymbol(String symbolId);

    Optional<Skeleton> findSkeleton(String symbolId);

    /** All symbols of a file, ordered by start line (drives {@code outline}). */
    List<Symbol> symbolsInFile(String repo, String filePath);

    /** The content hash stored for a file's MODULE symbol, if indexed (drives incremental skip). */
    Optional<String> fileContentHash(String repo, String filePath);

    /** Distinct indexed file paths for a repo. */
    List<String> filesInRepo(String repo);

    /** Lexical search over name/signature/doc within a repo, best matches first. */
    List<Symbol> searchSymbols(String repo, String query, int topK);

    void saveSummary(Summary summary);

    Optional<Summary> findSummary(String symbolId);

    /** Remove a symbol (cascades to its skeleton and summary). */
    void deleteSymbol(String symbolId);

    /** Remove all symbols of a file (cascades). */
    void deleteFile(String repo, String filePath);

    /** Record where a repo's working tree lives (for reading raw spans on expand). */
    void saveRepoRoot(String repo, String root);

    Optional<String> repoRoot(String repo);

    /** Persist the git sha last indexed for a repo (incremental-sync cursor). */
    void saveRepoCursor(String repo, String sha);

    Optional<String> repoCursor(String repo);

    /** Replace all outgoing edges for a source symbol (called on (re)index of its file). */
    void replaceEdgesFrom(String srcId, List<CodeEdge> edges);

    /** Resolve pending edges (dst_id null) to symbol ids by matching the called method name. */
    void resolvePendingEdges(String repo);

    /** Symbol ids that call the given symbol (resolved edges). */
    List<String> callersOf(String symbolId);

    /** Caller symbol ids whose file path looks like a test (contains "test", case-insensitive). */
    List<String> testsFor(String symbolId);
}
