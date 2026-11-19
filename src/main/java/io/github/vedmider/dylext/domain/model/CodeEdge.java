package io.github.vedmider.dylext.domain.model;

/** A directed call-graph edge. {@code dstId} is null until resolved from {@code dstName} (+ arity/recv/types). */
public record CodeEdge(String repo, String srcId, String dstId, String dstName, String kind,
                       Integer dstArity, String dstRecv, String dstParamTypes) {

    public static CodeEdge call(String repo, String srcId, String dstName) {
        return new CodeEdge(repo, srcId, null, dstName, "calls", null, null, null);
    }

    /** Call edge carrying the call-site arg count and (optional) static receiver type for precise resolution. */
    public static CodeEdge call(String repo, String srcId, String dstName, Integer dstArity, String dstRecv) {
        return new CodeEdge(repo, srcId, null, dstName, "calls", dstArity, dstRecv, null);
    }

    /** Call edge also carrying inferred call-site param types (CSV of simple type names) for overload resolution. */
    public static CodeEdge call(String repo, String srcId, String dstName, Integer dstArity, String dstRecv,
                                String dstParamTypes) {
        return new CodeEdge(repo, srcId, null, dstName, "calls", dstArity, dstRecv, dstParamTypes);
    }
}
