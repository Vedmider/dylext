package io.github.vedmider.dylext.domain.port.in;

import io.github.vedmider.dylext.domain.model.VerifiedAnswer;

/**
 * Inbound port: ground an answer's code references against the symbol index. Every candidate symbol
 * the answer cites is marked VERIFIED (exists, with its real signature/location) or UNVERIFIED
 * (not found) — anti-hallucination for code-referencing answers. Does not rewrite the answer.
 */
public interface VerifyUseCase {

    VerifiedAnswer verify(String answer, String repo);
}
