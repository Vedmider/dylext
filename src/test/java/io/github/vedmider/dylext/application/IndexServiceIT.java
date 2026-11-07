package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.port.in.IndexUseCase;
import io.github.vedmider.dylext.domain.port.in.IndexUseCase.IndexResult;
import io.github.vedmider.dylext.domain.port.out.DigestStore;
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
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class IndexServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private IndexUseCase index;

    @Autowired
    private DigestStore store;

    private static final String A = """
            package demo;
            public class Alpha {
                public int one() { return 1; }
            }
            """;

    private static final String B = """
            package demo;
            public class Beta {
                public int two() { return 2; }
            }
            """;

    private Path writeRepo(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("demo"));
        Files.writeString(dir.resolve("demo/Alpha.java"), A);
        Files.writeString(dir.resolve("demo/Beta.java"), B);
        Files.writeString(dir.resolve("README.md"), "not java");
        return dir;
    }

    @Test
    void indexesOnlyJavaFilesAndPersistsSymbols(@TempDir Path dir) throws IOException {
        String repo = "idx1";
        IndexResult r = index.index(repo, writeRepo(dir).toString());

        assertThat(r.filesScanned()).isEqualTo(2);
        assertThat(r.filesIndexed()).isEqualTo(2);
        assertThat(r.symbols()).isGreaterThanOrEqualTo(4);
        assertThat(store.filesInRepo(repo)).containsExactlyInAnyOrder("demo/Alpha.java", "demo/Beta.java");
        assertThat(store.symbolsInFile(repo, "demo/Alpha.java"))
                .anyMatch(s -> s.name().equals("one"));
    }

    @Test
    void reindexWithNoChangesSkipsEverything(@TempDir Path dir) throws IOException {
        String repo = "idx2";
        String root = writeRepo(dir).toString();
        index.index(repo, root);

        IndexResult second = index.index(repo, root);

        assertThat(second.filesScanned()).isEqualTo(2);
        assertThat(second.filesSkipped()).isEqualTo(2);
        assertThat(second.filesIndexed()).isZero();
    }

    @Test
    void reindexAfterOneFileChangesUpdatesOnlyThatFile(@TempDir Path dir) throws IOException {
        String repo = "idx3";
        String root = writeRepo(dir).toString();
        index.index(repo, root);

        Files.writeString(dir.resolve("demo/Alpha.java"), A.replace("one", "oneRenamed"));
        IndexResult second = index.index(repo, root);

        assertThat(second.filesIndexed()).isEqualTo(1);
        assertThat(second.filesSkipped()).isEqualTo(1);
        assertThat(store.symbolsInFile(repo, "demo/Alpha.java"))
                .anyMatch(s -> s.name().equals("oneRenamed"))
                .noneMatch(s -> s.name().equals("one"));
    }
}
