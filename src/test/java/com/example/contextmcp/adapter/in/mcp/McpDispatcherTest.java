package com.example.contextmcp.adapter.in.mcp;

import com.example.contextmcp.domain.model.Digest;
import com.example.contextmcp.domain.model.Skeleton;
import com.example.contextmcp.domain.model.Symbol;
import com.example.contextmcp.domain.model.SymbolKind;
import com.example.contextmcp.domain.port.in.DynamicContextUseCase;
import com.example.contextmcp.domain.port.in.ImpactUseCase;
import com.example.contextmcp.domain.port.in.ImpactUseCase.Impact;
import com.example.contextmcp.domain.port.in.IndexUseCase;
import com.example.contextmcp.domain.port.in.LedgerUseCase;
import com.example.contextmcp.domain.port.in.RetrieveUseCase;
import com.example.contextmcp.domain.port.in.RetrieveUseCase.ExpandedSpan;
import com.example.contextmcp.domain.port.in.RetrieveUseCase.SymbolDigest;
import com.example.contextmcp.domain.port.out.LedgerStore.Totals;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpDispatcherTest {

    private final ObjectMapper json = new ObjectMapper();
    private final IndexUseCase index = mock(IndexUseCase.class);
    private final RetrieveUseCase retrieve = mock(RetrieveUseCase.class);
    private final ImpactUseCase impact = mock(ImpactUseCase.class);
    private final DynamicContextUseCase dynamic = mock(DynamicContextUseCase.class);
    private final LedgerUseCase ledger = mock(LedgerUseCase.class);
    private final com.example.contextmcp.domain.port.in.SyncUseCase sync =
            mock(com.example.contextmcp.domain.port.in.SyncUseCase.class);
    private final com.example.contextmcp.domain.port.in.FederatedContextUseCase federated =
            mock(com.example.contextmcp.domain.port.in.FederatedContextUseCase.class);
    private McpDispatcher dispatcher;

    @BeforeEach
    void setup() {
        dispatcher = new McpDispatcher(index, retrieve, impact, dynamic, ledger, sync, federated);
        when(ledger.totals()).thenReturn(new Totals(3, 100, 400));
    }

    private JsonNode handle(String req) throws Exception {
        return json.readTree(dispatcher.handle(req));
    }

    @Test
    void initializeReturnsServerInfo() throws Exception {
        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":1,"method":"initialize"}""");
        assertThat(r.path("result").path("serverInfo").path("name").asText()).isEqualTo("context-mcp");
        assertThat(r.path("id").asInt()).isEqualTo(1);
    }

    @Test
    void toolsListAdvertisesAllToolsWithSchemas() throws Exception {
        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}""");
        List<String> names = r.path("result").path("tools").findValuesAsText("name");
        assertThat(names).contains("outline", "search_symbols", "get_digest", "expand",
                "impact_of", "recent_changes", "working_changes", "index", "sync", "ledger");
        JsonNode outline = r.path("result").path("tools").get(0);
        assertThat(outline.path("inputSchema").path("type").asText()).isEqualTo("object");
    }

    @Test
    void callGetDigestReturnsPayloadAndTokensFooter() throws Exception {
        Symbol s = new Symbol(Symbol.idOf("r", "A.java", "A#m"), "r", "A.java", "java",
                SymbolKind.METHOD, "m", "A#m", "void m()", "", 1, 2, List.of(), "h");
        when(retrieve.getDigest("r:A.java:A#m"))
                .thenReturn(new Digest(s, new Skeleton(s.id(), "h", "void m()", 3), Optional.empty()));

        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"get_digest","arguments":{"symbolId":"r:A.java:A#m"}}}""");

        assertThat(r.path("result").path("content").get(0).path("json").path("skeleton")
                .path("text").asText()).isEqualTo("void m()");
        assertThat(r.path("result").path("tokens").path("baselineTokens").asLong()).isEqualTo(400);
    }

    @Test
    void callSearchSymbolsPassesTopK() throws Exception {
        when(retrieve.searchSymbols(eq("r"), eq("foo"), eq(5)))
                .thenReturn(List.of(new SymbolDigest("id1", "METHOD", "foo", "void foo()", "", 1, 2)));

        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call",
                 "params":{"name":"search_symbols","arguments":{"repo":"r","query":"foo","topK":5}}}""");

        assertThat(r.path("result").path("content").get(0).path("json").get(0).path("name").asText())
                .isEqualTo("foo");
        verify(retrieve).searchSymbols("r", "foo", 5);
    }

    @Test
    void callExpandReturnsSpan() throws Exception {
        when(retrieve.expand("sid")).thenReturn(new ExpandedSpan("sid", "A.java", 10, 12, "body();"));
        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":5,"method":"tools/call",
                 "params":{"name":"expand","arguments":{"symbolId":"sid"}}}""");
        assertThat(r.path("result").path("content").get(0).path("json").path("source").asText())
                .isEqualTo("body();");
    }

    @Test
    void callImpactOf() throws Exception {
        when(impact.impactOf("sid")).thenReturn(new Impact("sid", List.of("c1"), List.of("t1")));
        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":6,"method":"tools/call",
                 "params":{"name":"impact_of","arguments":{"symbolId":"sid"}}}""");
        assertThat(r.path("result").path("content").get(0).path("json").path("callers").get(0).asText())
                .isEqualTo("c1");
    }

    @Test
    void callIndexInvokesIndexUseCase() throws Exception {
        when(index.index("r", "/root"))
                .thenReturn(new IndexUseCase.IndexResult("r", 2, 2, 0, 8));
        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":7,"method":"tools/call",
                 "params":{"name":"index","arguments":{"repo":"r","root":"/root"}}}""");
        assertThat(r.path("result").path("content").get(0).path("json").path("symbols").asInt())
                .isEqualTo(8);
        verify(index).index("r", "/root");
    }

    @Test
    void missingRequiredArgumentIsInvalidParams() throws Exception {
        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":8,"method":"tools/call",
                 "params":{"name":"get_digest","arguments":{}}}""");
        assertThat(r.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void callExplainDelegatesToFederatedDocs() throws Exception {
        when(federated.explain(eq("how does rate limiting work"), any(), anyInt()))
                .thenReturn(List.of(new com.example.contextmcp.domain.port.out.FederatedRetriever
                        .DocHit("skill:ratelimit", 0.9, "token bucket per client")));
        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":10,"method":"tools/call",
                 "params":{"name":"explain","arguments":{"query":"how does rate limiting work"}}}""");
        assertThat(r.path("result").path("content").get(0).path("json").get(0).path("source").asText())
                .isEqualTo("skill:ratelimit");
    }

    @Test
    void callRecallDelegatesToFederatedCode() throws Exception {
        when(federated.recall(eq("rate limit"), anyInt()))
                .thenReturn(List.of(new com.example.contextmcp.domain.port.out.FederatedRetriever
                        .CodeHit("r:F.java:F#m", "F.java", "m", "METHOD", "void m()", 0.5)));
        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":11,"method":"tools/call",
                 "params":{"name":"recall","arguments":{"query":"rate limit"}}}""");
        assertThat(r.path("result").path("content").get(0).path("json").get(0).path("symbol").asText())
                .isEqualTo("m");
    }

    @Test
    void explainReturnsEmptyGracefullyWhenBackendYieldsNothing() throws Exception {
        when(federated.explain(any(), any(), anyInt())).thenReturn(List.of());
        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":12,"method":"tools/call",
                 "params":{"name":"explain","arguments":{"query":"x"}}}""");
        assertThat(r.path("result").path("content").get(0).path("json").isArray()).isTrue();
        assertThat(r.path("result").path("content").get(0).path("json")).isEmpty();
    }

    @Test
    void searchTopKIsClampedToMaxFanout() throws Exception {
        when(retrieve.searchSymbols(eq("r"), eq("q"), eq(50))).thenReturn(List.of());
        handle("""
                {"jsonrpc":"2.0","id":20,"method":"tools/call",
                 "params":{"name":"search_symbols","arguments":{"repo":"r","query":"q","topK":100000}}}""");
        verify(retrieve).searchSymbols("r", "q", 50);
    }

    @Test
    void nonPositiveTopKFallsBackToDefault() throws Exception {
        when(retrieve.searchSymbols(eq("r"), eq("q"), eq(10))).thenReturn(List.of());
        handle("""
                {"jsonrpc":"2.0","id":21,"method":"tools/call",
                 "params":{"name":"search_symbols","arguments":{"repo":"r","query":"q","topK":-5}}}""");
        verify(retrieve).searchSymbols("r", "q", 10);
    }

    @Test
    void unknownMethodIsMethodNotFound() throws Exception {
        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":9,"method":"does/notExist"}""");
        assertThat(r.path("error").path("code").asInt()).isEqualTo(-32601);
    }

    @Test
    void malformedJsonIsParseError() throws Exception {
        JsonNode r = handle("not json at all");
        assertThat(r.path("error").path("code").asInt()).isEqualTo(-32700);
    }

    @Test
    void notificationWithoutIdForUnknownMethodYieldsNoResponse() {
        assertThat(dispatcher.handle("""
                {"jsonrpc":"2.0","method":"does/notExist"}""")).isNull();
    }
}
