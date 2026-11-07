package io.github.vedmider.dylext.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The AI's working memory for a task. Two layers: {@code decisions} (durable, batched, carry the
 * why — never auto-evicted) and {@code facts} (cheap streaming edits — bounded ring buffer).
 * Keyed by {@code id} (task-scoped). {@code repos} is a tag list, not the key.
 */
public record Session(
        String id,
        String task,
        Status status,
        List<String> repos,
        List<SessionEntry> decisions,
        List<SessionEntry> facts,
        Instant updatedAt) {

    public enum Status { ACTIVE, DONE }

    public Session {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(task, "task");
        status = status == null ? Status.ACTIVE : status;
        repos = repos == null ? List.of() : List.copyOf(repos);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        facts = facts == null ? List.of() : List.copyOf(facts);
    }

    public static Session start(String id, String task) {
        return new Session(id, task, Status.ACTIVE, List.of(), List.of(), List.of(), Instant.now());
    }

    /** Cheap index view for list(): identity + counts, never the entry bodies. */
    public SessionHeader header() {
        return new SessionHeader(id, task, status, repos, decisions.size(), facts.size(), updatedAt);
    }

    public record SessionHeader(String id, String task, Status status, List<String> repos,
                                int decisionCount, int factCount, Instant updatedAt) {
    }
}
