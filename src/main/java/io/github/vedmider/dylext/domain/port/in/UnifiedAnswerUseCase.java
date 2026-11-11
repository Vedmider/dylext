package io.github.vedmider.dylext.domain.port.in;

import java.util.List;

/**
 * One front door over both brains: routes a question by intent to code intelligence (this repo's
 * symbol index) and/or document knowledge (Sestacon), returning merged, evidence-backed citations.
 * Each side degrades independently — a doc-service outage still yields code evidence and vice versa.
 */
public interface UnifiedAnswerUseCase {

    Result answer(String query, String repo, String roles, int topK);

    enum Intent {CODE, DOCS, BOTH}

    /** A grounded code reference: the symbol, where it lives, and its signature. */
    record CodeCitation(String symbolId, String name, String signature, String filePath,
                        int startLine, int endLine) {
    }

    /** A grounded doc reference: the source and its relevance score. */
    record DocCitation(String source, double score, String snippet) {
    }

    record Result(String query, Intent intent, List<CodeCitation> code, List<DocCitation> docs,
                  boolean codeAvailable, boolean docsAvailable) {
    }
}
