package io.github.vedmider.dylext.adapter.out.store;

import io.github.vedmider.dylext.domain.model.Session;
import io.github.vedmider.dylext.domain.model.SessionEntry;
import io.github.vedmider.dylext.domain.port.out.SessionStore;
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
    void appendFactAccumulatesReposAcrossRepos() {
        store.save(Session.start("t-repos", "refactor A then B"));
        store.appendFact("t-repos", SessionEntry.fact("repoA", "F1.java", "F1.m", "edit"), 50);
        store.appendFact("t-repos", SessionEntry.fact("repoB", "G1.java", "G1.m", "edit"), 50);
        store.appendFact("t-repos", SessionEntry.fact("repoA", "F2.java", "F2.m", "edit"), 50);

        assertThat(store.findById("t-repos").orElseThrow().repos())
                .containsExactly("repoA", "repoB");
    }

    @Test
    void listHeadersFiltersByRepoTouchedByFacts() {
        store.save(Session.start("t-only-a", "task a"));
        store.save(Session.start("t-both", "task both"));
        store.appendFact("t-only-a", SessionEntry.fact("repoA-f", "F.java", "F.m", "e"), 50);
        store.appendFact("t-both", SessionEntry.fact("repoA-f", "F.java", "F.m", "e"), 50);
        store.appendFact("t-both", SessionEntry.fact("repoB-f", "G.java", "G.m", "e"), 50);

        assertThat(store.listHeaders("repoB-f", null, null, 50))
                .extracting(Session.SessionHeader::id).containsExactly("t-both");
        assertThat(store.listHeaders("repoA-f", null, null, 50))
                .extracting(Session.SessionHeader::id).containsExactlyInAnyOrder("t-only-a", "t-both");
    }

    @Test
    void decisionEntriesWithARepoAreTaggedToo() {
        store.save(Session.start("t-dec", "t"));
        store.appendDecisions("t-dec", List.of(new SessionEntry(SessionEntry.Type.DECISION,
                "split module", "cohesion", "repoC", null, null, null)));

        assertThat(store.findById("t-dec").orElseThrow().repos()).containsExactly("repoC");
    }

    @Test
    void repoTagSurvivesFactEviction() {
        store.save(Session.start("t-evict", "t"));
        store.appendFact("t-evict", SessionEntry.fact("repoEarly", "F.java", "F.m", "first"), 2);
        store.appendFact("t-evict", SessionEntry.fact("repoLate", "G.java", "G.m", "second"), 2);
        store.appendFact("t-evict", SessionEntry.fact("repoLate", "G.java", "G.m", "third"), 2);

        Session got = store.findById("t-evict").orElseThrow();
        assertThat(got.facts()).extracting(SessionEntry::repo).containsOnly("repoLate");
        assertThat(got.repos()).containsExactly("repoEarly", "repoLate");
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
