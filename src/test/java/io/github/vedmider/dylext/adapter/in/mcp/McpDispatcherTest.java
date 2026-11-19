package io.github.vedmider.dylext.adapter.in.mcp;

import io.github.vedmider.dylext.domain.model.Digest;
import io.github.vedmider.dylext.domain.model.Skeleton;
import io.github.vedmider.dylext.domain.model.Symbol;
import io.github.vedmider.dylext.domain.model.SymbolKind;
import io.github.vedmider.dylext.domain.port.in.DynamicContextUseCase;
import io.github.vedmider.dylext.domain.port.in.ImpactUseCase;
import io.github.vedmider.dylext.domain.port.in.ImpactUseCase.Impact;
import io.github.vedmider.dylext.domain.port.in.IndexUseCase;
import io.github.vedmider.dylext.domain.port.in.LedgerUseCase;
import io.github.vedmider.dylext.domain.port.in.RetrieveUseCase;
import io.github.vedmider.dylext.domain.port.in.RetrieveUseCase.ExpandedSpan;
import io.github.vedmider.dylext.domain.port.in.RetrieveUseCase.SymbolDigest;
import io.github.vedmider.dylext.domain.port.out.LedgerStore.Totals;
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
    private final io.github.vedmider.dylext.domain.port.in.SyncUseCase sync =
            mock(io.github.vedmider.dylext.domain.port.in.SyncUseCase.class);
    private final io.github.vedmider.dylext.domain.port.in.SessionUseCase session =
            mock(io.github.vedmider.dylext.domain.port.in.SessionUseCase.class);
    private final io.github.vedmider.dylext.domain.port.in.VerifyUseCase verify =
            mock(io.github.vedmider.dylext.domain.port.in.VerifyUseCase.class);
    private final io.github.vedmider.dylext.domain.port.in.RepoCatalogUseCase catalog =
            mock(io.github.vedmider.dylext.domain.port.in.RepoCatalogUseCase.class);
    private final io.github.vedmider.dylext.domain.port.in.SignalUseCase signalsUc =
            mock(io.github.vedmider.dylext.domain.port.in.SignalUseCase.class);
    private final io.github.vedmider.dylext.domain.port.in.AnnotateUseCase annotateUc =
            mock(io.github.vedmider.dylext.domain.port.in.AnnotateUseCase.class);
    private McpDispatcher dispatcher;
    private final io.github.vedmider.dylext.application.ResponseCache cache =
            new io.github.vedmider.dylext.application.ResponseCache(true, 2000);

    @BeforeEach
    void setup() {
        dispatcher = new McpDispatcher(index, retrieve, impact, dynamic, ledger, sync,
                session, verify, catalog, true,
                new io.github.vedmider.dylext.application.ResponseCache(false, 2000), signalsUc, annotateUc);
        when(ledger.totals()).thenReturn(new Totals(3, 100, 400));
    }

    private McpDispatcher withCache() {
        return new McpDispatcher(index, retrieve, impact, dynamic, ledger, sync,
                session, verify, catalog, true, cache, signalsUc, annotateUc);
    }

    private JsonNode handle(String req) throws Exception {
        return json.readTree(dispatcher.handle(req));
    }

    @Test
    void repoScopedCallCarriesCommittedEpoch() throws Exception {
        when(catalog.epochFor("r")).thenReturn(
                io.github.vedmider.dylext.domain.model.EpochRef.committed("r", "abc123"));
        when(dynamic.recentChanges(eq("r"), any(), anyInt())).thenReturn(List.of());

        JsonNode epoch = handle("""
                {"jsonrpc":"2.0","id":50,"method":"tools/call",
                 "params":{"name":"recent_changes","arguments":{"repo":"r"}}}""")
                .path("result").path("epoch");

        assertThat(epoch.path("provider").asText()).isEqualTo("dylext");
        assertThat(epoch.path("repo").asText()).isEqualTo("r");
        assertThat(epoch.path("commit").asText()).isEqualTo("abc123");
        assertThat(epoch.path("dirty").asBoolean()).isFalse();
    }

    @Test
    void workingChangesEpochIsDirty() throws Exception {
        when(catalog.epochFor("r")).thenReturn(
                io.github.vedmider.dylext.domain.model.EpochRef.committed("r", "abc123"));
        when(dynamic.workingChanges(eq("r"), any())).thenReturn(List.of());

        JsonNode epoch = handle("""
                {"jsonrpc":"2.0","id":51,"method":"tools/call",
                 "params":{"name":"working_changes","arguments":{"repo":"r"}}}""")
                .path("result").path("epoch");

        assertThat(epoch.path("dirty").asBoolean()).isTrue();
        assertThat(epoch.path("commit").asText()).isEqualTo("abc123");
    }

    @Test
    void symbolIdToolDerivesRepoEpochFromThePrefix() throws Exception {
        when(impact.impactOf(org.mockito.ArgumentMatchers.eq("myrepo:A.java:A#m"), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new Impact("myrepo:A.java:A#m", List.of(), List.of()));
        when(catalog.epochFor("myrepo")).thenReturn(
                io.github.vedmider.dylext.domain.model.EpochRef.committed("myrepo", "deadbeef"));

        JsonNode epoch = handle("""
                {"jsonrpc":"2.0","id":52,"method":"tools/call",
                 "params":{"name":"impact_of","arguments":{"symbolId":"myrepo:A.java:A#m"}}}""")
                .path("result").path("epoch");

        assertThat(epoch.path("repo").asText()).isEqualTo("myrepo");
        assertThat(epoch.path("commit").asText()).isEqualTo("deadbeef");
    }

    @Test
    void nonRepoToolHasNoEpoch() throws Exception {
        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":53,"method":"tools/call",
                 "params":{"name":"ledger","arguments":{}}}""");
        assertThat(r.path("result").has("epoch")).isFalse();
    }

    @Test
    void epochIsOmittedWhenDisabled() throws Exception {
        McpDispatcher off = new McpDispatcher(index, retrieve, impact, dynamic, ledger, sync,
                session, verify, catalog, false,
                new io.github.vedmider.dylext.application.ResponseCache(false, 2000), signalsUc, annotateUc);
        when(catalog.epochFor("r")).thenReturn(
                io.github.vedmider.dylext.domain.model.EpochRef.committed("r", "abc123"));
        when(dynamic.workingChanges(eq("r"), any())).thenReturn(List.of());

        JsonNode r = json.readTree(off.handle("""
                {"jsonrpc":"2.0","id":54,"method":"tools/call",
                 "params":{"name":"working_changes","arguments":{"repo":"r"}}}"""));
        assertThat(r.path("result").has("epoch")).isFalse();
    }

    @Test
    void secondIdenticalReadIsServedFromCacheWithoutRecomputing() throws Exception {
        McpDispatcher d = withCache();
        when(catalog.epochFor("myrepo")).thenReturn(
                io.github.vedmider.dylext.domain.model.EpochRef.committed("myrepo", "c1"));
        when(impact.impactOf(org.mockito.ArgumentMatchers.eq("myrepo:A.java:A#m"), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new Impact("myrepo:A.java:A#m", List.of("x"), List.of()));
        String call = """
                {"jsonrpc":"2.0","id":60,"method":"tools/call",
                 "params":{"name":"impact_of","arguments":{"symbolId":"myrepo:A.java:A#m"}}}""";

        JsonNode first = json.readTree(d.handle(call));
        JsonNode second = json.readTree(d.handle(call));

        assertThat(first.path("result").path("cached").asBoolean()).isFalse();
        assertThat(second.path("result").path("cached").asBoolean()).isTrue();
        assertThat(second.path("result").path("content").get(0).path("json").path("callers").get(0).asText())
                .isEqualTo("x");
        verify(impact, org.mockito.Mockito.times(1)).impactOf(org.mockito.ArgumentMatchers.eq("myrepo:A.java:A#m"), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void aNewEpochBustsTheCache() throws Exception {
        McpDispatcher d = withCache();
        when(impact.impactOf(org.mockito.ArgumentMatchers.eq("myrepo:A.java:A#m"), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new Impact("myrepo:A.java:A#m", List.of(), List.of()));
        String call = """
                {"jsonrpc":"2.0","id":61,"method":"tools/call",
                 "params":{"name":"impact_of","arguments":{"symbolId":"myrepo:A.java:A#m"}}}""";

        when(catalog.epochFor("myrepo")).thenReturn(
                io.github.vedmider.dylext.domain.model.EpochRef.committed("myrepo", "c1"));
        d.handle(call);
        when(catalog.epochFor("myrepo")).thenReturn(
                io.github.vedmider.dylext.domain.model.EpochRef.committed("myrepo", "c2"));
        JsonNode afterBump = json.readTree(d.handle(call));

        assertThat(afterBump.path("result").path("cached").asBoolean()).isFalse();
        verify(impact, org.mockito.Mockito.times(2)).impactOf(org.mockito.ArgumentMatchers.eq("myrepo:A.java:A#m"), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void dirtyWorkingChangesIsNeverCached() throws Exception {
        McpDispatcher d = withCache();
        when(catalog.epochFor("r")).thenReturn(
                io.github.vedmider.dylext.domain.model.EpochRef.committed("r", "c1"));
        when(dynamic.workingChanges(eq("r"), any())).thenReturn(List.of());
        String call = """
                {"jsonrpc":"2.0","id":62,"method":"tools/call",
                 "params":{"name":"working_changes","arguments":{"repo":"r"}}}""";

        JsonNode r1 = json.readTree(d.handle(call));
        JsonNode r2 = json.readTree(d.handle(call));

        assertThat(r1.path("result").has("cached")).isFalse();
        assertThat(r2.path("result").has("cached")).isFalse();
        verify(dynamic, org.mockito.Mockito.times(2)).workingChanges(eq("r"), any());
    }

    @Test
    void cacheDisabledMeansNoCachedFlagAndAlwaysRecomputes() throws Exception {
        when(catalog.epochFor("myrepo")).thenReturn(
                io.github.vedmider.dylext.domain.model.EpochRef.committed("myrepo", "c1"));
        when(impact.impactOf(org.mockito.ArgumentMatchers.eq("myrepo:A.java:A#m"), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new Impact("myrepo:A.java:A#m", List.of(), List.of()));
        String call = """
                {"jsonrpc":"2.0","id":63,"method":"tools/call",
                 "params":{"name":"impact_of","arguments":{"symbolId":"myrepo:A.java:A#m"}}}""";

        JsonNode r1 = json.readTree(dispatcher.handle(call));
        json.readTree(dispatcher.handle(call));

        assertThat(r1.path("result").has("cached")).isFalse();
        verify(impact, org.mockito.Mockito.times(2)).impactOf(org.mockito.ArgumentMatchers.eq("myrepo:A.java:A#m"), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void ingestSignalsRoutesParsedSignalsToTheUseCase() throws Exception {
        when(signalsUc.ingest(org.mockito.ArgumentMatchers.eq("r"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new io.github.vedmider.dylext.domain.port.in.SignalUseCase.IngestResult("r", 1));
        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":70,"method":"tools/call",
                 "params":{"name":"ingest_signals","arguments":{"repo":"r",
                   "signals":[{"symbolId":"r:A.java:A#m","coverage":0.0,"callFrequency":42}]}}}""");
        assertThat(r.path("result").path("content").get(0).path("json").path("accepted").asInt()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        var cap = (org.mockito.ArgumentCaptor<java.util.List<
                io.github.vedmider.dylext.domain.model.SymbolSignal>>) (org.mockito.ArgumentCaptor<?>)
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(signalsUc).ingest(org.mockito.ArgumentMatchers.eq("r"), cap.capture());
        assertThat(cap.getValue()).hasSize(1);
        assertThat(cap.getValue().get(0).symbolId()).isEqualTo("r:A.java:A#m");
        assertThat(cap.getValue().get(0).weight()).isEqualTo(42.0);   // coverage 0 -> weight = callFreq
    }

    @Test
    void ingestSignalsIsAdvertised() throws Exception {
        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":71,"method":"tools/list"}""");
        assertThat(r.path("result").path("tools").findValuesAsText("name")).contains("ingest_signals");
    }

    @Test
    void ingestSignalsInvalidatesCachedReads() throws Exception {
        McpDispatcher d = withCache();
        when(catalog.epochFor("myrepo")).thenReturn(
                io.github.vedmider.dylext.domain.model.EpochRef.committed("myrepo", "c1"));
        when(impact.impactOf(org.mockito.ArgumentMatchers.eq("myrepo:A.java:A#m"), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new Impact("myrepo:A.java:A#m", List.of(), List.of()));
        when(signalsUc.ingest(org.mockito.ArgumentMatchers.eq("myrepo"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new io.github.vedmider.dylext.domain.port.in.SignalUseCase.IngestResult("myrepo", 1));
        String impactCall = """
                {"jsonrpc":"2.0","id":80,"method":"tools/call",
                 "params":{"name":"impact_of","arguments":{"symbolId":"myrepo:A.java:A#m"}}}""";

        d.handle(impactCall);                               // miss -> compute (1)
        JsonNode hit = json.readTree(d.handle(impactCall)); // hit
        assertThat(hit.path("result").path("cached").asBoolean()).isTrue();
        d.handle("""
                {"jsonrpc":"2.0","id":81,"method":"tools/call",
                 "params":{"name":"ingest_signals","arguments":{"repo":"myrepo",
                   "signals":[{"symbolId":"myrepo:A.java:A#m","coverage":0.0,"callFrequency":5}]}}}""");
        JsonNode afterIngest = json.readTree(d.handle(impactCall));   // must recompute (2)

        assertThat(afterIngest.path("result").path("cached").asBoolean()).isFalse();
        verify(impact, org.mockito.Mockito.times(2)).impactOf(org.mockito.ArgumentMatchers.eq("myrepo:A.java:A#m"), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void setSummaryToolIsAdvertisedAndRoutesToAnnotate() throws Exception {
        JsonNode list = handle("""
                {"jsonrpc":"2.0","id":90,"method":"tools/list"}""");
        assertThat(list.path("result").path("tools").findValuesAsText("name")).contains("set_summary");

        when(annotateUc.annotate("myrepo:A.java:A#m", "orchestrates routing"))
                .thenReturn(new io.github.vedmider.dylext.domain.port.in.AnnotateUseCase.Annotation(
                        "myrepo:A.java:A#m", "hash1", true));

        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":91,"method":"tools/call",
                 "params":{"name":"set_summary","arguments":{
                   "symbolId":"myrepo:A.java:A#m","text":"orchestrates routing"}}}""");

        assertThat(r.path("result").path("content").get(0).path("json").path("stored").asBoolean()).isTrue();
        verify(annotateUc).annotate("myrepo:A.java:A#m", "orchestrates routing");
    }

    @Test
    void initializeReturnsServerInfo() throws Exception {
        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":1,"method":"initialize"}""");
        assertThat(r.path("result").path("serverInfo").path("name").asText()).isEqualTo("dylext");
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
    void callSavingsReturnsEnrichedTotals() throws Exception {
        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":9,"method":"tools/call",
                 "params":{"name":"savings","arguments":{}}}""");
        JsonNode s = r.path("result").path("content").get(0).path("json");
        assertThat(s.path("saved").asLong()).isEqualTo(300);          // 400 baseline - 100 served
        assertThat(s.path("savedPercent").asDouble()).isEqualTo(75.0);
        assertThat(s.has("byTool")).isTrue();
    }

    @Test
    void callImpactOf() throws Exception {
        when(impact.impactOf(org.mockito.ArgumentMatchers.eq("sid"), org.mockito.ArgumentMatchers.anyInt())).thenReturn(new Impact("sid", List.of("c1"), List.of("t1")));
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
    void callSessionStartReturnsSession() throws Exception {
        when(session.start("improve retrieval")).thenReturn(
                io.github.vedmider.dylext.domain.model.Session.start("s-1", "improve retrieval"));
        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":30,"method":"tools/call",
                 "params":{"name":"session_start","arguments":{"task":"improve retrieval"}}}""");
        assertThat(r.path("result").path("content").get(0).path("json").path("id").asText())
                .isEqualTo("s-1");
    }

    @Test
    void callSessionFactStreamsFact() throws Exception {
        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":31,"method":"tools/call",
                 "params":{"name":"session_fact","arguments":{"id":"s-2",
                   "repo":"r","file":"F.java","symbol":"F.m","what":"did"}}}""");
        assertThat(r.path("result").path("content").get(0).path("json").path("ok").asBoolean()).isTrue();
        var cap = org.mockito.ArgumentCaptor.forClass(
                io.github.vedmider.dylext.domain.model.SessionEntry.class);
        verify(session).recordFact(org.mockito.ArgumentMatchers.eq("s-2"), cap.capture());
        assertThat(cap.getValue().type())
                .isEqualTo(io.github.vedmider.dylext.domain.model.SessionEntry.Type.FACT);
        assertThat(cap.getValue().symbol()).isEqualTo("F.m");
    }

    @Test
    void callSessionCommitBatchesDecisionsAndReturnsHeader() throws Exception {
        var s = io.github.vedmider.dylext.domain.model.Session.start("s-3", "t");
        when(session.commit(org.mockito.ArgumentMatchers.eq("s-3"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(s);
        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":34,"method":"tools/call",
                 "params":{"name":"session_commit","arguments":{"id":"s-3",
                   "entries":[{"type":"decision","what":"use reranker","why":"precision"},
                              {"type":"next","what":"wire it in"}]}}}""");
        assertThat(r.path("result").path("content").get(0).path("json").path("id").asText())
                .isEqualTo("s-3");
        @SuppressWarnings("unchecked")
        var cap = (org.mockito.ArgumentCaptor<java.util.List<
                io.github.vedmider.dylext.domain.model.SessionEntry>>) (org.mockito.ArgumentCaptor<?>)
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(session).commit(org.mockito.ArgumentMatchers.eq("s-3"), cap.capture());
        assertThat(cap.getValue()).hasSize(2);
        assertThat(cap.getValue().get(0).why()).isEqualTo("precision");
        assertThat(cap.getValue().get(1).type())
                .isEqualTo(io.github.vedmider.dylext.domain.model.SessionEntry.Type.NEXT);
    }

    @Test
    void callSessionRecallDefaultTrimsFactsButKeepsAllDecisions() throws Exception {
        var decisions = new java.util.ArrayList<io.github.vedmider.dylext.domain.model.SessionEntry>();
        for (int i = 0; i < 5; i++) {
            decisions.add(io.github.vedmider.dylext.domain.model.SessionEntry.decision("d" + i, "w"));
        }
        var facts = new java.util.ArrayList<io.github.vedmider.dylext.domain.model.SessionEntry>();
        for (int i = 0; i < 40; i++) {
            facts.add(io.github.vedmider.dylext.domain.model.SessionEntry.fact("r", "f", "s" + i, "e" + i));
        }
        var full = new io.github.vedmider.dylext.domain.model.Session("s-4", "t",
                io.github.vedmider.dylext.domain.model.Session.Status.ACTIVE, java.util.List.of(),
                decisions, facts, java.time.Instant.now());
        when(session.recall("s-4")).thenReturn(java.util.Optional.of(full));

        JsonNode def = handle("""
                {"jsonrpc":"2.0","id":35,"method":"tools/call",
                 "params":{"name":"session_recall","arguments":{"id":"s-4"}}}""");
        JsonNode body = def.path("result").path("content").get(0).path("json");
        assertThat(body.path("decisions")).hasSize(5);
        assertThat(body.path("facts")).hasSize(20);

        JsonNode fullR = handle("""
                {"jsonrpc":"2.0","id":36,"method":"tools/call",
                 "params":{"name":"session_recall","arguments":{"id":"s-4","full":true}}}""");
        assertThat(fullR.path("result").path("content").get(0).path("json").path("facts")).hasSize(40);
    }

    @Test
    void callSessionRecallSectionsPicksLayer() throws Exception {
        var full = new io.github.vedmider.dylext.domain.model.Session("s-5", "t",
                io.github.vedmider.dylext.domain.model.Session.Status.ACTIVE, java.util.List.of(),
                java.util.List.of(io.github.vedmider.dylext.domain.model.SessionEntry.decision("d", "w")),
                java.util.List.of(io.github.vedmider.dylext.domain.model.SessionEntry.fact("r", "f", "s", "e")),
                java.time.Instant.now());
        when(session.recall("s-5")).thenReturn(java.util.Optional.of(full));
        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":37,"method":"tools/call",
                 "params":{"name":"session_recall","arguments":{"id":"s-5","sections":["decisions"]}}}""");
        JsonNode body = r.path("result").path("content").get(0).path("json");
        assertThat(body.path("decisions")).hasSize(1);
        assertThat(body.path("facts")).isEmpty();
    }

    @Test
    void callSessionListAdvertisedWithoutRequiredArgs() throws Exception {
        when(session.list(any(), any(), any(), anyInt())).thenReturn(java.util.List.of());
        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":32,"method":"tools/call",
                 "params":{"name":"session_list","arguments":{"status":"ACTIVE"}}}""");
        assertThat(r.path("result").path("content").get(0).path("json").isArray()).isTrue();
    }

    @Test
    void sessionToolsAreListed() throws Exception {
        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":33,"method":"tools/list"}""");
        java.util.List<String> names = r.path("result").path("tools").findValuesAsText("name");
        assertThat(names).contains("session_start", "session_fact", "session_commit",
                "session_recall", "session_list", "session_close", "session_purge");
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

    @Test
    void verifyClaimsToolReturnsCitations() throws Exception {
        var citation = io.github.vedmider.dylext.domain.model.VerifiedAnswer.Citation.unverified("GhostType");
        when(verify.verify("answer text", "r")).thenReturn(
                new io.github.vedmider.dylext.domain.model.VerifiedAnswer(
                        "answer text", java.util.List.of(citation), 1));

        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":40,"method":"tools/call",
                 "params":{"name":"verify_claims","arguments":{"answer":"answer text","repo":"r"}}}""");

        JsonNode body = r.path("result").path("content").get(0).path("json");
        assertThat(body.path("unverifiedCount").asInt()).isEqualTo(1);
        assertThat(body.path("citations").get(0).path("token").asText()).isEqualTo("GhostType");
        assertThat(body.path("citations").get(0).path("status").asText()).isEqualTo("UNVERIFIED");
    }

    @Test
    void verifyClaimsToolIsAdvertised() throws Exception {
        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":41,"method":"tools/list"}""");
        assertThat(r.path("result").path("tools").findValuesAsText("name")).contains("verify_claims");
    }

    @Test
    void verifyClaimsMissingRepoIsInvalidParams() throws Exception {
        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":42,"method":"tools/call",
                 "params":{"name":"verify_claims","arguments":{"answer":"x"}}}""");
        assertThat(r.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void verifyClaimsBlankAnswerIsInvalidParams() throws Exception {
        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":43,"method":"tools/call",
                 "params":{"name":"verify_claims","arguments":{"answer":"  ","repo":"r"}}}""");
        assertThat(r.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void unknownRepoIsInvalidParamsWithTheServicesHintNotAnInternalError() throws Exception {
        when(dynamic.workingChanges(eq("ghost"), any()))
                .thenThrow(new java.util.NoSuchElementException(
                        "repo not indexed: ghost — call index {repo, root} first"));

        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":44,"method":"tools/call",
                 "params":{"name":"working_changes","arguments":{"repo":"ghost"}}}""");

        assertThat(r.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(r.path("error").path("message").asText())
                .contains("repo not indexed: ghost").contains("index");
    }

    @Test
    void unknownSessionIsInvalidParams() throws Exception {
        when(session.close("nope")).thenThrow(new java.util.NoSuchElementException("unknown session: nope"));

        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":45,"method":"tools/call",
                 "params":{"name":"session_close","arguments":{"id":"nope"}}}""");

        assertThat(r.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void genuinelyUnexpectedFailuresStayInternalErrors() throws Exception {
        when(dynamic.workingChanges(eq("boom"), any())).thenThrow(new IllegalStateException("disk exploded"));

        JsonNode r = handle("""
                {"jsonrpc":"2.0","id":46,"method":"tools/call",
                 "params":{"name":"working_changes","arguments":{"repo":"boom"}}}""");

        assertThat(r.path("error").path("code").asInt()).isEqualTo(-32603);
    }

    @Test
    void catalogToolsAreListedAndRouted() throws Exception {
        when(catalog.listRepos()).thenReturn(java.util.List.of(
                new io.github.vedmider.dylext.domain.port.in.RepoCatalogUseCase.RepoInfo("r", "/x", "main", "abc", 2, 5)));
        JsonNode names = handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").path("result").path("tools");
        java.util.Set<String> have = new java.util.HashSet<>();
        names.forEach(t -> have.add(t.path("name").asText()));
        org.assertj.core.api.Assertions.assertThat(have).contains("list_repos", "repo_overview", "languages");

        JsonNode res = handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"list_repos\",\"arguments\":{}}}");
        org.assertj.core.api.Assertions.assertThat(res.toString()).contains("branch").contains("main");
    }

    @Test
    void searchSymbolsPassesPathPrefixOnlyWhenGiven() throws Exception {
        handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"search_symbols\",\"arguments\":{\"repo\":\"r\",\"query\":\"q\",\"pathPrefix\":\"web/\"}}}");
        org.mockito.Mockito.verify(retrieve).searchSymbols("r", "q", 10, "web/");
    }

    @Test
    void sessionFactCarriesBranchAndSurfacesTheUnknownRepoWarning() throws Exception {
        when(session.recordFact(org.mockito.ArgumentMatchers.eq("s-9"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of("repo 'x' is not indexed"));
        JsonNode res = handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"session_fact\",\"arguments\":{\"id\":\"s-9\",\"repo\":\"x\",\"what\":\"e\",\"branch\":\"feature/x\"}}}");
        org.mockito.ArgumentCaptor<io.github.vedmider.dylext.domain.model.SessionEntry> cap =
                org.mockito.ArgumentCaptor.forClass(io.github.vedmider.dylext.domain.model.SessionEntry.class);
        org.mockito.Mockito.verify(session).recordFact(org.mockito.ArgumentMatchers.eq("s-9"), cap.capture());
        assertThat(cap.getValue().branch()).isEqualTo("feature/x");
        assertThat(res.toString()).contains("warning").contains("not indexed");
    }
}
