package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.port.in.ImpactUseCase;
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
class LedgerCoverageIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired private IndexUseCase index;
    @Autowired private RetrieveUseCase retrieve;
    @Autowired private ImpactUseCase impact;
    @Autowired private LedgerUseCase ledger;

    private static final String GREETER = """
            package demo;
            public class Greeter {
                public String greet(String who) { return "hi " + who; }
            }
            """;
    private static final String CALLER = """
            package demo;
            public class Caller {
                public String run() { return new Greeter().greet("x"); }
            }
            """;

    @Test
    void searchOutlineImpactAllRecordAWholeFileBaseline(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("demo"));
        Files.writeString(dir.resolve("demo/Greeter.java"), GREETER);
        Files.writeString(dir.resolve("demo/Caller.java"), CALLER);
        String repo = "ledgercov";
        index.index(repo, dir.toString());

        Totals before = ledger.totals();
        retrieve.searchSymbols(repo, "greet", 5);
        retrieve.outline(repo, "demo/Greeter.java");
        impact.impactOf(repo + ":demo/Greeter.java:demo.Greeter#greet(String)");
        Totals after = ledger.totals();

        assertThat(after.calls() - before.calls()).isGreaterThanOrEqualTo(3);
        assertThat(after.baselineTokens() - before.baselineTokens()).isPositive();
        assertThat(after.byTool()).extracting(io.github.vedmider.dylext.domain.port.out.LedgerStore.ToolTotal::tool)
                .contains("search_symbols", "outline", "impact_of");
    }
}
