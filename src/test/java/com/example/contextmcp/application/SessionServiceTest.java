package com.example.contextmcp.application;

import com.example.contextmcp.domain.model.Session;
import com.example.contextmcp.domain.model.SessionEntry;
import com.example.contextmcp.domain.port.out.SessionStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionServiceTest {

    /** In-memory fake so the service logic is tested without a database. */
    private static final class FakeStore implements SessionStore {
        final Map<String, Session> db = new ConcurrentHashMap<>();

        public void save(Session s) {
            db.put(s.id(), s);
        }

        public void appendFact(String id, SessionEntry fact, int maxFacts) {
            Session s = db.get(id);
            List<SessionEntry> facts = new ArrayList<>(s.facts());
            facts.add(fact);
            int n = facts.size();
            int keep = Math.max(maxFacts, 1);
            if (n > keep) {
                facts = new ArrayList<>(facts.subList(n - keep, n));
            }
            save(new Session(s.id(), s.task(), s.status(), s.repos(), s.decisions(), facts, s.updatedAt()));
        }

        public void appendDecisions(String id, List<SessionEntry> entries) {
            Session s = db.get(id);
            List<SessionEntry> decisions = new ArrayList<>(s.decisions());
            decisions.addAll(entries);
            save(new Session(s.id(), s.task(), s.status(), s.repos(), decisions, s.facts(), s.updatedAt()));
        }

        public Optional<Session> findById(String id) {
            return Optional.ofNullable(db.get(id));
        }

        public List<Session.SessionHeader> listHeaders(String repo, Session.Status st, String text, int limit) {
            return db.values().stream()
                    .filter(s -> repo == null || s.repos().contains(repo))
                    .filter(s -> st == null || s.status() == st)
                    .filter(s -> text == null || s.task().toLowerCase().contains(text.toLowerCase()))
                    .sorted(Comparator.comparing(Session::updatedAt).reversed())
                    .limit(limit <= 0 ? 50 : limit)
                    .map(Session::header).toList();
        }

        public void deleteById(String id) { db.remove(id); }
        public long count() { return db.size(); }

        public int purge(Instant closedBefore, Instant idleBefore) {
            int before = db.size();
            db.values().removeIf(s ->
                    (s.status() == Session.Status.DONE && s.updatedAt().isBefore(closedBefore))
                 || (s.status() == Session.Status.ACTIVE && s.updatedAt().isBefore(idleBefore)));
            return before - db.size();
        }

        public List<String> oldestIds(int n) {
            return db.values().stream()
                    .sorted(Comparator.comparing(Session::updatedAt))
                    .limit(Math.max(n, 0)).map(Session::id).toList();
        }
    }

    private final FakeStore store = new FakeStore();
    // small bounds for the test: maxFacts=3, closedTtl=7, activeTtl=30, maxRows=2
    private final SessionService svc = new SessionService(store, 3, 7, 30, 2);

    @Test
    void startCreatesActiveSessionWithGeneratedId() {
        Session s = svc.start("improve retrieval");
        assertThat(s.id()).startsWith("s-");
        assertThat(s.status()).isEqualTo(Session.Status.ACTIVE);
        assertThat(store.findById(s.id())).isPresent();
    }

    @Test
    void startRejectsBlankTask() {
        assertThatThrownBy(() -> svc.start("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recordFactStreamsIntoFactsLayer() {
        String id = svc.start("t").id();
        svc.recordFact(id, SessionEntry.fact("rag-app", "F.java", "F.m", "edit"));
        assertThat(svc.recall(id).orElseThrow().facts())
                .singleElement().satisfies(f -> assertThat(f.symbol()).isEqualTo("F.m"));
    }

    @Test
    void factsAreBoundedToMaxFactsKeepingNewest() {
        String id = svc.start("t").id();
        for (int i = 1; i <= 5; i++) {
            svc.recordFact(id, SessionEntry.fact("r", "F.java", "F.m" + i, "edit " + i));
        }
        assertThat(svc.recall(id).orElseThrow().facts())
                .extracting(SessionEntry::what).containsExactly("edit 3", "edit 4", "edit 5");
    }

    @Test
    void commitBatchesDecisionsAndNeverTrims() {
        String id = svc.start("t").id();
        for (int i = 1; i <= 5; i++) {
            svc.commit(id, List.of(SessionEntry.decision("d" + i, "w" + i)));
        }
        assertThat(svc.recall(id).orElseThrow().decisions())
                .extracting(SessionEntry::what).containsExactly("d1", "d2", "d3", "d4", "d5");
    }

    @Test
    void commitRejectsEmptyBatch() {
        String id = svc.start("t").id();
        assertThatThrownBy(() -> svc.commit(id, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commitRejectsFactLayerEntries() {
        String id = svc.start("t").id();
        assertThatThrownBy(() -> svc.commit(id, List.of(SessionEntry.fact("r", "f", "s", "e"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listReturnsHeadersWithCounts() {
        String id = svc.start("alpha").id();
        svc.commit(id, List.of(SessionEntry.decision("d", "w")));
        svc.recordFact(id, SessionEntry.fact("rag-app", "f", "s", "e"));
        Session.SessionHeader h = svc.list(null, null, "alpha", 50).stream()
                .filter(x -> x.id().equals(id)).findFirst().orElseThrow();
        assertThat(h.decisionCount()).isEqualTo(1);
        assertThat(h.factCount()).isEqualTo(1);
    }

    @Test
    void recordFactEnforcesMaxRowsByEvictingOldest() {
        svc.start("a");
        svc.start("b");
        String c = svc.start("c").id();
        svc.recordFact(c, SessionEntry.fact("r", "f", "s", "e"));
        assertThat(store.count()).isLessThanOrEqualTo(2);
        assertThat(store.findById(c)).isPresent();
    }

    @Test
    void purgeRemovesExpiredByTtl() {
        store.save(new Session("old-done", "x", Session.Status.DONE, List.of(), List.of(), List.of(),
                Instant.now().minusSeconds(60L * 60 * 24 * 10)));
        int removed = svc.purge();
        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(store.findById("old-done")).isEmpty();
    }

    @Test
    void closeMarksDone() {
        String id = svc.start("t").id();
        assertThat(svc.close(id).status()).isEqualTo(Session.Status.DONE);
    }

    @Test
    void recordFactToUnknownSessionThrows() {
        assertThatThrownBy(() -> svc.recordFact("nope", SessionEntry.fact("r", "f", "s", "e")))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }
}
