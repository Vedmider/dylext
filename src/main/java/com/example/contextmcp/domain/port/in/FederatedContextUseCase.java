package com.example.contextmcp.domain.port.in;

import com.example.contextmcp.domain.port.out.FederatedRetriever.CodeHit;
import com.example.contextmcp.domain.port.out.FederatedRetriever.DocHit;

import java.util.List;

/** Inbound port federating the sibling retrievers: docs via rag-app, fuzzy code via code-memory. */
public interface FederatedContextUseCase {

    List<DocHit> explain(String query, String roles, int topK);

    List<CodeHit> recall(String query, int topK);
}
