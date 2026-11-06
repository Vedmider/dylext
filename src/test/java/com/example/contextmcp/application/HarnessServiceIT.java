package com.example.contextmcp.application;

import com.example.contextmcp.application.HarnessService.Report;
import com.example.contextmcp.application.HarnessService.Task;
import com.example.contextmcp.domain.port.in.IndexUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class HarnessServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private IndexUseCase index;

    @Autowired
    private HarnessService harness;

    private static final List<Task> TASKS = List.of(
            new Task("find retrieval service", "searchSymbols", 5, true),
            new Task("locate token ledger", "recordServed", 5, true),
            new Task("git working changes", "workingChanges", 5, false));

    @Test
    void producesPopulatedSavingsTableOverRealTasks() {
        String repo = "self";
        index.index(repo, "src/main/java");

        Report report = harness.run(repo, TASKS);

        assertThat(report.rows()).hasSize(3);
        assertThat(report.rows()).allSatisfy(r -> {
            assertThat(r.withMcpTokens()).isPositive();
            assertThat(r.hits()).isPositive();
        });
        assertThat(report.totalWithout()).isPositive();
        assertThat(report.toMarkdown()).contains("WITH-MCP", "total");
    }

    @Test
    void withMcpCostsFewerTokensThanReadingWholeFilesInAggregate() {
        String repo = "self2";
        index.index(repo, "src/main/java");

        Report report = harness.run(repo, TASKS);

        assertThat(report.totalWithMcp()).isLessThan(report.totalWithout());
        assertThat(report.totalSavedPercent()).isGreaterThan(0.0);
    }
}
