package com.example.contextmcp.application;

import com.example.contextmcp.domain.port.in.SyncUseCase;
import com.example.contextmcp.domain.port.in.SyncUseCase.SyncResult;
import com.example.contextmcp.domain.port.out.DigestStore;
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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class SyncServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private SyncUseCase sync;

    @Autowired
    private DigestStore store;

    private static final String ALPHA = "package demo;\npublic class Alpha { public int one() { return 1; } }\n";
    private static final String BETA = "package demo;\npublic class Beta { public int two() { return 2; } }\n";

    private Git initRepoWithTwoFiles(Path dir) throws Exception {
        Git git = Git.init().setDirectory(dir.toFile()).call();
        Files.createDirectories(dir.resolve("demo"));
        Files.writeString(dir.resolve("demo/Alpha.java"), ALPHA);
        Files.writeString(dir.resolve("demo/Beta.java"), BETA);
        commit(git, "initial");
        return git;
    }

    private void commit(Git git, String message) throws Exception {
        git.add().addFilepattern(".").call();
        git.commit().setMessage(message).setAuthor("T", "t@e.com").setSign(false).call();
    }

    @Test
    void firstSyncWithoutCursorDoesFullIndexAndSetsCursor(@TempDir Path dir) throws Exception {
        String repo = "syn1";
        try (Git ignored = initRepoWithTwoFiles(dir)) {
            SyncResult r = sync.sync(repo, dir.toString());

            assertThat(r.fullIndex()).isTrue();
            assertThat(r.toCursor()).isNotBlank();
            assertThat(store.repoCursor(repo)).isPresent();
            assertThat(store.filesInRepo(repo))
                    .containsExactlyInAnyOrder("demo/Alpha.java", "demo/Beta.java");
        }
    }

    @Test
    void secondSyncReindexesOnlyChangedFile(@TempDir Path dir) throws Exception {
        String repo = "syn2";
        try (Git git = initRepoWithTwoFiles(dir)) {
            sync.sync(repo, dir.toString());

            Files.writeString(dir.resolve("demo/Alpha.java"),
                    ALPHA.replace("one", "oneRenamed"));
            commit(git, "rename in Alpha");

            SyncResult r = sync.sync(repo, dir.toString());

            assertThat(r.fullIndex()).isFalse();
            assertThat(r.filesChanged()).isEqualTo(1);
            assertThat(store.symbolsInFile(repo, "demo/Alpha.java"))
                    .anyMatch(s -> s.name().equals("oneRenamed"))
                    .noneMatch(s -> s.name().equals("one"));
            assertThat(store.symbolsInFile(repo, "demo/Beta.java"))
                    .anyMatch(s -> s.name().equals("two"));
        }
    }

    @Test
    void syncRemovesDeletedFile(@TempDir Path dir) throws Exception {
        String repo = "syn3";
        try (Git git = initRepoWithTwoFiles(dir)) {
            sync.sync(repo, dir.toString());

            Files.delete(dir.resolve("demo/Beta.java"));
            git.rm().addFilepattern("demo/Beta.java").call();
            git.commit().setMessage("remove Beta").setAuthor("T", "t@e.com").setSign(false).call();

            sync.sync(repo, dir.toString());

            assertThat(store.filesInRepo(repo)).containsExactly("demo/Alpha.java");
        }
    }

    @Test
    void cursorAdvancesBetweenSyncs(@TempDir Path dir) throws Exception {
        String repo = "syn4";
        try (Git git = initRepoWithTwoFiles(dir)) {
            sync.sync(repo, dir.toString());
            String first = store.repoCursor(repo).orElseThrow();

            Files.writeString(dir.resolve("demo/Alpha.java"), ALPHA.replace("1", "11"));
            commit(git, "change");
            sync.sync(repo, dir.toString());

            assertThat(store.repoCursor(repo)).get().isNotEqualTo(first);
        }
    }
}
