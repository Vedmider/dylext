package io.github.vedmider.dylext.domain.port.in;

import java.util.List;

/** Inbound port: structural blast radius of a symbol — who calls it and which tests cover it. */
public interface ImpactUseCase {

    /** @param limit max callers to return (after ranking); <= 0 means unlimited. */
    Impact impactOf(String symbolId, int limit);

    default Impact impactOf(String symbolId) {
        return impactOf(symbolId, 0);
    }

    record Impact(String symbolId, List<String> callers, List<String> tests, boolean ambiguousOverload,
                  @com.fasterxml.jackson.annotation.JsonInclude(
                          com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                  String capabilityNote,
                  @com.fasterxml.jackson.annotation.JsonInclude(
                          com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                  List<SignalRank> signalRanking,
                  @com.fasterxml.jackson.annotation.JsonInclude(
                          com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT)
                  boolean truncated,
                  @com.fasterxml.jackson.annotation.JsonInclude(
                          com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT)
                  int totalCallers) {
        public Impact(String symbolId, List<String> callers, List<String> tests) {
            this(symbolId, callers, tests, false, null, null, false, 0);
        }

        public Impact(String symbolId, List<String> callers, List<String> tests, boolean ambiguousOverload) {
            this(symbolId, callers, tests, ambiguousOverload, null, null, false, 0);
        }

        public Impact(String symbolId, List<String> callers, List<String> tests, boolean ambiguousOverload,
                      String capabilityNote) {
            this(symbolId, callers, tests, ambiguousOverload, capabilityNote, null, false, 0);
        }

        public Impact(String symbolId, List<String> callers, List<String> tests, boolean ambiguousOverload,
                      String capabilityNote, List<SignalRank> signalRanking) {
            this(symbolId, callers, tests, ambiguousOverload, capabilityNote, signalRanking, false, 0);
        }
    }

    /** A caller id and its runtime-signal weight; present only when signals rank the result. */
    record SignalRank(String id, double weight) {
    }
}
