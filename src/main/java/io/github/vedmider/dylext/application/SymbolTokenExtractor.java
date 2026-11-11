package io.github.vedmider.dylext.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls candidate code-symbol tokens out of free answer text, conservatively: it favors precision
 * (missing a citation) over flagging ordinary prose. Recognizes qualified references
 * ({@code Type#method}, {@code Type.method}, {@code a.b.C}), {@code method()} calls, and multi-hump
 * CamelCase identifiers; the returned token is the bare symbol name to look up.
 */
public class SymbolTokenExtractor {

    // Type#member or Type.member (member starts lowercase — a method/field, not a nested Type).
    private static final Pattern QUALIFIED =
            Pattern.compile("\\b([A-Z][A-Za-z0-9]+)[#.]([a-z][A-Za-z0-9]*)\\b");
    // name( — a method call reference.
    private static final Pattern CALL =
            Pattern.compile("\\b([a-z][A-Za-z0-9]*)\\s*\\(");
    // Multi-hump CamelCase type, e.g. RateLimitInterceptor (at least two uppercase humps).
    private static final Pattern CAMEL_TYPE =
            Pattern.compile("\\b([A-Z][a-z0-9]+[A-Z][A-Za-z0-9]*)\\b");

    public List<String> extract(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Matcher q = QUALIFIED.matcher(text);
        while (q.find()) {
            tokens.add(q.group(1));   // the type
            tokens.add(q.group(2));   // the member
        }
        Matcher c = CALL.matcher(text);
        while (c.find()) {
            tokens.add(c.group(1));
        }
        Matcher t = CAMEL_TYPE.matcher(text);
        while (t.find()) {
            tokens.add(t.group(1));
        }
        return List.copyOf(tokens);
    }
}
