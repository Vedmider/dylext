package io.github.vedmider.dylext.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One unified session entry. {@code type} selects the layer: DECISION/FINDING/NEXT are the durable,
 * batched "why" layer (rationale in {@code why}); FACT is the cheap, streaming "what" layer
 * (edits — repo/file/symbol set, {@code why} absent).
 */
public record SessionEntry(
        Type type,
        String what,
        String why,
        String repo,
        String file,
        String symbol,
        Instant at,
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String branch) {

    public SessionEntry(Type type, String what, String why, String repo, String file, String symbol, Instant at) {
        this(type, what, why, repo, file, symbol, at, null);
    }

    public enum Type { FACT, DECISION, FINDING, NEXT }

    public SessionEntry {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(what, "what");
        at = at == null ? Instant.now() : at;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isDecisionLayer() {
        return type != Type.FACT;
    }

    public static SessionEntry fact(String repo, String file, String symbol, String what) {
        return fact(repo, file, symbol, what, null);
    }

    public static SessionEntry fact(String repo, String file, String symbol, String what, String branch) {
        return new SessionEntry(Type.FACT, what, null, repo, file, symbol, Instant.now(), branch);
    }

    public static SessionEntry decision(String what, String why) {
        return new SessionEntry(Type.DECISION, what, why, null, null, null, Instant.now());
    }
}
