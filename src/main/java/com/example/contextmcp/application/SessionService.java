package com.example.contextmcp.application;

import com.example.contextmcp.domain.model.Session;
import com.example.contextmcp.domain.port.in.SessionUseCase;
import com.example.contextmcp.domain.port.out.SessionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Task-reflection sessions. Appends to bounded working lists (newest kept) so a record stays small
 * and reloadable; provider-agnostic (no summarization). Isolated from code-context services.
 */
@Service
public class SessionService implements SessionUseCase {

    private final SessionStore store;
    private final int maxEntries;
    private final int closedTtlDays;
    private final int activeTtlDays;
    private final int maxRows;

    public SessionService(SessionStore store,
                          @Value("${contextmcp.session.max-entries:50}") int maxEntries,
                          @Value("${contextmcp.session.closed-ttl-days:7}") int closedTtlDays,
                          @Value("${contextmcp.session.active-ttl-days:30}") int activeTtlDays,
                          @Value("${contextmcp.session.max-rows:1000}") int maxRows) {
        this.store = store;
        this.maxEntries = Math.max(maxEntries, 1);
        this.closedTtlDays = Math.max(closedTtlDays, 1);
        this.activeTtlDays = Math.max(activeTtlDays, 1);
        this.maxRows = Math.max(maxRows, 1);
    }

    @Override
    public Session start(String task) {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task is required");
        }
        Session s = Session.start("s-" + UUID.randomUUID(), task);
        store.save(s);
        return s;
    }

    @Override
    public Optional<Session> recall(String id) {
        return store.findById(id);
    }

    @Override
    public List<Session> list(String repoFilter, Session.Status statusFilter, String textFilter,
                              int limit) {
        return store.list(repoFilter, statusFilter, textFilter, limit);
    }

    @Override
    public Session close(String id) {
        Session s = load(id);
        Session closed = new Session(s.id(), s.task(), Session.Status.DONE, s.repos(), s.decisions(),
                s.changes(), s.findings(), s.openThreads(), s.refs(), Instant.now());
        store.save(closed);
        return closed;
    }

    @Override
    public Session append(String id, Append item) {
        Session s = load(id);
        List<String> repos = new ArrayList<>(s.repos());
        List<Session.Entry> decisions = new ArrayList<>(s.decisions());
        List<Session.Change> changes = new ArrayList<>(s.changes());
        List<Session.Entry> findings = new ArrayList<>(s.findings());
        List<Session.Entry> threads = new ArrayList<>(s.openThreads());
        List<Session.Ref> refs = new ArrayList<>(s.refs());

        if (item != null) {
            if (item.decision() != null) {
                decisions.add(item.decision());
            }
            if (item.finding() != null) {
                findings.add(item.finding());
            }
            if (item.openThread() != null) {
                threads.add(item.openThread());
            }
            if (item.change() != null) {
                changes.add(item.change());
            }
            if (item.ref() != null) {
                refs.add(item.ref());
            }
            if (item.repo() != null && !item.repo().isBlank() && !repos.contains(item.repo())) {
                repos.add(item.repo());
            }
        }

        Session updated = new Session(s.id(), s.task(), s.status(), repos,
                bound(decisions), bound(changes), bound(findings), bound(threads), bound(refs),
                Instant.now());
        store.save(updated);
        enforceMaxRows();
        return updated;
    }

    @Override
    public int purge() {
        Instant now = Instant.now();
        return store.purge(now.minus(closedTtlDays, ChronoUnit.DAYS),
                now.minus(activeTtlDays, ChronoUnit.DAYS));
    }

    /** Hard cap: if the store exceeds max-rows, evict the oldest (back-pressure, never runs away). */
    private void enforceMaxRows() {
        long over = store.count() - maxRows;
        if (over > 0) {
            store.oldestIds((int) over).forEach(store::deleteById);
        }
    }

    /** Keep only the most recent {@code maxEntries} items (bounded working memory, no summarization). */
    private <T> List<T> bound(List<T> items) {
        int n = items.size();
        return n <= maxEntries ? items : new ArrayList<>(items.subList(n - maxEntries, n));
    }

    private Session load(String id) {
        return store.findById(id)
                .orElseThrow(() -> new NoSuchElementException("unknown session: " + id));
    }
}
