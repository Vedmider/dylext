package io.github.vedmider.dylext.adapter.out.federation;

import io.github.vedmider.dylext.domain.port.out.FederatedRetriever;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP delegation to Sestacon (/api/retrieve) and code-memory (/api/search). Any failure — service
 * down, timeout, non-2xx, bad body — degrades to an empty list so the agent falls back to local tools.
 */
@Component
public class HttpFederatedRetriever implements FederatedRetriever {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String ragUrl;
    private final String cmUrl;

    public HttpFederatedRetriever(
            @Value("${dylext.federation.rag-url:http://localhost:18080}") String ragUrl,
            @Value("${dylext.federation.cm-url:http://localhost:8080}") String cmUrl) {
        this.ragUrl = ragUrl;
        this.cmUrl = cmUrl;
    }

    @Override
    public List<DocHit> explain(String query, String roles, int topK) {
        JsonNode body = post(ragUrl + "/api/retrieve",
                "{\"query\":%s,\"topK\":%d}".formatted(quote(query), topK),
                roles == null || roles.isBlank() ? null : roles);
        if (body == null || !body.isArray()) {
            return List.of();
        }
        List<DocHit> out = new ArrayList<>();
        for (JsonNode h : body) {
            out.add(new DocHit(h.path("source").asText(""), h.path("score").asDouble(),
                    h.path("text").asText("")));
        }
        return out;
    }

    @Override
    public List<CodeHit> recall(String query, int topK) {
        JsonNode body = post(cmUrl + "/api/search",
                "{\"query\":%s,\"agent\":\"all\",\"topK\":%d}".formatted(quote(query), topK), null);
        if (body == null) {
            return List.of();
        }
        List<CodeHit> out = new ArrayList<>();
        for (JsonNode h : body.path("results")) {
            out.add(new CodeHit(h.path("id").asText(""), h.path("file").asText(""),
                    h.path("symbol").asText(""), h.path("kind").asText(""),
                    h.path("signature").asText(""), h.path("score").asDouble()));
        }
        return out;
    }

    private JsonNode post(String url, String jsonBody, String roles) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
            if (roles != null) {
                b.header("X-Roles", roles);
            }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                return null;
            }
            return json.readTree(resp.body());
        } catch (Exception unreachable) {
            return null;
        }
    }

    private String quote(String s) {
        try {
            return json.writeValueAsString(s);
        } catch (Exception e) {
            return "\"\"";
        }
    }
}
