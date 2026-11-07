package io.github.vedmider.dylext.domain.port.in;

import io.github.vedmider.dylext.domain.port.out.FederatedRetriever.CodeHit;
import io.github.vedmider.dylext.domain.port.out.FederatedRetriever.DocHit;

import java.util.List;

/** Inbound port federating the sibling retrievers: docs via Sestacon, fuzzy code via code-memory. */
public interface FederatedContextUseCase {

    List<DocHit> explain(String query, String roles, int topK);

    List<CodeHit> recall(String query, int topK);
}
