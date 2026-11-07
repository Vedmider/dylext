package com.example.contextmcp.adapter.in.mcp;

import com.example.contextmcp.domain.port.in.DynamicContextUseCase;
import com.example.contextmcp.domain.port.in.ImpactUseCase;
import com.example.contextmcp.domain.port.in.IndexUseCase;
import com.example.contextmcp.domain.port.in.LedgerUseCase;
import com.example.contextmcp.domain.port.in.RetrieveUseCase;
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
    private final com.example.contextmcp.domain.port.in.SyncUseCase sync;
    private final com.example.contextmcp.domain.port.in.FederatedContextUseCase federated;
    private final com.example.contextmcp.domain.port.in.SessionUseCase session;

    public McpDispatcher(IndexUseCase index, RetrieveUseCase retrieve, ImpactUseCase impact,
                         DynamicContextUseCase dynamic, LedgerUseCase ledger,
                         com.example.contextmcp.domain.port.in.SyncUseCase sync,
                         com.example.contextmcp.domain.port.in.FederatedContextUseCase federated,
                         com.example.contextmcp.domain.port.in.SessionUseCase session) {
        this.index = index;
        this.retrieve = retrieve;
        this.impact = impact;
        this.dynamic = dynamic;
        this.ledger = ledger;
        this.sync = sync;
        this.federated = federated;
        this.session = session;
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
        } catch (IllegalArgumentException bad) {
            return error(idNode, -32602, bad.getMessage());
        } catch (Exception e) {
            return error(idNode, -32603, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private ObjectNode initializeResult() {
        ObjectNode r = json.createObjectNode();
        r.put("protocolVersion", "2024-11-05");
        r.putObject("capabilities").putObject("tools");
        r.putObject("serverInfo").put("name", "context-mcp").put("version", "0.1.0");
        return r;
    }

    private ObjectNode toolsList() {
        ArrayNode tools = json.createArrayNode();
        tools.add(tool("outline", "File skeleton: every symbol's one-line signature.", "repo", "path"));
        tools.add(tool("search_symbols",
                "Find a symbol by known name/identifier (exact lexical). Use before recall when you have a name.",
                "repo", "query"));
        tools.add(tool("get_digest", "Skeleton (+summary if built) for one symbol.", "symbolId"));
        tools.add(tool("expand", "Raw source span for exactly one symbol. Use only after a digest.", "symbolId"));
        tools.add(tool("impact_of", "Callers and covering tests of a symbol (blast radius).", "symbolId"));
        tools.add(tool("recent_changes", "Recent commits (optionally path-filtered).", "repo"));
        tools.add(tool("working_changes", "Your UNCOMMITTED changes vs HEAD. Use when actively editing.", "repo"));
        tools.add(tool("index", "Index a repo's Java source into static context.", "repo", "root"));
        tools.add(tool("sync", "Refresh a repo's index.", "repo", "root"));
        tools.add(tool("explain", "Concept/doc answer via rag-app (what/why/which/convention). Use for NL intent.", "query"));
        tools.add(tool("recall", "Fuzzy graph-ranked code recall via code-memory (where-is, name unknown).", "query"));
        tools.add(tool("session_start", "Start a task-reflection session; returns its id.", "task"));
        tools.add(tool("session_recall", "Load a session by id (resume a task).", "id"));
        tools.add(tool("session_append", "Add a decision/finding/openThread/change/ref/repo to a session.", "id"));
        tools.add(tool("session_list", "List sessions (filter by repo/status/text).")); // no required args
        tools.add(tool("session_close", "Mark a session done.", "id"));
        tools.add(tool("session_purge", "Evict expired sessions (TTL sweep)."));  // no required args
        tools.add(tool("ledger", "Running token-savings totals.")); // no required args
        ObjectNode r = json.createObjectNode();
        r.set("tools", tools);
        return r;
    }

    private ObjectNode callTool(JsonNode params) {
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");
        Object payload = switch (name) {
            case "outline" -> retrieve.outline(req(args, "repo"), req(args, "path"));
            case "search_symbols" -> retrieve.searchSymbols(req(args, "repo"), req(args, "query"),
                    clampTopK(args, 10));
            case "get_digest" -> retrieve.getDigest(req(args, "symbolId"));
            case "expand" -> retrieve.expand(req(args, "symbolId"));
            case "impact_of" -> impact.impactOf(req(args, "symbolId"));
            case "recent_changes" -> dynamic.recentChanges(req(args, "repo"),
                    args.path("path").asText(null), clampLimit(args, 10));
            case "working_changes" -> dynamic.workingChanges(req(args, "repo"),
                    args.path("path").asText(null));
            case "index" -> index.index(req(args, "repo"), req(args, "root"));
            case "sync" -> sync.sync(req(args, "repo"), req(args, "root"));
            case "explain" -> federated.explain(req(args, "query"),
                    args.path("roles").asText(null), clampTopK(args, 3));
            case "recall" -> federated.recall(req(args, "query"), clampTopK(args, 5));
            case "session_start" -> session.start(req(args, "task"));
            case "session_recall" -> session.recall(req(args, "id")).orElse(null);
            case "session_list" -> session.list(args.path("repo").asText(null),
                    parseStatus(args.path("status").asText(null)),
                    args.path("text").asText(null), clampTopK(args, 50));
            case "session_close" -> session.close(req(args, "id"));
            case "session_append" -> session.append(req(args, "id"), parseAppend(args));
            case "session_purge" -> java.util.Map.of("purged", session.purge());
            case "ledger" -> ledger.totals();
            default -> throw new IllegalArgumentException("unknown tool: " + name);
        };
        ObjectNode result = json.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode item = content.addObject();
        item.put("type", "text");
        item.set("json", json.valueToTree(payload));
        result.set("tokens", json.valueToTree(ledger.totals()));
        return result;
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

    private com.example.contextmcp.domain.model.Session.Status parseStatus(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return com.example.contextmcp.domain.model.Session.Status.valueOf(s.toUpperCase());
    }

    private com.example.contextmcp.domain.port.in.SessionUseCase.Append parseAppend(JsonNode args) {
        return new com.example.contextmcp.domain.port.in.SessionUseCase.Append(
                entry(args.get("decision")),
                entry(args.get("finding")),
                entry(args.get("openThread")),
                change(args.get("change")),
                ref(args.get("ref")),
                args.path("repo").asText(null));
    }

    private static com.example.contextmcp.domain.model.Session.Entry entry(JsonNode n) {
        return n == null || n.isNull() ? null
                : new com.example.contextmcp.domain.model.Session.Entry(
                        n.path("what").asText(""), n.path("why").asText(""));
    }

    private static com.example.contextmcp.domain.model.Session.Change change(JsonNode n) {
        return n == null || n.isNull() ? null
                : new com.example.contextmcp.domain.model.Session.Change(
                        n.path("repo").asText(null), n.path("file").asText(null),
                        n.path("symbol").asText(null), n.path("what").asText(""),
                        n.path("why").asText(""));
    }

    private static com.example.contextmcp.domain.model.Session.Ref ref(JsonNode n) {
        return n == null || n.isNull() ? null
                : new com.example.contextmcp.domain.model.Session.Ref(
                        n.path("kind").asText(""), n.path("value").asText(""));
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
