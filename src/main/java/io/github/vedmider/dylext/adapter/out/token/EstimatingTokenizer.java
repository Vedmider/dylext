package io.github.vedmider.dylext.adapter.out.token;

import io.github.vedmider.dylext.domain.port.out.Tokenizer;
import org.springframework.stereotype.Component;

/**
 * Deterministic token estimate: splits on whitespace and standalone punctuation, roughly tracking
 * BPE token counts. Replace with a real tokenizer adapter (Task 7) for exact ledger figures.
 */
@Component
public class EstimatingTokenizer implements Tokenizer {

    @Override
    public int count(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        String spaced = text.replaceAll("([\\p{Punct}])", " $1 ");
        return (int) spaced.trim().split("\\s+").length;
    }
}
