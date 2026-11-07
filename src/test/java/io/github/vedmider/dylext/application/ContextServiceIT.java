package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.model.Digest;
import io.github.vedmider.dylext.domain.port.in.IndexUseCase;
import io.github.vedmider.dylext.domain.port.in.RetrieveUseCase;
import io.github.vedmider.dylext.domain.port.in.RetrieveUseCase.ExpandedSpan;
import io.github.vedmider.dylext.domain.port.in.RetrieveUseCase.SymbolDigest;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class ContextServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private IndexUseCase index;

    @Autowired
    private RetrieveUseCase retrieve;

    private static final String SRC = """
            package demo;
            public class Orders {
                /** Places a new order for the customer. */
                public String placeOrder(String customer) {
                    return validate(customer);
                }
                private String validate(String customer) {
                    return customer.trim();
                }
            }
            """;

    private String indexFixture(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("demo"));
        Files.writeString(dir.resolve("demo/Orders.java"), SRC);
        String repo = "ctx";
        index.index(repo, dir.toString());
        return repo;
    }

    @Test
    void outlineReturnsSkeletonsForEverySymbolInFile(@TempDir Path dir) throws IOException {
        String repo = indexFixture(dir);
        List<SymbolDigest> outline = retrieve.outline(repo, "demo/Orders.java");
        assertThat(outline).extracting(SymbolDigest::name)
                .contains("Orders.java", "Orders", "placeOrder", "validate");
    }

    @Test
    void searchFindsSymbolByNameRankedFirst(@TempDir Path dir) throws IOException {
        String repo = indexFixture(dir);
        List<SymbolDigest> hits = retrieve.searchSymbols(repo, "placeOrder", 5);
        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).name()).isEqualTo("placeOrder");
    }

    @Test
    void searchMatchesDocText(@TempDir Path dir) throws IOException {
        String repo = indexFixture(dir);
        List<SymbolDigest> hits = retrieve.searchSymbols(repo, "customer", 10);
        assertThat(hits).extracting(SymbolDigest::name).contains("placeOrder");
    }

    @Test
    void getDigestReturnsSkeletonWithoutSummaryWhenNoneBuilt(@TempDir Path dir) throws IOException {
        String repo = indexFixture(dir);
        String id = retrieve.searchSymbols(repo, "placeOrder", 1).get(0).id();
        Digest d = retrieve.getDigest(id);
        assertThat(d.skeleton().text()).contains("placeOrder");
        assertThat(d.summary()).isEmpty();
    }

    @Test
    void expandReturnsOnlyTheSymbolSpanNotTheWholeFile(@TempDir Path dir) throws IOException {
        String repo = indexFixture(dir);
        String id = retrieve.searchSymbols(repo, "validate", 1).get(0).id();
        ExpandedSpan span = retrieve.expand(id);

        assertThat(span.source())
                .contains("private String validate(String customer)")
                .contains("customer.trim()")
                .doesNotContain("placeOrder")
                .doesNotContain("package demo");
        assertThat(span.startLine()).isGreaterThan(1);
    }
}
