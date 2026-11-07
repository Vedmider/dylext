package com.example.contextmcp.application;

import com.example.contextmcp.domain.model.Digest;
import com.example.contextmcp.domain.model.Skeleton;
import com.example.contextmcp.domain.model.Summary;
import com.example.contextmcp.domain.model.Symbol;
import com.example.contextmcp.domain.port.in.RetrieveUseCase;
import com.example.contextmcp.domain.port.out.DigestStore;
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
    private final com.example.contextmcp.domain.port.in.LedgerUseCase ledger;
    private final SummaryService summaries;
    private final FreshnessGate freshness;

    public ContextService(DigestStore store,
                          com.example.contextmcp.domain.port.in.LedgerUseCase ledger,
                          SummaryService summaries,
                          FreshnessGate freshness) {
        this.store = store;
        this.ledger = ledger;
        this.summaries = summaries;
        this.freshness = freshness;
    }

    @Override
    public List<SymbolDigest> outline(String repo, String filePath) {
        freshness.ensureFresh(repo);
        return store.symbolsInFile(repo, filePath).stream().map(ContextService::toDigest).toList();
    }

    @Override
    public List<SymbolDigest> searchSymbols(String repo, String query, int topK) {
        freshness.ensureFresh(repo);
        return store.searchSymbols(repo, query, topK).stream().map(ContextService::toDigest).toList();
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
