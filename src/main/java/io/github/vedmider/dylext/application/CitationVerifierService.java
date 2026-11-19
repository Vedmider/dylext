package io.github.vedmider.dylext.application;

import io.github.vedmider.dylext.domain.model.VerifiedAnswer;
import io.github.vedmider.dylext.domain.model.VerifiedAnswer.Citation;
import io.github.vedmider.dylext.domain.port.in.RetrieveUseCase;
import io.github.vedmider.dylext.domain.port.in.RetrieveUseCase.SymbolDigest;
import io.github.vedmider.dylext.domain.port.in.VerifyUseCase;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Verifies an answer's cited symbols against the index via {@link RetrieveUseCase#searchSymbols}. */
@Service
public class CitationVerifierService implements VerifyUseCase {

    private static final int MAX_TOKENS = 50;

    private final RetrieveUseCase retrieve;
    private final SymbolTokenExtractor extractor = new SymbolTokenExtractor();

    public CitationVerifierService(RetrieveUseCase retrieve) {
        this.retrieve = retrieve;
    }

    @Override
    public VerifiedAnswer verify(String answer, String repo) {
        List<String> tokens = extractor.extract(answer).stream().limit(MAX_TOKENS).toList();
        List<Citation> citations = new ArrayList<>();
        int unverified = 0;
        for (String token : tokens) {
            List<SymbolDigest> matches = matches(repo, token);
            if (matches.isEmpty()) {
                citations.add(Citation.unverified(token));
                unverified++;
            } else {
                SymbolDigest hit = matches.get(0);
                citations.add(Citation.verified(token, hit.id(), hit.signature(),
                        filePathOf(hit.id()), hit.startLine(), matches.size()));
            }
        }
        return new VerifiedAnswer(answer, List.copyOf(citations), unverified);
    }

    /** All indexed symbols whose simple name equals the cited token (overloads/homonyms → several). */
    private List<SymbolDigest> matches(String repo, String token) {
        return retrieve.searchSymbols(repo, token, 25).stream()
                .filter(d -> token.equals(d.name()))
                .toList();
    }

    private static String filePathOf(String symbolId) {
        String[] parts = symbolId.split(":", 3);
        return parts.length == 3 ? parts[1] : "";
    }
}
