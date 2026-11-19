package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.port.in.IndexUseCase;
import io.github.vedmider.dylext.domain.port.in.RepoCatalogUseCase;
import io.github.vedmider.dylext.domain.port.in.RepoCatalogUseCase.RepoOverview;
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
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
class RepoCatalogServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private IndexUseCase index;

    @Autowired
    private RepoCatalogUseCase catalog;

    private static void write(Path dir, String rel, String content) throws Exception {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private static String java(String cls) {
        return "package p;\npublic class " + cls + " { public int run() { return 1; } }\n";
    }

    @Test
    void multiModuleRepoShowsItsModulesLanguagesAndFolders(@TempDir Path dir) throws Exception {
        try (Git git = Git.init().setDirectory(dir.toFile()).setInitialBranch("main").call()) {
            write(dir, "pom.xml", "<project/>");
            write(dir, "core/pom.xml", "<project/>");
            write(dir, "core/src/A.java", java("A"));
            write(dir, "web/pom.xml", "<project/>");
            write(dir, "web/src/B.java", java("B"));
            write(dir, "web/ui/package.json", "{}");
            write(dir, "web/ui/app.ts", "export const x = 1;\n");
            write(dir, "native/util.c", "int main(){return 0;}\n");
            write(dir, "target/Gen.java", java("Gen"));
            write(dir, ".gitignore", "target/\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("i").setAuthor("T", "t@e.com").setCommitter("T", "t@e.com").setSign(false).call();
            index.index("cat1", dir.toString());

            RepoOverview o = catalog.overview("cat1");

            assertThat(o.branch()).isEqualTo("main");
            assertThat(o.modules()).extracting(m -> m.path() + ":" + m.kind())
                    .containsExactly(".:maven", "core:maven", "web:maven", "web/ui:node");
            assertThat(o.languages()).containsEntry("java", 2);
            assertThat(o.unsupported()).containsEntry("typescript", 1).containsEntry("c", 1);
            assertThat(o.unsupported()).doesNotContainKey("java");
            assertThat(o.topLevel()).extracting(RepoCatalogUseCase.DirStat::dir).containsExactlyInAnyOrder("core", "web");
            assertThat(catalog.listRepos()).anyMatch(r -> r.repo().equals("cat1") && r.files() == 2 && r.symbols() >= 2);
        }
    }

    @Test
    void overviewOfAnUnindexedRepoIsAnError() {
        assertThatThrownBy(() -> catalog.overview("nope")).isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("not indexed");
    }

    @Test
    void languagesTellWhichOnesCanActuallyBeParsed() {
        assertThat(catalog.languages()).anyMatch(l -> l.language().equals("java") && l.available()
                && l.parser().equals("in-process") && l.extensions().contains("java"));
    }
}
