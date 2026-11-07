package com.example.contextmcp.domain.port.out;

import com.example.contextmcp.domain.model.Session;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persists AI task-reflection sessions, isolated from code-context storage. Keyed by session id. */
public interface SessionStore {

    void save(Session session);

    Optional<Session> findById(String id);

    /** Sessions filtered by optional repo (tag), status, and free-text over task — newest first. */
    List<Session> list(String repoFilter, Session.Status statusFilter, String textFilter, int limit);

    void deleteById(String id);

    long count();

    /** Evict sessions matching the lifecycle rule; returns rows removed. */
    int purge(Instant closedBefore, Instant idleBefore);

    /** Oldest session ids (by updatedAt asc) — used for max-rows back-pressure. */
    List<String> oldestIds(int n);
}
