package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.port.in.DynamicContextUseCase;
import io.github.vedmider.dylext.domain.port.out.DigestStore;
import io.github.vedmider.dylext.domain.port.out.VcsClient.Commit;
import io.github.vedmider.dylext.domain.port.out.VcsClient.WorkingChange;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class DynamicContextServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private DynamicContextUseCase dynamic;

    @Autowired
    private DigestStore store;

    private Git initRepo(Path dir, String repo) throws Exception {
        Git git = Git.init().setDirectory(dir.toFile()).call();
        store.saveRepoRoot(repo, dir.toString());
        return git;
    }

    private void commit(Git git, String message) throws Exception {
        git.add().addFilepattern(".").call();
        git.commit().setMessage(message).setAuthor("Tester", "t@example.com").setSign(false).call();
    }

    @Test
    void recentChangesListsCommitsNewestFirst(@TempDir Path dir) throws Exception {
        String repo = "dyn1";
        try (Git git = initRepo(dir, repo)) {
            Files.writeString(dir.resolve("A.java"), "class A {}");
            commit(git, "add A");
            Files.writeString(dir.resolve("B.java"), "class B {}");
            commit(git, "add B");
        }

        List<Commit> commits = dynamic.recentChanges(repo, null, 10);

        assertThat(commits).extracting(Commit::message).containsExactly("add B", "add A");
    }

    @Test
    void recentChangesCanFilterByPath(@TempDir Path dir) throws Exception {
        String repo = "dyn2";
        try (Git git = initRepo(dir, repo)) {
            Files.writeString(dir.resolve("A.java"), "class A {}");
            commit(git, "add A");
            Files.writeString(dir.resolve("B.java"), "class B {}");
            commit(git, "add B");
        }

        List<Commit> onlyA = dynamic.recentChanges(repo, "A.java", 10);

        assertThat(onlyA).extracting(Commit::message).containsExactly("add A");
    }

    @Test
    void workingChangesShowsUncommittedEditVsHead(@TempDir Path dir) throws Exception {
        String repo = "dyn3";
        try (Git git = initRepo(dir, repo)) {
            Files.writeString(dir.resolve("A.java"), "class A { int x; }");
            commit(git, "initial");
        }
        Files.writeString(dir.resolve("A.java"), "class A { int x; int y; }");

        List<WorkingChange> changes = dynamic.workingChanges(repo, null);

        assertThat(changes).hasSize(1);
        WorkingChange ch = changes.get(0);
        assertThat(ch.path()).isEqualTo("A.java");
        assertThat(ch.changeType()).isEqualTo("MODIFY");
        assertThat(ch.diff()).contains("int y;");
    }

    @Test
    void workingChangesEmptyOnCleanTree(@TempDir Path dir) throws Exception {
        String repo = "dyn4";
        try (Git git = initRepo(dir, repo)) {
            Files.writeString(dir.resolve("A.java"), "class A {}");
            commit(git, "initial");
        }

        assertThat(dynamic.workingChanges(repo, null)).isEmpty();
    }

    @Test
    void workingChangesDetectsUntrackedNewFile(@TempDir Path dir) throws Exception {
        String repo = "dyn5";
        try (Git git = initRepo(dir, repo)) {
            Files.writeString(dir.resolve("A.java"), "class A {}");
            commit(git, "initial");
        }
        Files.writeString(dir.resolve("New.java"), "class New {}");

        List<WorkingChange> changes = dynamic.workingChanges(repo, null);

        assertThat(changes).extracting(WorkingChange::path).contains("New.java");
        assertThat(changes).anyMatch(c -> c.changeType().equals("ADD"));
    }
}
