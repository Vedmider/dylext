package com.example.contextmcp.adapter.out.store;

import com.example.contextmcp.domain.model.Session;
import com.example.contextmcp.domain.model.SessionEntry;
import com.example.contextmcp.domain.port.out.SessionStore;
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
class PgSessionStoreIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private SessionStore store;

    @Test
    void saveThenFindByIdRoundTripsBothLayers() {
        Session s = new Session("t-1", "improve retrieval", Session.Status.ACTIVE,
                List.of("rag-app"),
                List.of(SessionEntry.decision("use reranker", "precision")),
                List.of(SessionEntry.fact("rag-app", "R.java", "R.retrieve", "wide+rerank")),
                java.time.Instant.now());
        store.save(s);

        Session got = store.findById("t-1").orElseThrow();
        assertThat(got.decisions()).singleElement()
                .satisfies(d -> {
                    assertThat(d.type()).isEqualTo(SessionEntry.Type.DECISION);
                    assertThat(d.why()).isEqualTo("precision");
                });
        assertThat(got.facts()).singleElement()
                .satisfies(f -> assertThat(f.symbol()).isEqualTo("R.retrieve"));
    }

    @Test
    void appendFactTrimsToMaxKeepingNewest() {
        store.save(Session.start("t-facts", "t"));
        for (int i = 1; i <= 5; i++) {
            store.appendFact("t-facts", SessionEntry.fact("r", "F.java", "F.m" + i, "edit " + i), 3);
        }
        Session got = store.findById("t-facts").orElseThrow();
        assertThat(got.facts()).hasSize(3);
        assertThat(got.facts()).extracting(SessionEntry::what)
                .containsExactly("edit 3", "edit 4", "edit 5");
    }

    @Test
    void appendDecisionsIsBatchedAndNeverTrimmed() {
        store.save(Session.start("t-dec", "t"));
        store.appendDecisions("t-dec", List.of(
                SessionEntry.decision("d1", "w1"),
                SessionEntry.decision("d2", "w2")));
        store.appendDecisions("t-dec", List.of(SessionEntry.decision("d3", "w3")));
        assertThat(store.findById("t-dec").orElseThrow().decisions())
                .extracting(SessionEntry::what).containsExactly("d1", "d2", "d3");
    }

    @Test
    void listHeadersReturnsCountsNotBodies() {
        store.save(new Session("h-1", "alpha", Session.Status.ACTIVE, List.of("rag-app"),
                List.of(SessionEntry.decision("d", "w")),
                List.of(SessionEntry.fact("r", "f", "s", "e")), java.time.Instant.now()));

        Session.SessionHeader h = store.listHeaders("rag-app", null, null, 50).stream()
                .filter(x -> x.id().equals("h-1")).findFirst().orElseThrow();
        assertThat(h.task()).isEqualTo("alpha");
        assertThat(h.decisionCount()).isEqualTo(1);
        assertThat(h.factCount()).isEqualTo(1);
    }

    @Test
    void listHeadersFiltersByStatusAndText() {
        store.save(new Session("h-a", "beta task", Session.Status.DONE, List.of(),
                List.of(), List.of(), java.time.Instant.now()));
        assertThat(store.listHeaders(null, Session.Status.DONE, null, 50))
                .extracting(Session.SessionHeader::id).contains("h-a");
        assertThat(store.listHeaders(null, null, "beta", 50))
                .extracting(Session.SessionHeader::id).contains("h-a");
    }

    @Test
    void deleteCountOldest() {
        store.save(Session.start("d-1", "x"));
        assertThat(store.findById("d-1")).isPresent();
        assertThat(store.oldestIds(1)).hasSize(1);
        store.deleteById("d-1");
        assertThat(store.findById("d-1")).isEmpty();
        assertThat(store.count()).isGreaterThanOrEqualTo(0);
    }
}
