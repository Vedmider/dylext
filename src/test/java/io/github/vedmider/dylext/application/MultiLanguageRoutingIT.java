package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.port.in.IndexUseCase;
import io.github.vedmider.dylext.domain.port.in.IndexUseCase.IndexResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
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
@TestPropertySource(properties = "dylext.parsers.python=http://localhost:1")
class MultiLanguageRoutingIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private IndexUseCase index;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry metrics;

    @Test
    void unreachableParserIsReportedDistinctlyNotSilentlyDegraded(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("mod.py"), "def f():\n    return 1\n");

        IndexResult r = index.index("ml-unreachable", dir.toString());

        assertThat(r.parserUnreachable()).containsEntry("python", 1);
        assertThat(r.skippedUnsupported()).doesNotContainKey("python");
        assertThat(r.filesDegraded()).isZero();
        assertThat(r.filesScanned()).isZero();
        assertThat(metrics.counter("dylext.index.parser.unreachable").count()).isGreaterThanOrEqualTo(1.0);
    }
}
