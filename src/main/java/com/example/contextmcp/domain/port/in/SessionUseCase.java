package com.example.contextmcp.domain.port.in;

import com.example.contextmcp.domain.model.Session;

import java.util.List;
import java.util.Optional;

/** Inbound port: the AI's task-reflection memory — start/recall/append/list/close, isolated from code. */
public interface SessionUseCase {

    Session start(String task);

    Optional<Session> recall(String id);

    List<Session> list(String repoFilter, Session.Status statusFilter, String textFilter, int limit);

    Session close(String id);

    /** Evict sessions per lifecycle policy (closed-grace + active-idle TTL). @return rows removed. */
    int purge();

    /** Append one item to a session's working lists. Unknown/empty fields are ignored. */
    Session append(String id, Append item);

    /** What to add. Exactly one of the fields is expected per call; others may be null. */
    record Append(Session.Entry decision, Session.Entry finding, Session.Entry openThread,
                  Session.Change change, Session.Ref ref, String repo) {
    }
}
