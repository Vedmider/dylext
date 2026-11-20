package io.github.vedmider.dylext.adapter.in.mcp;

import io.github.vedmider.dylext.domain.port.in.DynamicContextUseCase;
import io.github.vedmider.dylext.domain.port.in.ImpactUseCase;
import io.github.vedmider.dylext.domain.port.in.IndexUseCase;
import io.github.vedmider.dylext.domain.port.in.LedgerUseCase;
import io.github.vedmider.dylext.domain.port.in.RetrieveUseCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Minimal JSON-RPC 2.0 dispatcher exposing context tools. Transport-agnostic: {@link #handle}
 * maps a request string to a response string, so it is testable without stdio wiring.
 * Supports {@code initialize}, {@code tools/list}, {@code tools/call}.
 */
@Component
public class McpDispatcher {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final IndexUseCase index;
    private final RetrieveUseCase retrieve;
    private final ImpactUseCase impact;
    private final DynamicContextUseCase dynamic;
    private final LedgerUseCase ledger;
    private final io.github.vedmider.dylext.domain.port.in.SyncUseCase sync;
    private final io.github.vedmider.dylext.domain.port.in.SessionUseCase session;
    private final io.github.vedmider.dylext.domain.port.in.VerifyUseCase verify;
    private final io.github.vedmider.dylext.domain.port.in.RepoCatalogUseCase catalog;
    private final boolean epochEnabled;
    private final io.github.vedmider.dylext.application.ResponseCache cache;
    private final io.github.vedmider.dylext.domain.port.in.SignalUseCase signals;
    private final io.github.vedmider.dylext.domain.port.in.AnnotateUseCase annotate;

    /** Read tools whose result is tied to a specific repo epoch (cache key + freshness tag). */
    private static final java.util.Set<String> EPOCH_TOOLS = java.util.Set.of(
            "outline", "repo_overview", "search_symbols", "get_digest", "expand", "impact_of",
            "recent_changes", "working_changes", "index", "sync", "verify_claims");

    /** Deterministic reads cacheable by (tool, args, committed epoch). Excludes mutations and dirty/live reads. */
    private static final java.util.Set<String> CACHEABLE_TOOLS = java.util.Set.of(
            "outline", "repo_overview", "search_symbols", "get_digest", "expand", "impact_of",
            "recent_changes");

    /** Mutations whose effect the epoch (git HEAD) cannot capture — they must flush the read cache. */
    private static final java.util.Set<String> MUTATION_TOOLS = java.util.Set.of(
            "index", "sync", "ingest_signals", "set_summary");

    public McpDispatcher(IndexUseCase index, RetrieveUseCase retrieve, ImpactUseCase impact,
                         DynamicContextUseCase dynamic, LedgerUseCase ledger,
                         io.github.vedmider.dylext.domain.port.in.SyncUseCase sync,
                         io.github.vedmider.dylext.domain.port.in.SessionUseCase session,
                         io.github.vedmider.dylext.domain.port.in.VerifyUseCase verify,
                         io.github.vedmider.dylext.domain.port.in.RepoCatalogUseCase catalog,
                         @org.springframework.beans.factory.annotation.Value(
                                 "${dylext.epoch.enabled:true}") boolean epochEnabled,
                         io.github.vedmider.dylext.application.ResponseCache cache,
                         io.github.vedmider.dylext.domain.port.in.SignalUseCase signals,
                         io.github.vedmider.dylext.domain.port.in.AnnotateUseCase annotate) {
        this.index = index;
        this.retrieve = retrieve;
        this.impact = impact;
        this.dynamic = dynamic;
        this.ledger = ledger;
        this.sync = sync;
        this.session = session;
        this.verify = verify;
        this.catalog = catalog;
        this.epochEnabled = epochEnabled;
        this.cache = cache;
        this.signals = signals;
        this.annotate = annotate;
    }

    /** @return the JSON-RPC response string, or null for a notification (no id). */
    public String handle(String requestJson) {
        JsonNode req;
        try {
            req = json.readTree(requestJson);
        } catch (Exception e) {
            return error(null, -32700, "parse error");
        }
        JsonNode idNode = req.get("id");
        String method = req.path("method").asText("");
        try {
            return switch (method) {
                case "initialize" -> ok(idNode, initializeResult());
                case "tools/list" -> ok(idNode, toolsList());
                case "tools/call" -> ok(idNode, callTool(req.path("params")));
                case "" -> error(idNode, -32600, "invalid request: missing method");
                default -> idNode == null ? null : error(idNode, -32601, "method not found: " + method);
            };
        } catch (IllegalArgumentException | java.util.NoSuchElementException bad) {
            // unknown repo/symbol/session ids are caller mistakes, not server faults
            return error(idNode, -32602, bad.getMessage());
        } catch (Exception e) {
            return error(idNode, -32603, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private ObjectNode initializeResult() {
        ObjectNode r = json.createObjectNode();
        r.put("protocolVersion", "2024-11-05");
        r.putObject("capabilities").putObject("tools");
        r.putObject("serverInfo").put("name", "dylext").put("version", "0.2.0");
        return r;
    }

    private ObjectNode toolsList() {
        ArrayNode tools = json.createArrayNode();
        tools.add(tool("list_repos", "Indexed repos with root, branch, head, file and symbol counts."));
        tools.add(tool("repo_overview", "Languages, modules (maven/gradle/go/node/...) and top-level folders of an indexed repo.", "repo"));
        tools.add(tool("languages", "Languages Dylext can parse and how (in-process or sidecar); unavailable ones are not indexed."));
        tools.add(tool("outline", "File skeleton: every symbol's one-line signature.", "repo", "path"));
        tools.add(tool("search_symbols",
                "Find a symbol by name/identifier (fuzzy token match — a near or partial name also hits). "
                        + "Optional pathPrefix (e.g. 'web/') limits it to one module/folder.",
                "repo", "query"));
        tools.add(tool("get_digest", "Skeleton (+summary if built) for one symbol.", "symbolId"));
        tools.add(tool("set_summary",
                "Attach an agent-authored purpose/intent note to a symbol (surfaced by get_digest). "
                + "Stamped with the symbol's current content hash, so it auto-invalidates when the code "
                + "changes. Use after analyzing the repo. Args: symbolId, text.",
                "symbolId", "text"));
        tools.add(tool("expand", "Raw source span for exactly one symbol. Use only after a digest.", "symbolId"));
        tools.add(tool("impact_of",
                "Callers and covering tests of a symbol (blast radius). Optional limit caps callers to the top-N "
                + "(after signal ranking) and sets truncated + totalCallers for large blast radii.", "symbolId"));
        tools.add(tool("recent_changes", "Recent commits (optionally path-filtered).", "repo"));
        tools.add(tool("working_changes", "Your UNCOMMITTED changes vs HEAD. Use when actively editing.", "repo"));
        tools.add(tool("index", "Index a repo's Java source into static context.", "repo", "root"));
        tools.add(tool("sync", "Refresh a repo's index.", "repo", "root"));
        tools.add(tool("verify_claims",
                "Ground an answer's code references against the symbol index: each cited symbol is marked VERIFIED (with its real signature+location) or UNVERIFIED (not found). Use to catch hallucinated code in an LLM/doc answer.",
                "answer", "repo"));
        tools.add(tool("ingest_signals",
                "Push runtime signals (per-symbol coverage + call frequency) that weight impact_of ranking: hot-and-untested callers rank first. Args: repo, signals[{symbolId, coverage, callFrequency}].",
                "repo", "signals"));
        tools.add(tool("session_start", "Start a task-reflection session; returns its id.", "task"));
        tools.add(tool("session_fact",
                "Stream ONE cheap factual edit (what changed: repo/file/symbol). Bounded ring buffer. Mechanical, no why.",
                "id", "what"));
        tools.add(tool("session_commit",
                "Batch durable decision-layer entries at a checkpoint: entries[]{type(decision|finding|next),what,why}. Never evicted. NOT per message.",
                "id", "entries"));
        tools.add(tool("session_recall",
                "Load a session by id. Default: all decisions + newest facts only. full=true for everything; sections=[decisions|facts] to pick layers.",
                "id"));
        tools.add(tool("session_list", "List session headers (id/task/status/counts) — filter by repo/status/text. No bodies.")); // no required args
        tools.add(tool("session_close", "Mark a session done.", "id"));
        tools.add(tool("session_purge", "Evict expired sessions (TTL sweep)."));  // no required args
        tools.add(tool("ledger", "Running token-savings totals.")); // no required args
        tools.add(tool("savings",
                "Combined token-savings summary: total saved, savedPercent, and the per-tool "
                        + "breakdown for the local code-context tools.")); // no required args
        ObjectNode r = json.createObjectNode();
        r.set("tools", tools);
        return r;
    }

    private ObjectNode callTool(JsonNode params) {
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");
        io.github.vedmider.dylext.domain.model.EpochRef epoch = epochEnabled ? epochFor(name, args) : null;
        String cacheKey = cacheKey(name, args, epoch);

        JsonNode payloadJson;
        Boolean cached = cacheKey == null ? null : Boolean.FALSE;
        java.util.Optional<String> hit = cache.get(cacheKey);
        if (hit.isPresent()) {
            payloadJson = readTree(hit.get());
            cached = Boolean.TRUE;
        } else {
            payloadJson = json.valueToTree(computePayload(name, args));
            if (cacheKey != null) {
                cache.put(cacheKey, payloadJson.toString());
            }
        }
        if (MUTATION_TOOLS.contains(name)) {
            cache.invalidateAll();   // a state change the epoch can't see (e.g. signals) must bust reads
        }

        ObjectNode result = json.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode item = content.addObject();
        item.put("type", "text");
        item.set("json", payloadJson);
        result.set("tokens", json.valueToTree(ledger.totals()));
        if (epoch != null) {
            result.set("epoch", json.valueToTree(epoch));
        }
        if (cached != null) {
            result.put("cached", cached);
        }
        return result;
    }

    private Object computePayload(String name, JsonNode args) {
        return switch (name) {
            case "outline" -> retrieve.outline(req(args, "repo"), req(args, "path"));
            case "list_repos" -> catalog.listRepos();
            case "repo_overview" -> catalog.overview(req(args, "repo"));
            case "languages" -> catalog.languages();
            case "search_symbols" -> searchSymbols(args);
            case "get_digest" -> retrieve.getDigest(req(args, "symbolId"));
            case "set_summary" -> annotate.annotate(req(args, "symbolId"), req(args, "text"));
            case "expand" -> retrieve.expand(req(args, "symbolId"));
            case "impact_of" -> impact.impactOf(req(args, "symbolId"), args.path("limit").asInt(0));
            case "recent_changes" -> dynamic.recentChanges(req(args, "repo"),
                    args.path("path").asText(null), clampLimit(args, 10));
            case "working_changes" -> dynamic.workingChanges(req(args, "repo"),
                    args.path("path").asText(null));
            case "index" -> index.index(req(args, "repo"), req(args, "root"));
            case "sync" -> sync.sync(req(args, "repo"), req(args, "root"));
            case "verify_claims" -> verify.verify(req(args, "answer"), req(args, "repo"));
            case "ingest_signals" -> signals.ingest(req(args, "repo"), parseSignals(args));
            case "session_start" -> session.start(req(args, "task"));
            case "session_fact" -> {
                java.util.Optional<String> warning = session.recordFact(req(args, "id"), parseFact(args));
                java.util.Map<String, Object> ack = new java.util.LinkedHashMap<>();
                ack.put("ok", true);
                warning.ifPresent(w -> ack.put("warning", w));
                yield ack;
            }
            case "session_commit" -> session.commit(req(args, "id"), parseEntries(args)).header();
            case "session_recall" -> recallView(session.recall(req(args, "id")).orElse(null), args);
            case "session_list" -> session.list(args.path("repo").asText(null),
                    parseStatus(args.path("status").asText(null)),
                    args.path("text").asText(null), clampTopK(args, 50));
            case "session_close" -> session.close(req(args, "id"));
            case "session_purge" -> java.util.Map.of("purged", session.purge());
            case "ledger" -> ledger.totals();
            case "savings" -> savingsSummary();
            default -> throw new IllegalArgumentException("unknown tool: " + name);
        };
    }

    private JsonNode readTree(String jsonText) {
        try {
            return json.readTree(jsonText);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("corrupt cache entry", e);
        }
    }

    /** Cache key for a deterministic repo read, or null when the result must not be cached. */
    private String cacheKey(String name, JsonNode args, io.github.vedmider.dylext.domain.model.EpochRef epoch) {
        if (!cache.enabled() || !CACHEABLE_TOOLS.contains(name) || epoch == null || epoch.dirty()) {
            return null;
        }
        java.util.List<String> fields = new java.util.ArrayList<>();
        args.fieldNames().forEachRemaining(fields::add);
        java.util.Collections.sort(fields);
        StringBuilder sb = new StringBuilder(name).append('|');
        for (String f : fields) {
            sb.append(f).append('=').append(args.path(f).asText()).append(';');
        }
        return sb.append('|').append(epoch.token()).toString();
    }

    /** The repo epoch this call's result is tied to, or null for non-repo tools / unresolvable repo. */
    private io.github.vedmider.dylext.domain.model.EpochRef epochFor(String name, JsonNode args) {
        if (!EPOCH_TOOLS.contains(name)) {
            return null;
        }
        String repo = args.path("repo").asText(null);
        if (repo == null || repo.isBlank()) {
            String symbolId = args.path("symbolId").asText(null);
            int colon = symbolId == null ? -1 : symbolId.indexOf(':');
            repo = colon > 0 ? symbolId.substring(0, colon) : null;
        }
        if (repo == null || repo.isBlank()) {
            return null;
        }
        io.github.vedmider.dylext.domain.model.EpochRef base = catalog.epochFor(repo);
        if (base == null) {
            return null;
        }
        return "working_changes".equals(name) ? base.asDirty() : base;
    }

    /** Combined savings summary (I-015): ledger totals enriched with saved + savedPercent + per-tool breakdown. */
    private ObjectNode savingsSummary() {
        var t = ledger.totals();
        ObjectNode o = json.createObjectNode();
        o.put("calls", t.calls());
        o.put("tokensServed", t.tokensServed());
        o.put("baselineTokens", t.baselineTokens());
        o.put("saved", t.saved());
        o.put("savedPercent", Math.round(t.savedPercent() * 10.0) / 10.0);
        o.set("byTool", json.valueToTree(t.byTool()));
        o.set("cache", json.valueToTree(cache.stats()));
        return o;
    }

    private ObjectNode tool(String name, String description, String... requiredArgs) {
        ObjectNode t = json.createObjectNode();
        t.put("name", name);
        t.put("description", description);
        ObjectNode schema = t.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ArrayNode required = schema.putArray("required");
        for (String arg : requiredArgs) {
            props.putObject(arg).put("type", "string");
            required.add(arg);
        }
        return t;
    }

    private static final int MAX_FANOUT = 50;
    private static final int RECALL_FACTS_DEFAULT = 20;

    private io.github.vedmider.dylext.domain.model.Session.Status parseStatus(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return io.github.vedmider.dylext.domain.model.Session.Status.valueOf(s.toUpperCase());
    }

    private java.util.List<io.github.vedmider.dylext.domain.model.SymbolSignal> parseSignals(JsonNode args) {
        JsonNode arr = args.get("signals");
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            throw new IllegalArgumentException("missing required argument: signals (non-empty array)");
        }
        java.util.List<io.github.vedmider.dylext.domain.model.SymbolSignal> out = new java.util.ArrayList<>();
        for (JsonNode n : arr) {
            String id = n.path("symbolId").asText(null);
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("each signal needs a symbolId");
            }
            out.add(io.github.vedmider.dylext.domain.model.SymbolSignal.of(
                    id, n.path("coverage").asDouble(0.0), n.path("callFrequency").asDouble(0.0)));
        }
        return out;
    }

    private io.github.vedmider.dylext.domain.model.SessionEntry parseFact(JsonNode args) {
        return io.github.vedmider.dylext.domain.model.SessionEntry.fact(
                args.path("repo").asText(null), args.path("file").asText(null),
                args.path("symbol").asText(null), req(args, "what"), args.path("branch").asText(null));
    }

    private java.util.List<io.github.vedmider.dylext.domain.model.SessionEntry> parseEntries(JsonNode args) {
        JsonNode arr = args.get("entries");
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            throw new IllegalArgumentException("missing required argument: entries (non-empty array)");
        }
        java.util.List<io.github.vedmider.dylext.domain.model.SessionEntry> out = new java.util.ArrayList<>();
        for (JsonNode n : arr) {
            out.add(decisionEntry(n));
        }
        return out;
    }

    private static io.github.vedmider.dylext.domain.model.SessionEntry decisionEntry(JsonNode n) {
        String what = n.path("what").asText("");
        if (what.isBlank()) {
            throw new IllegalArgumentException("each committed entry requires 'what'");
        }
        io.github.vedmider.dylext.domain.model.SessionEntry.Type type =
                parseType(n.path("type").asText(null));
        return new io.github.vedmider.dylext.domain.model.SessionEntry(
                type, what, n.path("why").asText(null),
                n.path("repo").asText(null), n.path("file").asText(null),
                n.path("symbol").asText(null), null, n.path("branch").asText(null));
    }

    private static io.github.vedmider.dylext.domain.model.SessionEntry.Type parseType(String s) {
        if (s == null || s.isBlank()) {
            return io.github.vedmider.dylext.domain.model.SessionEntry.Type.DECISION;
        }
        return io.github.vedmider.dylext.domain.model.SessionEntry.Type.valueOf(s.toUpperCase());
    }

    /** Tiered read: default = all decisions + newest facts; full=true = everything; sections picks layers. */
    private Object recallView(io.github.vedmider.dylext.domain.model.Session s, JsonNode args) {
        if (s == null) {
            return null;
        }
        boolean full = args.path("full").asBoolean(false);
        java.util.Set<String> sections = new java.util.HashSet<>();
        JsonNode sectionsNode = args.get("sections");
        if (sectionsNode != null && sectionsNode.isArray()) {
            sectionsNode.forEach(x -> sections.add(x.asText("").toLowerCase()));
        }
        boolean wantDecisions = sections.isEmpty() || sections.contains("decisions");
        boolean wantFacts = sections.isEmpty() || sections.contains("facts");

        java.util.List<io.github.vedmider.dylext.domain.model.SessionEntry> decisions =
                wantDecisions ? s.decisions() : java.util.List.of();
        java.util.List<io.github.vedmider.dylext.domain.model.SessionEntry> facts;
        if (!wantFacts) {
            facts = java.util.List.of();
        } else if (full) {
            facts = s.facts();
        } else {
            int n = s.facts().size();
            facts = n <= RECALL_FACTS_DEFAULT ? s.facts()
                    : s.facts().subList(n - RECALL_FACTS_DEFAULT, n);
        }
        return new io.github.vedmider.dylext.domain.model.Session(
                s.id(), s.task(), s.status(), s.repos(), decisions, facts, s.updatedAt());
    }

    private Object searchSymbols(JsonNode args) {
        String prefix = args.path("pathPrefix").asText(null);
        return prefix == null || prefix.isBlank()
                ? retrieve.searchSymbols(req(args, "repo"), req(args, "query"), clampTopK(args, 10))
                : retrieve.searchSymbols(req(args, "repo"), req(args, "query"), clampTopK(args, 10), prefix);
    }

    private static int clampTopK(JsonNode args, int fallback) {
        return clamp(args.path("topK").asInt(fallback), fallback);
    }

    private static int clampLimit(JsonNode args, int fallback) {
        return clamp(args.path("limit").asInt(fallback), fallback);
    }

    private static int clamp(int requested, int fallback) {
        if (requested <= 0) {
            return fallback;
        }
        return Math.min(requested, MAX_FANOUT);
    }

    private static String req(JsonNode args, String field) {
        JsonNode v = args.get(field);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            throw new IllegalArgumentException("missing required argument: " + field);
        }
        return v.asText();
    }

    private String ok(JsonNode id, JsonNode result) {
        ObjectNode r = json.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id == null ? null : id);
        r.set("result", result);
        return r.toString();
    }

    private String error(JsonNode id, int code, String message) {
        ObjectNode r = json.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id == null ? null : id);
        ObjectNode err = r.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return r.toString();
    }
}
