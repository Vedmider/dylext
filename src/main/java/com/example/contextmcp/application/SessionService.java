package com.example.contextmcp.application;

import com.example.contextmcp.domain.model.Session;
import com.example.contextmcp.domain.model.SessionEntry;
import com.example.contextmcp.domain.port.in.SessionUseCase;
import com.example.contextmcp.domain.port.out.SessionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Task-reflection sessions. Facts stream into a bounded ring buffer (newest kept); decisions are
 * batched at checkpoints and never auto-evicted (they carry the why). Provider-agnostic — no
 * summarization. Isolated from code-context services.
 */
@Service
public class SessionService implements SessionUseCase {

    private final SessionStore store;
    private final int maxFacts;
    private final int closedTtlDays;
    private final int activeTtlDays;
    private final int maxRows;

    public SessionService(SessionStore store,
                          @Value("${contextmcp.session.max-facts:50}") int maxFacts,
                          @Value("${contextmcp.session.closed-ttl-days:7}") int closedTtlDays,
                          @Value("${contextmcp.session.active-ttl-days:30}") int activeTtlDays,
                          @Value("${contextmcp.session.max-rows:1000}") int maxRows) {
        this.store = store;
        this.maxFacts = Math.max(maxFacts, 1);
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
    public void recordFact(String id, SessionEntry fact) {
        if (fact == null) {
            throw new IllegalArgumentException("fact is required");
        }
        load(id);
        store.appendFact(id, fact, maxFacts);
        enforceMaxRows();
    }

    @Override
    public Session commit(String id, List<SessionEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("commit requires at least one entry");
        }
        for (SessionEntry e : entries) {
            if (e.type() == SessionEntry.Type.FACT) {
                throw new IllegalArgumentException("commit is for decision-layer entries; use recordFact for facts");
            }
        }
        load(id);
        store.appendDecisions(id, entries);
        return load(id);
    }

    @Override
    public Optional<Session> recall(String id) {
        return store.findById(id);
    }

    @Override
    public List<Session.SessionHeader> list(String repoFilter, Session.Status statusFilter,
                                            String textFilter, int limit) {
        return store.listHeaders(repoFilter, statusFilter, textFilter, limit);
    }

    @Override
    public Session close(String id) {
        Session s = load(id);
        Session closed = new Session(s.id(), s.task(), Session.Status.DONE, s.repos(),
                s.decisions(), s.facts(), Instant.now());
        store.save(closed);
        return closed;
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

    private Session load(String id) {
        return store.findById(id)
                .orElseThrow(() -> new NoSuchElementException("unknown session: " + id));
    }
}
