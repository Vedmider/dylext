package com.example.contextmcp.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The AI's working memory for a task. Keyed by {@code id} (task-scoped, not repo-scoped); a task may
 * touch many repos or none. Lists are append-only working notes that callers keep bounded.
 */
public record Session(
        String id,
        String task,
        Status status,
        List<String> repos,
        List<Entry> decisions,
        List<Change> changes,
        List<Entry> findings,
        List<Entry> openThreads,
        List<Ref> refs,
        Instant updatedAt) {

    public enum Status { ACTIVE, DONE }

    public Session {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(task, "task");
        status = status == null ? Status.ACTIVE : status;
        repos = repos == null ? List.of() : List.copyOf(repos);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        changes = changes == null ? List.of() : List.copyOf(changes);
        findings = findings == null ? List.of() : List.copyOf(findings);
        openThreads = openThreads == null ? List.of() : List.copyOf(openThreads);
        refs = refs == null ? List.of() : List.copyOf(refs);
    }

    public static Session start(String id, String task) {
        return new Session(id, task, Status.ACTIVE, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), Instant.now());
    }

    /** A free-text working note (decision / finding / next step). */
    public record Entry(String what, String why) {
    }

    /** A code change note; repo/file/symbol optional so non-code work still fits. */
    public record Change(String repo, String file, String symbol, String what, String why) {
    }

    /** A pointer into another context source (doc id, symbol id, ticket, URL). */
    public record Ref(String kind, String value) {
    }
}
