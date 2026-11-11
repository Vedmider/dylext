package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.port.in.FederatedContextUseCase;
import io.github.vedmider.dylext.domain.port.in.RetrieveUseCase;
import io.github.vedmider.dylext.domain.port.in.RetrieveUseCase.SymbolDigest;
import io.github.vedmider.dylext.domain.port.in.UnifiedAnswerUseCase;
import io.github.vedmider.dylext.domain.port.out.FederatedRetriever.DocHit;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Unified router. Classifies the query (code vs doc intent), fans out to the symbol index and/or
 * Sestacon, and merges the evidence. Both sides are independent: a failing/empty source just
 * contributes nothing, so the result is best-effort rather than all-or-nothing.
 */
@Service
public class UnifiedAnswerService implements UnifiedAnswerUseCase {

    private static final Pattern CODE_TOKEN = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*#[A-Za-z_][A-Za-z0-9_]*"   // Type#member
                    + "|[a-z][A-Za-z0-9]*[A-Z][A-Za-z0-9]*"   // camelCase / CamelCase hump
                    + "|\\w+\\(\\)");                         // call()
    private static final Pattern WHERE_IS = Pattern.compile(
            "\\b(where is|where'?s|find the|definition of|callers? of|implementation of)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DOC_INTENT = Pattern.compile(
            "\\b(how|why|what|which|when|explain|convention|policy|overview|guide|should)\\b",
            Pattern.CASE_INSENSITIVE);

    private final RetrieveUseCase retrieve;
    private final FederatedContextUseCase federated;

    public UnifiedAnswerService(RetrieveUseCase retrieve, FederatedContextUseCase federated) {
        this.retrieve = retrieve;
        this.federated = federated;
    }

    @Override
    public Result answer(String query, String repo, String roles, int topK) {
        Intent intent = classify(query);
        int k = Math.max(topK, 1);

        List<CodeCitation> code = List.of();
        boolean codeAvailable = false;
        if (intent != Intent.DOCS && repo != null && !repo.isBlank()) {
            try {
                code = retrieve.searchSymbols(repo, query, k).stream().map(UnifiedAnswerService::toCode).toList();
                codeAvailable = true;
            } catch (RuntimeException codeDown) {
                codeAvailable = false;
            }
        }

        List<DocCitation> docs = List.of();
        boolean docsAvailable = false;
        if (intent != Intent.CODE) {
            List<DocHit> hits = federated.explain(query, roles, k);
            docsAvailable = !hits.isEmpty();
            docs = hits.stream().map(UnifiedAnswerService::toDoc).toList();
        }

        return new Result(query, intent, code, docs, codeAvailable, docsAvailable);
    }

    /** Rule-based intent: a code token / "where is" => CODE; NL question words => DOCS; both => BOTH. */
    public static Intent classify(String query) {
        if (query == null || query.isBlank()) {
            return Intent.DOCS;
        }
        boolean codeish = CODE_TOKEN.matcher(query).find() || WHERE_IS.matcher(query).find();
        boolean docish = DOC_INTENT.matcher(query).find();
        if (codeish && docish) {
            return Intent.BOTH;
        }
        if (codeish) {
            return Intent.CODE;
        }
        return Intent.DOCS;
    }

    private static CodeCitation toCode(SymbolDigest d) {
        String file = d.id() != null && d.id().split(":", 3).length >= 2 ? d.id().split(":", 3)[1] : "";
        return new CodeCitation(d.id(), d.name(), d.signature(), file, d.startLine(), d.endLine());
    }

    private static DocCitation toDoc(DocHit h) {
        String text = h.text() == null ? "" : h.text();
        String snippet = text.length() > 200 ? text.substring(0, 200) : text;
        return new DocCitation(h.source(), h.score(), snippet);
    }
}
