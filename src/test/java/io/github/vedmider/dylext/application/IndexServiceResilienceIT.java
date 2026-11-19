package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.model.Symbol;
import io.github.vedmider.dylext.domain.port.in.IndexUseCase;
import io.github.vedmider.dylext.domain.port.in.IndexUseCase.IndexResult;
import io.github.vedmider.dylext.domain.port.in.RetrieveUseCase;
import io.github.vedmider.dylext.domain.port.out.DigestStore;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a developer sees when indexing real-world folders: odd files, unsupported languages, git, roots. */
@Testcontainers
@SpringBootTest
class IndexServiceResilienceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private IndexUseCase index;

    @Autowired
    private RetrieveUseCase retrieve;

    @Autowired
    private DigestStore store;

    private static String java(String cls, String... methods) {
        StringBuilder b = new StringBuilder("package p;\npublic class " + cls + " {\n");
        for (String m : methods) {
            b.append("  public int ").append(m).append("() { return 1; }\n");
        }
        return b.append("}\n").toString();
    }

    private static void write(Path dir, String rel, String content) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private static void commit(Git git, String msg) throws Exception {
        git.add().addFilepattern(".").call();
        git.commit().setMessage(msg).setAuthor("T", "t@e.com").setCommitter("T", "t@e.com").setSign(false).call();
    }

    @Test
    void oneUnreadableFileNeverAbortsTheRun(@TempDir Path dir) throws Exception {
        write(dir, "Good1.java", java("Good1", "one"));
        write(dir, "Good2.java", java("Good2", "two"));
        Files.write(dir.resolve("Latin1.java"),
                "package p;\n// caf\u00e9\npublic class Latin1 { public int latinMethod() { return 1; } }\n"
                        .getBytes(StandardCharsets.ISO_8859_1));
        Files.write(dir.resolve("Blob.java"), new byte[]{'p', 0, 1, 2, 0, 3});

        IndexResult r = index.index("res1", dir.toString());

        assertThat(r.filesIndexed()).isEqualTo(3);
        assertThat(r.filesFailed()).isEqualTo(1);
        assertThat(r.problems()).anyMatch(p -> p.startsWith("Blob.java"));
        assertThat(store.searchSymbols("res1", "latinMethod", 5)).extracting(Symbol::name).contains("latinMethod");
    }

    @Test
    void sourceFilesWithoutAParserAreReportedNotSilentlyDropped(@TempDir Path dir) throws Exception {
        write(dir, "A.java", java("A", "a"));
        write(dir, "ui/app.ts", "export const x = 1;\n");
        write(dir, "native/lib.rs", "fn main() {}\n");
        write(dir, "tools/gen.py", "def gen():\n    pass\n");
        write(dir, "README.md", "# docs\n");

        IndexResult r = index.index("res2", dir.toString());

        assertThat(r.filesScanned()).isEqualTo(1);
        assertThat(r.skippedUnsupported()).containsExactlyInAnyOrderEntriesOf(Map.of("typescript", 1, "rs", 1, "python", 1));
    }

    @Test
    void aFileTheParserCannotParseIsReportedAsDegradedAndRetriedLater(@TempDir Path dir) throws Exception {
        write(dir, "Ok.java", java("Ok", "fine"));
        write(dir, "Broken.java", "public class {{{ this is not java\n");

        IndexResult first = index.index("res3", dir.toString());
        assertThat(first.filesDegraded()).isEqualTo(1);
        assertThat(first.problems()).anyMatch(p -> p.startsWith("Broken.java"));

        IndexResult again = index.index("res3", dir.toString());
        assertThat(again.filesDegraded()).as("a degraded file is retried, not skipped as unchanged").isEqualTo(1);

        write(dir, "Broken.java", java("Broken", "fixedNow"));
        IndexResult fixed = index.index("res3", dir.toString());
        assertThat(fixed.filesDegraded()).isZero();
        assertThat(store.searchSymbols("res3", "fixedNow", 5)).isNotEmpty();
    }

    @Test
    void rootMustBeAnExistingAbsoluteDirectory(@TempDir Path dir) throws Exception {
        write(dir, "F.java", java("F", "f"));

        assertThatThrownBy(() -> index.index("res4", "relative/dir"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("absolute");
        assertThatThrownBy(() -> index.index("res4", dir.resolve("nope").toString()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not exist");
        assertThatThrownBy(() -> index.index("res4", dir.resolve("F.java").toString()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a directory");
    }

    @Test
    void repointingARepoNameAtAnotherFolderPurgesTheOldSymbols(@TempDir Path a, @TempDir Path b) throws Exception {
        write(a, "OnlyA.java", java("OnlyA", "onlyAsym"));
        write(b, "OnlyB.java", java("OnlyB", "onlyBsym"));

        index.index("res5", a.toString());
        IndexResult second = index.index("res5", b.toString());

        assertThat(second.replacedRoot()).isEqualTo(a.toString());
        assertThat(store.searchSymbols("res5", "onlyAsym", 5)).isEmpty();
        assertThat(store.searchSymbols("res5", "onlyBsym", 5)).isNotEmpty();
    }

    @Test
    void gitignoredFilesAreNotIndexedButUntrackedOnesAre(@TempDir Path dir) throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            write(dir, ".gitignore", "build/\n");
            write(dir, "src/Real.java", java("Real", "realMethod"));
            commit(git, "init");
            write(dir, "build/gen/Gen.java", java("Gen", "ignoredGen"));
            write(dir, "src/Fresh.java", java("Fresh", "freshUntracked"));

            index.index("res6", dir.toString());

            assertThat(store.searchSymbols("res6", "ignoredGen", 5)).isEmpty();
            assertThat(store.searchSymbols("res6", "realMethod", 5)).isNotEmpty();
            assertThat(store.searchSymbols("res6", "freshUntracked", 5)).isNotEmpty();
        }
    }

    @Test
    void aFolderThatIsNotAGitRepoIsStillIndexed(@TempDir Path dir) throws Exception {
        write(dir, "x/F.java", java("F", "plainFolder"));

        IndexResult r = index.index("res7", dir.toString());

        assertThat(r.filesIndexed()).isEqualTo(1);
        assertThat(r.head()).isNull();
        assertThat(r.branch()).isNull();
    }

    @Test
    void indexRecordsTheHeadAndBranchItReflects(@TempDir Path dir) throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            write(dir, "F.java", java("F", "f"));
            commit(git, "init");

            IndexResult r = index.index("res8", dir.toString());

            assertThat(r.head()).isNotBlank();
            assertThat(r.branch()).isEqualTo(git.getRepository().getBranch());
            assertThat(store.repoCursor("res8")).contains(r.head());
        }
    }

    @Test
    void indexingOnlyASubfolderStillResyncsWhenYouCommit(@TempDir Path dir) throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).call()) {
            write(dir, "src/F.java", java("F", "base"));
            write(dir, "docs/readme.md", "# docs\n");
            commit(git, "init");
            index.index("res9", dir.resolve("src").toString());
            assertThat(retrieve.searchSymbols("res9", "lateAdded", 5)).isEmpty();

            write(dir, "src/F.java", java("F", "base", "lateAdded"));
            commit(git, "add method");

            assertThat(retrieve.searchSymbols("res9", "lateAdded", 5)).as("freshness must work from a subfolder root")
                    .isNotEmpty();
        }
    }

    @Test
    void queryingAnUnindexedRepoFailsLoudlyInsteadOfReturningEmpty() {
        assertThatThrownBy(() -> retrieve.searchSymbols("never-indexed", "x", 5))
                .isInstanceOf(java.util.NoSuchElementException.class).hasMessageContaining("not indexed");
        assertThatThrownBy(() -> retrieve.outline("never-indexed", "A.java"))
                .isInstanceOf(java.util.NoSuchElementException.class).hasMessageContaining("not indexed");
    }

    @Test
    void outlineOfAnUnknownPathInAnIndexedRepoFailsInsteadOfReturningEmpty(@TempDir Path dir) throws Exception {
        write(dir, "demo/Real.java", java("Real", "run"));
        String repo = "outline-unknown";
        index.index(repo, dir.toString());
        assertThatThrownBy(() -> retrieve.outline(repo, "demo/Nope.java"))
                .isInstanceOf(java.util.NoSuchElementException.class).hasMessageContaining("not indexed");
    }

    @Test
    void searchCanBeScopedToAPathPrefixSoOneModuleCanBeQueried(@TempDir Path dir) throws Exception {
        write(dir, "core/src/Shared.java", java("Shared", "process"));
        write(dir, "web/src/Page.java", java("Page", "process"));
        write(dir, "webapp/src/Other.java", java("Other", "process"));
        index.index("res10", dir.toString());

        assertThat(retrieve.searchSymbols("res10", "process", 10)).hasSize(3);
        assertThat(retrieve.searchSymbols("res10", "process", 10, "web/")).extracting(RetrieveUseCase.SymbolDigest::id)
                .allMatch(id -> id.startsWith("res10:web/src/Page.java:")).hasSize(1);
        assertThat(retrieve.searchSymbols("res10", "process", 10, "./core")).hasSize(1);
        assertThat(retrieve.searchSymbols("res10", "process", 10, "")).hasSize(3);
    }
}
