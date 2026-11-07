package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.port.in.FederatedContextUseCase;
import io.github.vedmider.dylext.domain.port.in.LedgerUseCase;
import io.github.vedmider.dylext.domain.port.out.FederatedRetriever;
import io.github.vedmider.dylext.domain.port.out.FederatedRetriever.CodeHit;
import io.github.vedmider.dylext.domain.port.out.FederatedRetriever.DocHit;
import org.springframework.stereotype.Service;

import java.util.List;

/** Federates explain (Sestacon) and recall (code-memory), recording served tokens to the ledger. */
@Service
public class FederatedContextService implements FederatedContextUseCase {

    private final FederatedRetriever retriever;
    private final LedgerUseCase ledger;

    public FederatedContextService(FederatedRetriever retriever, LedgerUseCase ledger) {
        this.retriever = retriever;
        this.ledger = ledger;
    }

    @Override
    public List<DocHit> explain(String query, String roles, int topK) {
        List<DocHit> hits = retriever.explain(query, roles, topK);
        String served = hits.stream().map(DocHit::text).reduce("", (a, b) -> a + "\n" + b);
        ledger.recordCost(null, "explain", tokensish(served));
        return hits;
    }

    @Override
    public List<CodeHit> recall(String query, int topK) {
        List<CodeHit> hits = retriever.recall(query, topK);
        String served = hits.stream().map(CodeHit::signature).reduce("", (a, b) -> a + "\n" + b);
        ledger.recordCost(null, "recall", tokensish(served));
        return hits;
    }

    private static int tokensish(String s) {
        return s.isBlank() ? 0 : s.trim().split("\\s+").length;
    }
}
