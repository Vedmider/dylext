package io.github.vedmider.dylext.domain.model;

/** A directed call-graph edge. {@code dstId} is null until resolved from {@code dstName}. */
public record CodeEdge(String repo, String srcId, String dstId, String dstName, String kind) {

    public static CodeEdge call(String repo, String srcId, String dstName) {
        return new CodeEdge(repo, srcId, null, dstName, "calls");
    }
}
