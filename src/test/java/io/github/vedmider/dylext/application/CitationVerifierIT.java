package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.model.VerifiedAnswer;
import io.github.vedmider.dylext.domain.model.VerifiedAnswer.Status;
import io.github.vedmider.dylext.domain.port.in.IndexUseCase;
import io.github.vedmider.dylext.domain.port.in.VerifyUseCase;
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
class CitationVerifierIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private IndexUseCase index;

    @Autowired
    private VerifyUseCase verify;

    private static final String SOURCE = """
            package demo;
            public class Greeter {
                public String greet(String who) { return "hi " + who; }
            }
            """;

    private String indexFixture(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("demo"));
        Files.writeString(dir.resolve("demo/Greeter.java"), SOURCE);
        String repo = "ver";
        index.index(repo, dir.toString());
        return repo;
    }

    @Test
    void realSymbolIsVerifiedWithSignatureAndLocation(@TempDir Path dir) throws IOException {
        String repo = indexFixture(dir);

        VerifiedAnswer result = verify.verify("the greeting is produced by Greeter#greet", repo);

        assertThat(result.citations())
                .filteredOn(c -> c.token().equals("greet"))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.status()).isEqualTo(Status.VERIFIED);
                    assertThat(c.filePath()).isEqualTo("demo/Greeter.java");
                    assertThat(c.signature()).contains("greet");
                    assertThat(c.startLine()).isGreaterThan(0);
                });
    }

    @Test
    void hallucinatedSymbolIsUnverified(@TempDir Path dir) throws IOException {
        String repo = indexFixture(dir);

        VerifiedAnswer result = verify.verify("it calls GhostService#vanish() to clean up", repo);

        assertThat(result.unverifiedCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.citations())
                .filteredOn(c -> c.token().equals("vanish"))
                .singleElement()
                .satisfies(c -> assertThat(c.status()).isEqualTo(Status.UNVERIFIED));
    }

    @Test
    void symbolIsScopedToItsRepo(@TempDir Path dir) throws IOException {
        indexFixture(dir);   // indexes repo "ver"

        VerifiedAnswer result = verify.verify("the greeting is produced by Greeter#greet", "other-repo");

        assertThat(result.citations())
                .filteredOn(c -> c.token().equals("greet"))
                .allSatisfy(c -> assertThat(c.status()).isEqualTo(Status.UNVERIFIED));
    }

    @Test
    void proseOnlyAnswerHasNoCitations(@TempDir Path dir) throws IOException {
        String repo = indexFixture(dir);

        VerifiedAnswer result = verify.verify(
                "The greeting is returned to the caller after processing completes.", repo);

        assertThat(result.citations()).isEmpty();
        assertThat(result.unverifiedCount()).isZero();
    }
}
