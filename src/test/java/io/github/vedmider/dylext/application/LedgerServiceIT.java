package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.port.in.IndexUseCase;
import io.github.vedmider.dylext.domain.port.in.LedgerUseCase;
import io.github.vedmider.dylext.domain.port.in.RetrieveUseCase;
import io.github.vedmider.dylext.domain.port.out.LedgerStore.Totals;
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
class LedgerServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private IndexUseCase index;

    @Autowired
    private RetrieveUseCase retrieve;

    @Autowired
    private LedgerUseCase ledger;

    private static final String SRC = """
            package demo;
            public class Big {
                /** Entry point doing a lot of work across many lines. */
                public int compute(int n) {
                    int acc = 0;
                    for (int i = 0; i < n; i++) {
                        acc += i * 2;
                        acc -= (i % 3);
                        acc = Math.max(acc, 0);
                    }
                    return acc;
                }
            }
            """;

    private String indexFixture(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("demo"));
        Files.writeString(dir.resolve("demo/Big.java"), SRC);
        String repo = "led";
        index.index(repo, dir.toString());
        return repo;
    }

    @Test
    void getDigestRecordsServedLessThanWholeFileBaseline(@TempDir Path dir) throws IOException {
        String repo = indexFixture(dir);
        String id = retrieve.searchSymbols(repo, "compute", 1).get(0).id();
        Totals before = ledger.totals();

        retrieve.getDigest(id);

        Totals after = ledger.totals();
        assertThat(after.calls()).isEqualTo(before.calls() + 1);
        assertThat(after.saved()).isGreaterThan(before.saved());
    }

    @Test
    void expandRecordsSpanOnlyCostAndAccumulates(@TempDir Path dir) throws IOException {
        String repo = indexFixture(dir);
        String id = retrieve.searchSymbols(repo, "compute", 1).get(0).id();
        Totals before = ledger.totals();

        retrieve.getDigest(id);
        retrieve.expand(id);

        Totals after = ledger.totals();
        assertThat(after.calls()).isEqualTo(before.calls() + 2);
        assertThat(after.savedPercent()).isGreaterThan(0.0);
    }

    @Test
    void directRecordServedComputesSavedFromTokenizer(@TempDir Path dir) throws IOException {
        indexFixture(dir);
        Totals before = ledger.totals();
        ledger.recordServed("task-1", "manual", "a b c", "a b c d e f g h i j");

        Totals after = ledger.totals();
        assertThat(after.saved()).isGreaterThan(before.saved());
    }
}
