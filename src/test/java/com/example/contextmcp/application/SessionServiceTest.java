package com.example.contextmcp.application;

import com.example.contextmcp.domain.model.Session;
import com.example.contextmcp.domain.port.in.SessionUseCase.Append;
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
        public void save(Session s) { db.put(s.id(), s); }
        public Optional<Session> findById(String id) { return Optional.ofNullable(db.get(id)); }
        public List<Session> list(String repo, Session.Status st, String text, int limit) {
            return db.values().stream()
                    .filter(s -> repo == null || s.repos().contains(repo))
                    .filter(s -> st == null || s.status() == st)
                    .filter(s -> text == null || s.task().toLowerCase().contains(text.toLowerCase()))
                    .sorted(Comparator.comparing(Session::updatedAt).reversed())
                    .limit(limit <= 0 ? 50 : limit).toList();
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
    // small bounds for the test: maxEntries=3, closedTtl=7, activeTtl=30, maxRows=2
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
    void appendRoutesEachKindToItsOwnList() {
        String id = svc.start("t").id();
        svc.append(id, new Append(new Session.Entry("d", "why"), null, null, null, null, null));
        svc.append(id, new Append(null, new Session.Entry("f", "why"), null, null, null, null));
        svc.append(id, new Append(null, null, new Session.Entry("next", "why"), null, null, null));
        svc.append(id, new Append(null, null, null,
                new Session.Change("r", "F.java", "F.m", "did", "why"), null, null));
        svc.append(id, new Append(null, null, null, null, new Session.Ref("symbol", "r:F.java:F.m"), null));
        svc.append(id, new Append(null, null, null, null, null, "rag-app"));

        Session s = svc.recall(id).orElseThrow();
        assertThat(s.decisions()).extracting(Session.Entry::what).containsExactly("d");
        assertThat(s.findings()).hasSize(1);
        assertThat(s.openThreads()).hasSize(1);
        assertThat(s.changes()).singleElement().satisfies(c -> assertThat(c.symbol()).isEqualTo("F.m"));
        assertThat(s.refs()).hasSize(1);
        assertThat(s.repos()).containsExactly("rag-app");
    }

    @Test
    void repoAppendIsDeduplicated() {
        String id = svc.start("t").id();
        svc.append(id, new Append(null, null, null, null, null, "rag-app"));
        svc.append(id, new Append(null, null, null, null, null, "rag-app"));
        assertThat(svc.recall(id).orElseThrow().repos()).containsExactly("rag-app");
    }

    @Test
    void listsAreBoundedToMaxEntriesKeepingNewest() {
        String id = svc.start("t").id();
        for (int i = 1; i <= 5; i++) {
            svc.append(id, new Append(new Session.Entry("d" + i, "w"), null, null, null, null, null));
        }
        // maxEntries=3 → only the last three decisions survive.
        assertThat(svc.recall(id).orElseThrow().decisions())
                .extracting(Session.Entry::what).containsExactly("d3", "d4", "d5");
    }

    @Test
    void appendEnforcesMaxRowsByEvictingOldest() {
        // maxRows=2. Start 3 sessions; appending triggers back-pressure down to 2.
        svc.start("a");
        svc.start("b");
        String c = svc.start("c").id();
        svc.append(c, new Append(new Session.Entry("d", "w"), null, null, null, null, null));
        assertThat(store.count()).isLessThanOrEqualTo(2);
        assertThat(store.findById(c)).isPresent();   // the one just touched survives
    }

    @Test
    void purgeRemovesExpiredByTtl() {
        // A DONE session with an old updatedAt is evicted by the closed TTL.
        store.save(new Session("old-done", "x", Session.Status.DONE, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), Instant.now().minusSeconds(60L * 60 * 24 * 10)));
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
    void appendToUnknownSessionThrows() {
        assertThatThrownBy(() -> svc.append("nope", new Append(null, null, null, null, null, "r")))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }
}
