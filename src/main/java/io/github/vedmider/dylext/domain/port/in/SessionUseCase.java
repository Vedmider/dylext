package io.github.vedmider.dylext.domain.port.in;

import io.github.vedmider.dylext.domain.model.Session;
import io.github.vedmider.dylext.domain.model.SessionEntry;

import java.util.List;
import java.util.Optional;

/**
 * Inbound port: the AI's task-reflection memory. Two write verbs — {@code recordFact} streams cheap
 * edits (bounded ring buffer), {@code commit} batches durable decision-layer entries (never evicted).
 * Reads are tiered: {@code list} returns headers only, {@code recall} returns the full session.
 */
public interface SessionUseCase {

    Session start(String task);

    /** Stream one cheap factual edit. Bounded: oldest facts are evicted past the cap. */
    void recordFact(String id, SessionEntry fact);

    /** Batch-append durable decision-layer entries (decision/finding/next). Never auto-evicted. */
    Session commit(String id, List<SessionEntry> entries);

    Optional<Session> recall(String id);

    /** Cheap index: identity + counts only (no entry bodies), newest first. */
    List<Session.SessionHeader> list(String repoFilter, Session.Status statusFilter,
                                     String textFilter, int limit);

    Session close(String id);

    /** Evict sessions per lifecycle policy (closed-grace + active-idle TTL). @return rows removed. */
    int purge();
}
