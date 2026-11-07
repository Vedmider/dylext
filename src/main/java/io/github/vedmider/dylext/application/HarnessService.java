package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.port.in.RetrieveUseCase;
import io.github.vedmider.dylext.domain.port.in.RetrieveUseCase.ExpandedSpan;
import io.github.vedmider.dylext.domain.port.in.RetrieveUseCase.SymbolDigest;
import io.github.vedmider.dylext.domain.port.out.DigestStore;
import io.github.vedmider.dylext.domain.port.out.Tokenizer;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A/B token-savings harness. For each task it compares WITH-MCP (search → one digest, drilling to a
 * span only when needed) against WITHOUT (grep-style scan of candidate files + reading them whole),
 * tokenizing both with the same tokenizer so the saving is apples-to-apples.
 */
@Service
public class HarnessService {

    private final RetrieveUseCase retrieve;
    private final DigestStore store;
    private final Tokenizer tokenizer;

    public HarnessService(RetrieveUseCase retrieve, DigestStore store, Tokenizer tokenizer) {
        this.retrieve = retrieve;
        this.store = store;
        this.tokenizer = tokenizer;
    }

    public Report run(String repo, List<Task> tasks) {
        List<Row> rows = new ArrayList<>();
        for (Task task : tasks) {
            rows.add(measure(repo, task));
        }
        int withMcp = rows.stream().mapToInt(Row::withMcpTokens).sum();
        int without = rows.stream().mapToInt(Row::withoutTokens).sum();
        return new Report(rows, withMcp, without, savedPercent(without, withMcp));
    }

    private Row measure(String repo, Task task) {
        List<SymbolDigest> hits = retrieve.searchSymbols(repo, task.query(), task.topK());

        // WITH-MCP: the digests the agent sees, plus one expand when the task drills in.
        StringBuilder served = new StringBuilder();
        for (SymbolDigest d : hits) {
            served.append(d.kind()).append(' ').append(d.name())
                    .append("  ").append(d.signature()).append('\n');
        }
        if (task.expand() && !hits.isEmpty()) {
            ExpandedSpan span = retrieve.expand(hits.get(0).id());
            served.append(span.source());
        }
        int withMcp = tokenizer.count(served.toString());

        // WITHOUT: read every file that holds a candidate symbol, whole.
        List<String> files = hits.stream().map(h -> fileOf(h.id())).distinct().toList();
        String root = store.repoRoot(repo).orElse("");
        int without = 0;
        for (String file : files) {
            without += tokenizer.count(readWhole(Path.of(root).resolve(file)));
        }

        return new Row(task.name(), hits.size(), files.size(), withMcp, without,
                savedPercent(without, withMcp));
    }

    private static String fileOf(String symbolId) {
        int first = symbolId.indexOf(':');
        int second = symbolId.indexOf(':', first + 1);
        return symbolId.substring(first + 1, second);
    }

    private static String readWhole(Path file) {
        try {
            return Files.exists(file) ? Files.readString(file) : "";
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + file, e);
        }
    }

    private static double savedPercent(int without, int withMcp) {
        return without == 0 ? 0.0 : 100.0 * (without - withMcp) / without;
    }

    public record Task(String name, String query, int topK, boolean expand) {
    }

    public record Row(String task, int hits, int files, int withMcpTokens, int withoutTokens,
                      double savedPercent) {
    }

    public record Report(List<Row> rows, int totalWithMcp, int totalWithout, double totalSavedPercent) {
        public String toMarkdown() {
            StringBuilder sb = new StringBuilder();
            sb.append("| task | hits | files | WITH-MCP | WITHOUT | saved |\n");
            sb.append("|---|---:|---:|---:|---:|---:|\n");
            for (Row r : rows) {
                sb.append("| %s | %d | %d | %d | %d | %+.0f%% |%n"
                        .formatted(r.task(), r.hits(), r.files(), r.withMcpTokens(),
                                r.withoutTokens(), r.savedPercent()));
            }
            sb.append("| **total** |  |  | **%d** | **%d** | **%+.0f%%** |%n"
                    .formatted(totalWithMcp, totalWithout, totalSavedPercent));
            return sb.toString();
        }
    }
}
