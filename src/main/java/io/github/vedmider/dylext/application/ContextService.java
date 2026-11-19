package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.model.Digest;
import io.github.vedmider.dylext.domain.model.Skeleton;
import io.github.vedmider.dylext.domain.model.Summary;
import io.github.vedmider.dylext.domain.model.Symbol;
import io.github.vedmider.dylext.domain.port.in.RetrieveUseCase;
import io.github.vedmider.dylext.domain.port.out.DigestStore;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/** Digest-first retrieval: cheap skeletons via outline/search, raw spans only via expand. */
@Service
public class ContextService implements RetrieveUseCase {

    private final DigestStore store;
    private final io.github.vedmider.dylext.domain.port.in.LedgerUseCase ledger;
    private final SummaryService summaries;
    private final FreshnessGate freshness;

    public ContextService(DigestStore store,
                          io.github.vedmider.dylext.domain.port.in.LedgerUseCase ledger,
                          SummaryService summaries,
                          FreshnessGate freshness) {
        this.store = store;
        this.ledger = ledger;
        this.summaries = summaries;
        this.freshness = freshness;
    }

    @Override
    public List<SymbolDigest> outline(String repo, String filePath) {
        requireIndexed(repo);
        freshness.ensureFresh(repo);
        List<Symbol> symbols = store.symbolsInFile(repo, filePath);
        if (symbols.isEmpty()) {
            throw new NoSuchElementException("file not indexed in repo " + repo + ": " + filePath
                    + " - check the path (repo-relative) or (re)index the repo");
        }
        ledger.recordServed(null, "outline", servedText(symbols), wholeFiles(repo, symbols));
        return symbols.stream().map(ContextService::toDigest).toList();
    }

    @Override
    public List<SymbolDigest> searchSymbols(String repo, String query, int topK) {
        return searchSymbols(repo, query, topK, null);
    }

    @Override
    public List<SymbolDigest> searchSymbols(String repo, String query, int topK, String pathPrefix) {
        requireIndexed(repo);
        freshness.ensureFresh(repo);
        List<Symbol> symbols = store.searchSymbols(repo, query, topK, pathPrefix);
        if (symbols.isEmpty()) {
            for (String token : fuzzyTokens(query)) {          // relaxed retry for multi-word / NL queries
                symbols = store.searchSymbols(repo, token, topK, pathPrefix);
                if (!symbols.isEmpty()) {
                    break;
                }
            }
        }
        ledger.recordServed(null, "search_symbols", servedText(symbols), wholeFiles(repo, symbols));
        return symbols.stream().map(ContextService::toDigest).toList();
    }

    /** Identifier-ish tokens from a query (word + camelCase-hump splits), longest-first, for a relaxed retry. */
    static List<String> fuzzyTokens(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        java.util.LinkedHashSet<String> tokens = new java.util.LinkedHashSet<>();
        for (String word : query.split("[^A-Za-z0-9_]+")) {
            if (word.length() >= 3) {
                tokens.add(word);
            }
            for (String hump : word.split("(?<=[a-z0-9])(?=[A-Z])")) {   // camelCase / PascalCase humps
                if (hump.length() >= 3) {
                    tokens.add(hump);
                }
            }
        }
        tokens.remove(query);
        return tokens.stream()
                .sorted(java.util.Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    /** What the caller receives: one skeleton line per hit. */
    private static String servedText(List<Symbol> symbols) {
        StringBuilder sb = new StringBuilder();
        for (Symbol s : symbols) {
            sb.append(s.signature()).append('\n');
        }
        return sb.toString();
    }

    /** Baseline an AI would pay without Dylext: reading each distinct hit file whole. */
    private String wholeFiles(String repo, List<Symbol> symbols) {
        String root = store.repoRoot(repo).orElse(null);
        if (root == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        symbols.stream().map(Symbol::filePath).distinct()
                .forEach(fp -> sb.append(readWhole(Path.of(root).resolve(fp))).append('\n'));
        return sb.toString();
    }

    private void requireIndexed(String repo) {
        if (store.repoRoot(repo).isEmpty()) {
            throw new NoSuchElementException("repo not indexed: " + repo + " - call index {repo, root} first");
        }
    }

    @Override
    public Digest getDigest(String symbolId) {
        freshness.ensureFresh(repoOf(symbolId));
        Symbol symbol = store.findSymbol(symbolId)
                .orElseThrow(() -> new NoSuchElementException("unknown symbol: " + symbolId));
        Skeleton skeleton = store.findSkeleton(symbolId)
                .orElseThrow(() -> new NoSuchElementException("no skeleton for: " + symbolId));
        Optional<Summary> summary = summaries.summaryFor(symbol, spanOf(symbol));
        Digest digest = new Digest(symbol, skeleton, summary);
        ledger.recordServed(null, "get_digest", skeleton.text(), wholeFileOf(symbol));
        return digest;
    }

    @Override
    public ExpandedSpan expand(String symbolId) {
        freshness.ensureFresh(repoOf(symbolId));
        Symbol s = store.findSymbol(symbolId)
                .orElseThrow(() -> new NoSuchElementException("unknown symbol: " + symbolId));
        String root = store.repoRoot(s.repo())
                .orElseThrow(() -> new NoSuchElementException("unknown repo root: " + s.repo()));
        Path file = Path.of(root).resolve(s.filePath());
        String span = readSpan(file, s.startLine(), s.endLine());
        ledger.recordServed(null, "expand", span, readWhole(file));
        return new ExpandedSpan(symbolId, s.filePath(), s.startLine(), s.endLine(), span);
    }

    /** Symbol ids are {@code repo:filePath:symbolPath}; the repo is the first segment. */
    private static String repoOf(String symbolId) {
        int i = symbolId == null ? -1 : symbolId.indexOf(':');
        return i > 0 ? symbolId.substring(0, i) : null;
    }

    private String wholeFileOf(Symbol symbol) {
        return store.repoRoot(symbol.repo())
                .map(root -> readWhole(Path.of(root).resolve(symbol.filePath())))
                .orElse("");
    }

    private String spanOf(Symbol symbol) {
        return store.repoRoot(symbol.repo())
                .map(root -> readSpan(Path.of(root).resolve(symbol.filePath()),
                        symbol.startLine(), symbol.endLine()))
                .orElse("");
    }

    private static String readWhole(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            return "";
        }
    }

    private static String readSpan(Path file, int startLine, int endLine) {
        try {
            List<String> lines = Files.readAllLines(file);
            int from = Math.max(0, startLine - 1);
            int to = Math.min(lines.size(), endLine);
            return String.join("\n", lines.subList(from, to));
        } catch (IOException e) {
            throw new UncheckedIOException("reading span of " + file, e);
        }
    }

    private static SymbolDigest toDigest(Symbol s) {
        return new SymbolDigest(s.id(), s.kind().name(), s.name(), s.signature(),
                s.docFirstLine(), s.startLine(), s.endLine());
    }
}
