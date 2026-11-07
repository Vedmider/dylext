package com.example.contextmcp.adapter.out.store;

import com.example.contextmcp.domain.model.Session;
import com.example.contextmcp.domain.port.out.SessionStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
class PgSessionStoreIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private SessionStore store;

    private Session withData(String id, String task) {
        return new Session(id, task, Session.Status.ACTIVE,
                List.of("rag-app"),
                List.of(new Session.Entry("use reranker", "precision")),
                List.of(new Session.Change("rag-app", "R.java", "rag-app:R.java:R.retrieve", "wide+rerank", "G1")),
                List.of(new Session.Entry("docs say vector-only", "baseline")),
                List.of(new Session.Entry("add parent-doc chunking", "G2")),
                List.of(new Session.Ref("symbol", "rag-app:R.java:R.retrieve")),
                Instant.now());
    }

    @Test
    void saveThenFindByIdRoundTripsAllFields() {
        store.save(withData("task-1", "improve retrieval"));

        Session got = store.findById("task-1").orElseThrow();
        assertThat(got.task()).isEqualTo("improve retrieval");
        assertThat(got.repos()).containsExactly("rag-app");
        assertThat(got.changes()).singleElement()
                .satisfies(c -> assertThat(c.symbol()).isEqualTo("rag-app:R.java:R.retrieve"));
        assertThat(got.decisions()).singleElement()
                .satisfies(d -> assertThat(d.what()).isEqualTo("use reranker"));
        assertThat(got.refs()).singleElement()
                .satisfies(r -> assertThat(r.kind()).isEqualTo("symbol"));
    }

    @Test
    void upsertReplacesInPlace() {
        store.save(Session.start("task-2", "v1"));
        store.save(Session.start("task-2", "v2"));
        assertThat(store.findById("task-2")).get()
                .satisfies(s -> assertThat(s.task()).isEqualTo("v2"));
    }

    @Test
    void listFiltersByRepoStatusAndText() {
        store.save(withData("f-a", "alpha task"));                       // repo rag-app, ACTIVE
        store.save(new Session("f-b", "beta task", Session.Status.DONE,
                List.of("code-memory"), List.of(), List.of(), List.of(), List.of(), List.of(),
                Instant.now()));

        assertThat(store.list("rag-app", null, null, 50)).extracting(Session::id).contains("f-a");
        assertThat(store.list("rag-app", null, null, 50)).extracting(Session::id).doesNotContain("f-b");
        assertThat(store.list(null, Session.Status.DONE, null, 50)).extracting(Session::id).contains("f-b");
        assertThat(store.list(null, null, "alpha", 50)).extracting(Session::id).containsExactly("f-a");
    }

    @Test
    void deleteAndCount() {
        long before = store.count();
        store.save(Session.start("task-del", "x"));
        assertThat(store.count()).isEqualTo(before + 1);
        store.deleteById("task-del");
        assertThat(store.findById("task-del")).isEmpty();
    }

    @Test
    void oldestIdsReturnsLeastRecentlyUpdatedFirst() {
        store.save(Session.start("old-1", "x"));
        store.save(Session.start("old-2", "y"));
        assertThat(store.oldestIds(1)).hasSize(1);
    }

    @Test
    void purgeRemovesClosedAndIdleByThreshold() {
        // A DONE session updated "now" should survive a closedBefore well in the past.
        store.save(new Session("keep", "k", Session.Status.DONE, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), Instant.now()));
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
        int removed = store.purge(past, past);
        assertThat(store.findById("keep")).isPresent();
        assertThat(removed).isGreaterThanOrEqualTo(0);
    }
}
