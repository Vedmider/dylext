package io.github.vedmider.dylext.domain.port.out;

import io.github.vedmider.dylext.domain.model.Session;
import io.github.vedmider.dylext.domain.model.SessionEntry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persists AI task-reflection sessions, isolated from code-context storage. Keyed by session id. */
public interface SessionStore {

    void save(Session session);

    /** Append one streaming fact, trimming the facts list to the newest {@code maxFacts}. */
    void appendFact(String id, SessionEntry fact, int maxFacts);

    /** Append a batch of durable decision-layer entries (never trimmed). */
    void appendDecisions(String id, List<SessionEntry> entries);

    Optional<Session> findById(String id);

    /** Cheap index: identity + counts only (no entry bodies), newest first. */
    List<Session.SessionHeader> listHeaders(String repoFilter, Session.Status statusFilter,
                                            String textFilter, int limit);

    void deleteById(String id);

    long count();

    int purge(Instant closedBefore, Instant idleBefore);

    List<String> oldestIds(int n);
}
