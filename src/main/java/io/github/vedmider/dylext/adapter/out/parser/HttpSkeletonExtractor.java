package io.github.vedmider.dylext.adapter.out.parser;

import io.github.vedmider.dylext.domain.model.Hashing;
import io.github.vedmider.dylext.domain.model.Symbol;
import io.github.vedmider.dylext.domain.model.SymbolKind;
import io.github.vedmider.dylext.domain.port.out.SkeletonExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Lego adapter: delegates parsing to an out-of-process language parser over HTTP, mapping its
 * neutral symbol JSON back into Dylext's {@link Symbol}. Dylext keeps ownership of identity
 * (repo:filePath:symbolPath) and content hashing; the sidecar only reports logical symbols.
 * Any failure degrades to a single MODULE symbol, matching {@code JavaSkeletonExtractor}.
 */
public class HttpSkeletonExtractor implements SkeletonExtractor {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String language;
    private final String parseUrl;

    public HttpSkeletonExtractor(String language, String baseUrl) {
        this.language = language;
        this.parseUrl = baseUrl.replaceAll("/+$", "") + "/parse";
    }

    @Override
    public String language() {
        return language;
    }

    @Override
    public List<Symbol> extract(String repo, String filePath, String source) {
        String hash = Hashing.sha256(source);
        int lineCount = Math.max(1, (int) source.lines().count());
        JsonNode body = callParser(repo, filePath, source);
        if (body == null || !body.path("symbols").isArray()) {
            return List.of(module(repo, filePath, hash, lineCount, "// unparsed (" + lineCount + " lines)"));
        }

        List<Symbol> out = new ArrayList<>();
        boolean sawModule = false;
        for (JsonNode n : body.path("symbols")) {
            Symbol s = toSymbol(repo, filePath, n, hash, lineCount);
            if (s.kind() == SymbolKind.MODULE) {
                sawModule = true;
                out.add(0, s);
            } else {
                out.add(s);
            }
        }
        if (!sawModule) {
            out.add(0, module(repo, filePath, hash, lineCount, moduleSkeleton(out)));
        }
        return out;
    }

    private Symbol toSymbol(String repo, String file, JsonNode n, String fileHash, int lineCount) {
        SymbolKind kind = parseKind(n.path("kind").asText("MODULE"));
        if (kind == SymbolKind.MODULE) {
            return module(repo, file, fileHash, lineCount,
                    n.path("signature").asText(moduleName(file)));
        }
        String symbolPath = n.path("symbolPath").asText(n.path("name").asText(""));
        String name = n.path("name").asText("");
        String signature = n.path("signature").asText(name);
        String doc = n.path("docFirstLine").asText("");
        int start = n.path("startLine").asInt(1);
        int end = n.path("endLine").asInt(start);
        List<String> calls = new ArrayList<>();
        for (JsonNode c : n.path("callTargets")) {
            calls.add(c.asText());
        }
        String hash = Hashing.sha256(signature + "|" + doc + "|" + String.join(",", calls));
        return new Symbol(Symbol.idOf(repo, file, symbolPath), repo, file, language, kind, name,
                symbolPath, signature, doc, start, end, calls, hash);
    }

    private Symbol module(String repo, String file, String hash, int lineCount, String skeleton) {
        return new Symbol(Symbol.idOf(repo, file, "<module>"), repo, file, language, SymbolKind.MODULE,
                moduleName(file), "<module>", skeleton, "", 1, lineCount, List.of(), hash);
    }

    private static String moduleSkeleton(List<Symbol> members) {
        StringBuilder sb = new StringBuilder();
        members.forEach(m -> sb.append(m.signature()).append('\n'));
        return sb.toString().stripTrailing();
    }

    private static SymbolKind parseKind(String raw) {
        try {
            return SymbolKind.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            return SymbolKind.METHOD;   // conservative default for callable-ish symbols
        }
    }

    private static String moduleName(String file) {
        int slash = file.lastIndexOf('/');
        return slash >= 0 ? file.substring(slash + 1) : file;
    }

    private JsonNode callParser(String repo, String filePath, String source) {
        try {
            var payload = json.createObjectNode();
            payload.put("repo", repo);
            payload.put("filePath", filePath);
            payload.put("language", language);
            payload.put("source", source);
            HttpRequest req = HttpRequest.newBuilder(URI.create(parseUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                return null;
            }
            return json.readTree(resp.body());
        } catch (Exception unreachable) {
            return null;
        }
    }
}
