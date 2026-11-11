package io.github.vedmider.dylext.domain.model;

import java.util.List;

/**
 * An answer annotated with the verification status of every code symbol it cites. Non-destructive:
 * the original {@code answer} is unchanged; {@code citations} say which referenced symbols actually
 * exist in the indexed code (grounding against hallucinated references).
 */
public record VerifiedAnswer(String answer, List<Citation> citations, int unverifiedCount) {

    public enum Status { VERIFIED, UNVERIFIED }

    /**
     * One code symbol referenced by an answer. VERIFIED carries the real signature/location from the
     * index; UNVERIFIED means no such symbol was found (likely hallucinated or stale).
     */
    public record Citation(String token, Status status, String symbolId, String signature,
                           String filePath, int startLine) {

        public static Citation verified(String token, String symbolId, String signature,
                                        String filePath, int startLine) {
            return new Citation(token, Status.VERIFIED, symbolId, signature, filePath, startLine);
        }

        public static Citation unverified(String token) {
            return new Citation(token, Status.UNVERIFIED, null, null, null, 0);
        }
    }
}
