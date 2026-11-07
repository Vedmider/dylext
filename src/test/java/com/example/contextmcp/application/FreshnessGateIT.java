package com.example.contextmcp.application;

import com.example.contextmcp.domain.port.in.DynamicContextUseCase;
import com.example.contextmcp.domain.port.in.IndexUseCase;
import com.example.contextmcp.domain.port.in.RetrieveUseCase;
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

/** The read-time freshness gate: a new commit is reflected in the static index without an explicit sync. */
@Testcontainers
@SpringBootTest
class FreshnessGateIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired IndexUseCase index;
    @Autowired RetrieveUseCase retrieve;
    @Autowired DynamicContextUseCase dynamic;
    @Autowired DigestStore store;

    private Git initRepo(Path dir) throws Exception {
        return Git.init().setDirectory(dir.toFile()).call();
    }

    private void commit(Git git, String msg) throws Exception {
        git.add().addFilepattern(".").call();
        git.commit().setMessage(msg).setAuthor("T", "t@e.com").setSign(false).call();
    }

    @Test
    void newCommitIsReflectedOnStaticReadWithoutExplicitSync(@TempDir Path dir) throws Exception {
        String repo = "fresh1";
        try (Git git = initRepo(dir)) {
            Files.writeString(dir.resolve("Alpha.java"), "class Alpha { void one() {} }");
            commit(git, "add Alpha");
            index.index(repo, dir.toString());   // sets cursor to this HEAD
            assertThat(retrieve.searchSymbols(repo, "two", 5)).isEmpty();

            // New commit AFTER indexing — cursor is now behind HEAD.
            Files.writeString(dir.resolve("Beta.java"), "class Beta { void two() {} }");
            commit(git, "add Beta");

            // A plain search must auto-sync (gate) and surface the new symbol — no explicit sync call.
            assertThat(retrieve.searchSymbols(repo, "two", 5))
                    .anyMatch(d -> d.name().equals("two"));
            assertThat(store.repoCursor(repo)).isPresent();
        }
    }

    @Test
    void dynamicReadAlsoTriggersFreshness(@TempDir Path dir) throws Exception {
        String repo = "fresh2";
        try (Git git = initRepo(dir)) {
            Files.writeString(dir.resolve("Alpha.java"), "class Alpha { void one() {} }");
            commit(git, "add Alpha");
            index.index(repo, dir.toString());

            Files.writeString(dir.resolve("Gamma.java"), "class Gamma { void three() {} }");
            commit(git, "add Gamma");

            // Calling a DYNAMIC tool reconciles the static index too (gate on the dynamic path).
            dynamic.recentChanges(repo, null, 10);

            assertThat(retrieve.searchSymbols(repo, "three", 5))
                    .anyMatch(d -> d.name().equals("three"));
        }
    }
}
