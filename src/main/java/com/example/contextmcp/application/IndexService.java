package com.example.contextmcp.application;

import com.example.contextmcp.domain.model.Hashing;
import com.example.contextmcp.domain.model.Skeleton;
import com.example.contextmcp.domain.model.Symbol;
import com.example.contextmcp.domain.model.SymbolKind;
import com.example.contextmcp.domain.port.in.IndexUseCase;
import com.example.contextmcp.domain.port.out.DigestStore;
import com.example.contextmcp.domain.port.out.SkeletonExtractor;
import com.example.contextmcp.domain.port.out.Tokenizer;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** Walks a repo root, extracts Java skeletons, and persists them, skipping files unchanged by hash. */
@Service
public class IndexService implements IndexUseCase {

    private final SkeletonExtractor extractor;
    private final DigestStore store;
    private final Tokenizer tokenizer;

    public IndexService(SkeletonExtractor extractor, DigestStore store, Tokenizer tokenizer) {
        this.extractor = extractor;
        this.store = store;
        this.tokenizer = tokenizer;
    }

    @Override
    public IndexResult index(String repo, String root) {
        store.saveRepoRoot(repo, root);
        Path base = Path.of(root);
        int scanned = 0;
        int indexed = 0;
        int skipped = 0;
        int symbols = 0;
        try (Stream<Path> walk = Files.walk(base)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
            for (Path file : files) {
                scanned++;
                String rel = base.relativize(file).toString();
                int written = indexFileAbsolute(repo, file, rel);
                if (written < 0) {
                    skipped++;
                } else {
                    indexed++;
                    symbols += written;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("walking " + root, e);
        }
        store.resolvePendingEdges(repo);
        return new IndexResult(repo, scanned, indexed, skipped, symbols);
    }

    @Override
    public int indexFile(String repo, String root, String relativePath) {
        store.saveRepoRoot(repo, root);
        int written = indexFileAbsolute(repo, Path.of(root).resolve(relativePath), relativePath);
        store.resolvePendingEdges(repo);
        return Math.max(written, 0);
    }

    /** @return symbols written, or -1 when skipped because the file hash is unchanged. */
    private int indexFileAbsolute(String repo, Path file, String rel) {
        String source;
        try {
            source = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + file, e);
        }
        String fileHash = Hashing.sha256(source);
        Optional<String> existing = store.fileContentHash(repo, rel);
        if (existing.isPresent() && existing.get().equals(fileHash)) {
            return -1;
        }
        List<Symbol> symbols = extractor.extract(repo, rel, source);
        pruneRemovedSymbols(repo, rel, symbols);
        for (Symbol s : symbols) {
            String text = skeletonText(s);
            store.upsert(s, new Skeleton(s.id(), hashFor(s, fileHash), text, tokenizer.count(text)));
            store.replaceEdgesFrom(s.id(), callEdges(repo, s));
        }
        return symbols.size();
    }

    private static List<com.example.contextmcp.domain.model.CodeEdge> callEdges(String repo, Symbol s) {
        boolean callable = s.kind() == SymbolKind.METHOD || s.kind() == SymbolKind.CONSTRUCTOR;
        if (!callable) {
            return List.of();
        }
        return s.callTargets().stream()
                .map(target -> com.example.contextmcp.domain.model.CodeEdge.call(repo, s.id(), target))
                .toList();
    }

    private void pruneRemovedSymbols(String repo, String rel, List<Symbol> current) {
        var keep = current.stream().map(Symbol::id).collect(java.util.stream.Collectors.toSet());
        for (Symbol existing : store.symbolsInFile(repo, rel)) {
            if (!keep.contains(existing.id())) {
                store.deleteSymbol(existing.id());
            }
        }
    }

    private static String hashFor(Symbol s, String fileHash) {
        return s.kind() == SymbolKind.MODULE ? fileHash : s.contentHash();
    }

    private static String skeletonText(Symbol s) {
        if (s.kind() == SymbolKind.MODULE) {
            return s.signature();
        }
        return s.docFirstLine().isEmpty()
                ? s.signature()
                : s.signature() + "  // " + s.docFirstLine();
    }
}
