package com.example.contextmcp.adapter.in.mcp;

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
}
