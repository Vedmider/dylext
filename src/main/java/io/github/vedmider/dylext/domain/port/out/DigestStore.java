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
    default List<Symbol> searchSymbols(String repo, String query, int topK) {
        return searchSymbols(repo, query, topK, null);
    }

    /** Search limited to files whose path starts with {@code pathPrefix} (null/blank = whole repo). */
    List<Symbol> searchSymbols(String repo, String query, int topK, String pathPrefix);

    void saveSummary(Summary summary);

    Optional<Summary> findSummary(String symbolId);

    /** Remove a symbol (cascades to its skeleton and summary). */
    void deleteSymbol(String symbolId);

    /** Remove all symbols of a file (cascades). */
    void deleteFile(String repo, String filePath);

    /** Forget everything indexed for a repo (symbols, skeletons, summaries, edges, sync cursor); keeps no root. */
    void clearRepo(String repo);

    /** Run several writes as one unit (one commit). Default: just run them. */
    default void atomically(Runnable work) {
        work.run();
    }

    /** Record where a repo's working tree lives (for reading raw spans on expand). */
    void saveRepoRoot(String repo, String root);

    Optional<String> repoRoot(String repo);

    /** Persist the git sha last indexed for a repo (incremental-sync cursor). */
    void saveRepoCursor(String repo, String sha);

    /** Generation that built the repo's index (0 = unknown/pre-versioning). */
    default int indexGeneration(String repo) {
        return io.github.vedmider.dylext.domain.model.IndexGeneration.CURRENT;
    }

    default void saveIndexGeneration(String repo, int generation) {
    }

    Optional<String> repoCursor(String repo);

    /** Replace all outgoing edges for a source symbol (called on (re)index of its file). */
    void replaceEdgesFrom(String srcId, List<CodeEdge> edges);

    /** Resolve pending edges (dst_id null) to symbol ids by matching the called method name. */
    void resolvePendingEdges(String repo);

    /** Symbol ids that call the given symbol (resolved edges). */
    List<String> callersOf(String symbolId);

    /** True when any resolved caller edge into the symbol is an un-split same-arity overload best-guess. */
    default boolean hasAmbiguousCallers(String symbolId) {
        return false;
    }

    /** Caller symbol ids whose file path looks like a test (contains "test", case-insensitive). */
    List<String> testsFor(String symbolId);

    /** One row per known repo with indexed file and symbol counts. */
    List<RepoRow> listRepos();

    /** Per indexed file: language and number of real symbols. */
    List<FileStat> fileStats(String repo);

    record RepoRow(String repo, String root, String cursor, int files, int symbols) {
    }

    record FileStat(String path, String language, int symbols) {
    }
}
