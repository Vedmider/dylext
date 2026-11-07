package io.github.vedmider.dylext.domain.port.out;

import java.util.List;

/**
 * Delegates to the sibling retrievers at their strongest: {@code explain} to Sestacon (conceptual /
 * doc retrieval over prose) and {@code recall} to code-memory (hybrid + graph-ranked code recall).
 * Both degrade to an empty list when the backing service is unreachable.
 */
public interface FederatedRetriever {

    /** Conceptual/doc answers from Sestacon. */
    List<DocHit> explain(String query, String roles, int topK);

    /** Fuzzy, graph-ranked code recall from code-memory. */
    List<CodeHit> recall(String query, int topK);

    record DocHit(String source, double score, String text) {
    }

    record CodeHit(String id, String file, String symbol, String kind, String signature,
                   double score) {
    }
}
