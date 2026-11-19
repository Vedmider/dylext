package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.model.CodeEdge;
import io.github.vedmider.dylext.domain.model.Hashing;
import io.github.vedmider.dylext.domain.model.IndexGeneration;
import io.github.vedmider.dylext.domain.model.Skeleton;
import io.github.vedmider.dylext.domain.model.Symbol;
import io.github.vedmider.dylext.domain.model.SymbolKind;
import io.github.vedmider.dylext.domain.port.in.IndexUseCase;
import io.github.vedmider.dylext.domain.port.out.DigestStore;
import io.github.vedmider.dylext.domain.port.out.SkeletonExtractor;
import io.github.vedmider.dylext.domain.port.out.Tokenizer;
import io.github.vedmider.dylext.domain.port.out.VcsClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Indexes a repo root (the repo or any module folder in it): lists the files git would consider
 * (honouring .gitignore), extracts skeletons through the language router, and persists them,
 * skipping files unchanged by hash. One unreadable or unparsable file never aborts the run — it is
 * counted and reported, so the caller can see exactly what was and was not indexed.
 */
@Service
public class IndexService implements IndexUseCase {

    private static final int PROBLEM_SAMPLE = 5;
    private static final int BINARY_SNIFF_BYTES = 8000;
    /** Stored on a degraded file's MODULE hash so the next index run retries it (e.g. parser recovered). */
    private static final String RETRY_HASH_PREFIX = "unparsed:";

    private final LanguageRouter router;
    private final DigestStore store;
    private final Tokenizer tokenizer;
    private final VcsClient vcs;
    private final io.micrometer.core.instrument.MeterRegistry metrics;

    public IndexService(LanguageRouter router, DigestStore store, Tokenizer tokenizer, VcsClient vcs,
                        io.micrometer.core.instrument.MeterRegistry metrics) {
        this.router = router;
        this.store = store;
        this.tokenizer = tokenizer;
        this.vcs = vcs;
        this.metrics = metrics;
    }

    @Override
    public IndexResult index(String repo, String root) {
        Path base = RootPaths.requireDirectory(root);
        String rootStr = base.toString();

        // re-pointing a repo name at another folder must not leave the old folder's symbols behind
        String replaced = store.repoRoot(repo).filter(old -> !old.equals(rootStr)).orElse(null);
        boolean rebuild = store.indexGeneration(repo) < IndexGeneration.CURRENT;   // older extractor: re-extract all
        if (replaced != null) {
            store.clearRepo(repo);
        }
        store.saveRepoRoot(repo, rootStr);

        Tally tally = new Tally();
        for (String rel : candidateFiles(base, rootStr)) {
            LanguageRouter.Routing routing = router.route(rel);
            switch (routing.route()) {
                case UNSUPPORTED -> {
                    if (routing.key() != null) {
                        tally.unsupported.merge(routing.key(), 1, Integer::sum);
                    }
                }
                case UNREACHABLE -> tally.unreachable.merge(routing.key(), 1, Integer::sum);
                case PARSE -> {
                    tally.scanned++;
                    tally.record(rel, indexFileAbsolute(repo, base.resolve(rel), rel, routing.extractor(), rebuild));
                }
            }
        }
        store.resolvePendingEdges(repo);
        store.saveIndexGeneration(repo, IndexGeneration.CURRENT);

        String head = vcs.headSha(rootStr);
        if (head != null) {
            store.saveRepoCursor(repo, head);
        }
        recordIndexMetrics(tally);
        return new IndexResult(repo, tally.scanned, tally.indexed, tally.unchanged, tally.symbols,
                tally.failed, tally.degraded, tally.unsupported, tally.unreachable, tally.problems,
                vcs.currentBranch(rootStr), head, replaced);
    }

    /** Index-health counters for observability (filesFailed/degraded/parserUnreachable + symbols indexed). */
    private void recordIndexMetrics(Tally tally) {
        metrics.counter("dylext.index.files.indexed").increment(tally.indexed);
        metrics.counter("dylext.index.files.failed").increment(tally.failed);
        metrics.counter("dylext.index.files.degraded").increment(tally.degraded);
        metrics.counter("dylext.index.symbols").increment(tally.symbols);
        int unreachable = tally.unreachable.values().stream().mapToInt(Integer::intValue).sum();
        metrics.counter("dylext.index.parser.unreachable").increment(unreachable);
        int unsupported = tally.unsupported.values().stream().mapToInt(Integer::intValue).sum();
        metrics.counter("dylext.index.files.unsupported").increment(unsupported);
    }

    @Override
    public int indexFile(String repo, String root, String relativePath) {
        store.saveRepoRoot(repo, root);
        LanguageRouter.Routing routing = router.route(relativePath);
        if (routing.route() != LanguageRouter.Route.PARSE) {
            return 0;
        }
        FileOutcome outcome = indexFileAbsolute(repo, Path.of(root).resolve(relativePath), relativePath,
                routing.extractor(), false);
        store.resolvePendingEdges(repo);
        return Math.max(outcome.symbols, 0);
    }

    /** Files to consider, relative to {@code base}: git's view when in a work tree, else every file. */
    private List<String> candidateFiles(Path base, String rootStr) {
        Optional<List<String>> listed = vcs.listWorkingFiles(rootStr);
        if (listed.isPresent()) {
            return listed.get().stream().sorted().toList();
        }
        try (Stream<Path> walk = Files.walk(base)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> base.relativize(p).toString().replace('\\', '/'))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("walking " + base, e);
        }
    }

    private FileOutcome indexFileAbsolute(String repo, Path file, String rel, SkeletonExtractor extractor,
                                          boolean rebuild) {
        String source;
        try {
            source = readSource(file);
        } catch (IOException e) {
            return FileOutcome.failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        String fileHash = Hashing.sha256(source);
        Optional<String> existing = store.fileContentHash(repo, rel);
        if (!rebuild && existing.isPresent() && existing.get().equals(fileHash)) {
            return FileOutcome.UNCHANGED;
        }
        List<Symbol> symbols;
        try {
            symbols = extractor.extract(repo, rel, source);
        } catch (RuntimeException parserCrash) {
            return FileOutcome.failed("parser error: " + parserCrash.getMessage());
        }
        boolean degraded = symbols.stream().anyMatch(IndexService::isUnparsedModule);
        List<Symbol> toStore = degraded ? symbols.stream().map(IndexService::retryable).toList() : symbols;

        store.atomically(() -> {
            pruneRemovedSymbols(repo, rel, toStore);
            for (Symbol s : toStore) {
                String text = skeletonText(s);
                store.upsert(s, new Skeleton(s.id(), hashFor(s, fileHash), text, tokenizer.count(text)));
                store.replaceEdgesFrom(s.id(), callEdges(repo, s));
            }
        });
        return FileOutcome.indexed(realSymbolCount(toStore), degraded);
    }

    /** Non-module symbols only — matches how listRepos/fileStats count (symbol_path <> '<module>'). */
    private static int realSymbolCount(List<Symbol> symbols) {
        return (int) symbols.stream().filter(s -> s.kind() != SymbolKind.MODULE).count();
    }

    /**
     * Reads a source file as UTF-8, falling back to ISO-8859-1 for legacy encodings. Binary content
     * (NUL bytes) is rejected so garbage never reaches a parser.
     */
    static String readSource(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int sniff = Math.min(bytes.length, BINARY_SNIFF_BYTES);
        for (int i = 0; i < sniff; i++) {
            if (bytes[i] == 0) {
                throw new IOException("binary file");
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    private static boolean isUnparsedModule(Symbol s) {
        return s.kind() == SymbolKind.MODULE && s.signature().startsWith(Symbol.UNPARSED_PREFIX);
    }

    /** A degraded MODULE gets a hash that never equals the file hash, so the next run re-extracts it. */
    private static Symbol retryable(Symbol s) {
        if (!isUnparsedModule(s)) {
            return s;
        }
        return new Symbol(s.id(), s.repo(), s.filePath(), s.language(), s.kind(), s.name(), s.symbolPath(),
                s.signature(), s.docFirstLine(), s.startLine(), s.endLine(), s.callTargets(),
                RETRY_HASH_PREFIX + s.contentHash());
    }

    private static List<CodeEdge> callEdges(String repo, Symbol s) {
        boolean callable = s.kind() == SymbolKind.METHOD || s.kind() == SymbolKind.CONSTRUCTOR;
        if (!callable) {
            return List.of();
        }
        return s.callTargets().stream()
                .map(t -> toEdge(repo, s.id(), t))   // "[recv.]name[/arity]" -> precise edge
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    /** Parse a call target "[recv.]name[/arity][#T1,T2]" into an edge with name, arity, receiver, param types. */
    static CodeEdge toEdge(String repo, String srcId, String rawTarget) {
        String target = rawTarget;
        String paramTypes = null;
        int hash = target.indexOf('#');
        if (hash >= 0) {
            String types = target.substring(hash + 1);
            paramTypes = types.isBlank() ? null : types;
            target = target.substring(0, hash);
        }
        Integer arity = null;
        int slash = target.lastIndexOf('/');
        if (slash > 0 && slash < target.length() - 1
                && target.substring(slash + 1).chars().allMatch(Character::isDigit)) {
            arity = Integer.valueOf(target.substring(slash + 1));
            target = target.substring(0, slash);
        }
        int dot = target.lastIndexOf('.');
        String name = dot >= 0 && dot < target.length() - 1 ? target.substring(dot + 1) : target;
        String recv = dot > 0 ? target.substring(0, dot) : null;
        return name.isBlank() ? null : CodeEdge.call(repo, srcId, name, arity, recv, paramTypes);
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

    private record FileOutcome(Kind kind, int symbols, boolean degraded, String error) {
        enum Kind { INDEXED, UNCHANGED, FAILED }

        static final FileOutcome UNCHANGED = new FileOutcome(Kind.UNCHANGED, -1, false, null);

        static FileOutcome indexed(int symbols, boolean degraded) {
            return new FileOutcome(Kind.INDEXED, symbols, degraded, null);
        }

        static FileOutcome failed(String error) {
            return new FileOutcome(Kind.FAILED, -1, false, error);
        }
    }

    private static final class Tally {
        int scanned;
        int indexed;
        int unchanged;
        int failed;
        int degraded;
        int symbols;
        final Map<String, Integer> unsupported = new LinkedHashMap<>();
        final Map<String, Integer> unreachable = new LinkedHashMap<>();
        final List<String> problems = new ArrayList<>();

        void record(String rel, FileOutcome o) {
            switch (o.kind()) {
                case UNCHANGED -> unchanged++;
                case FAILED -> {
                    failed++;
                    problem(rel + ": " + o.error());
                }
                case INDEXED -> {
                    indexed++;
                    symbols += o.symbols();
                    if (o.degraded()) {
                        degraded++;
                        problem(rel + ": parser could not parse it (module-only entry)");
                    }
                }
            }
        }

        private void problem(String text) {
            if (problems.size() < PROBLEM_SAMPLE) {
                problems.add(text);
            }
        }
    }
}
