package io.github.vedmider.dylext.adapter.in.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/** Smoke test: full Spring context + real DB, driving tools through the MCP dispatcher end to end. */
@Testcontainers
@SpringBootTest
class McpEndToEndIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private McpDispatcher dispatcher;

    private JsonNode call(String name, String argsJson) throws Exception {
        String req = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"%s","arguments":%s}}"""
                .formatted(name, argsJson);
        return json.readTree(dispatcher.handle(req)).path("result");
    }

    @Test
    void indexThenSearchThenExpandThroughMcp() throws Exception {
        JsonNode indexed = call("index", "{\"repo\":\"e2e\",\"root\":\"src/main/java\"}");
        assertThat(indexed.path("content").get(0).path("json").path("symbols").asInt())
                .isPositive();

        JsonNode hits = call("search_symbols",
                "{\"repo\":\"e2e\",\"query\":\"searchSymbols\",\"topK\":3}");
        JsonNode first = hits.path("content").get(0).path("json").get(0);
        assertThat(first.path("id").asText()).isNotBlank();

        JsonNode expanded = call("expand",
                "{\"symbolId\":\"%s\"}".formatted(first.path("id").asText()));
        assertThat(expanded.path("content").get(0).path("json").path("source").asText())
                .isNotBlank();
        assertThat(expanded.path("tokens").path("calls").asLong()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void toolsListIsServedThroughRealContext() throws Exception {
        JsonNode r = json.readTree(dispatcher.handle("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}"""));
        assertThat(r.path("result").path("tools")).isNotEmpty();
    }

    @Test
    void verifyClaimsGroundsRealAndFakeSymbolsThroughMcp() throws Exception {
        call("index", "{\"repo\":\"vfe2e\",\"root\":\"src/main/java\"}");

        JsonNode result = call("verify_claims",
                "{\"answer\":\"the dispatcher calls searchSymbols() and also GhostThing#vanish\","
                + "\"repo\":\"vfe2e\"}");
        JsonNode body = result.path("content").get(0).path("json");

        JsonNode citations = body.path("citations");
        assertThat(citations).isNotEmpty();

        JsonNode verified = find(citations, "searchSymbols");
        assertThat(verified.path("status").asText()).isEqualTo("VERIFIED");
        assertThat(verified.path("signature").asText()).isNotBlank();
        assertThat(verified.path("startLine").asInt()).isPositive();
        // no raw source body leaks — only signature/location are surfaced
        assertThat(verified.has("source")).isFalse();

        JsonNode fake = find(citations, "vanish");
        assertThat(fake.path("status").asText()).isEqualTo("UNVERIFIED");
        assertThat(body.path("unverifiedCount").asInt()).isGreaterThanOrEqualTo(1);
    }

    private static JsonNode find(JsonNode citations, String token) {
        for (JsonNode c : citations) {
            if (token.equals(c.path("token").asText())) {
                return c;
            }
        }
        throw new AssertionError("no citation for token: " + token);
    }
}
