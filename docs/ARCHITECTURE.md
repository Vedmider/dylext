# Architecture — the gateway and its two context providers

This document explains how **the gateway**, **Dylext**, and **Sestacon** compose into one
context-retrieval system for AI-assisted development: what each owns, how an AI agent reaches them, and
how data flows end to end.

Guiding principle: **find cheap and conceptual first, read exact bytes only when needed** — and
**one ingress** mediates everything. The **gateway** is the single front door; Dylext and Sestacon are
two independent provider modules behind it. Neither provider calls the other (single responsibility):
the gateway routes each request to the backend that owns it.

---

## 1. The components at a glance

| Component | Role | Owns | Strongest question |
|---|---|---|---|
| **Gateway** | Single ingress / router | routing, auth (loopback), trace + telemetry, retries/circuit-breaker | — (mediates all traffic) |
| **Dylext** | Dynamic code context provider | exact code structure + live git state | "jump to NAME / show its body / what did I just change / what calls this" |
| **Sestacon** | Static doc context provider | prose: docs, skills, conventions (RAG) | "what / why / which / explain" |

Dylext is *dynamic* (it reflects uncommitted edits and moving git state); Sestacon is *static*
(ingested documents). The AI decides which it needs and asks the **gateway**; the gateway propagates to
the right provider. **Dylext does not call Sestacon** — that cross-call was removed because it violated
single responsibility and bypassed the ingress.

```
                        ┌───────────────────────────────────────┐
   AI agent ──MCP────▶  │                GATEWAY (:18070)         │
   (one ingress)        │  route · loopback auth · trace ·        │
   (+ REST for docs)    │  telemetry · retries · circuit-breaker  │
                        └───┬───────────────────────┬─────────────┘
                        /mcp│                    /api│
                            ▼                        ▼
                     Dylext (:18090)          Sestacon (:18080)
                     dynamic code + git        static docs / RAG
```

---

## 2. Why two providers behind a gateway

Each provider's central bet is different and would compromise the other if merged:
- **Sestacon** optimizes semantic recall over *prose* (real embeddings; doc-RAG; one chunking strategy
  tuned for text).
- **Dylext** optimizes *tokens per known-target lookup* and *live-edit awareness*: symbol-exact
  `expand` (never a whole file), `working_changes` (uncommitted diff vs HEAD), a call graph for
  `impact_of`, and a token-savings ledger.

Collapsing them would force one embedding model + one chunking strategy to serve prose and exact code
spans at once. Keeping them apart preserves each bet — and the **gateway** is what lets them stay
apart: it owns cross-cutting concerns (routing, auth, tracing, resilience) so neither provider knows
about the other. Providers are leaves; the gateway is the only router. (A former third provider,
*code-memory* for fuzzy recall, was dropped — it overlapped Dylext's index and Sestacon's retrieval
engine and added a provider without a distinct, proven bet.)

---

## 3. The surface the AI sees (through the gateway)

The AI talks to **one ingress, the gateway**:
- **Code / git (MCP)** → `POST http://localhost:18070/mcp` (JSON-RPC) → routed to **Dylext**.
  Tools: `outline`, `search_symbols`, `get_digest`, `expand`, `impact_of`, `recent_changes`,
  `working_changes`, `index`, `sync`, `verify_claims`, `session_*`, `ledger`, `savings`.
- **Docs (REST)** → `POST http://localhost:18070/api/retrieve` (and `/api/ingest`, `/api/chat`) →
  routed to **Sestacon**.

Dylext exposes **only its own code/git tools** — there is no `explain`/`recall`/`answer` tool in
Dylext. Conceptual/doc retrieval is a *Sestacon* call the AI makes through the gateway. Each hop is a
gateway hop, so it is authenticated (loopback-only), traced (`X-Trace-Id` propagated and logged by
every service), metered, and protected by retries + a per-provider circuit breaker.

---

## 4. How an AI should route a question (decision table)

Pick the cheapest backend that can answer; descend to exact bytes only when needed. All calls go to the
gateway (`:18070`).

| The question is… | Call (via gateway) | Backend |
|---|---|---|
| concept / "what / why / which / convention" | `POST /api/retrieve {query}` | Sestacon |
| known identifier / "jump to NAME", a file's symbols | `search_symbols` / `outline` (MCP) | Dylext |
| read one symbol's body | `get_digest` → `expand` (MCP) | Dylext |
| blast radius / callers + covering tests | `impact_of(symbolId)` (MCP) | Dylext |
| live edits / "what am I changing right now" | `working_changes(repo)` (MCP) | Dylext |
| history / "what changed recently" | `recent_changes(repo)` (MCP) | Dylext |
| is a cited symbol real (anti-hallucination) | `verify_claims(answer, repo)` (MCP) | Dylext |
| how many code-context tokens am I saving | `ledger` / `savings` (MCP) | Dylext |

Heuristics:
- Exact identifier? Go straight to `search_symbols` — don't spend a doc query.
- NL intent, no symbol name yet? Ask Sestacon (`/api/retrieve`) for the concept; let the hit name a
  symbol; **then** `search_symbols` → `expand` for the exact bytes.
- Actively editing? `working_changes()` before reading committed code.

Progressive disclosure, always: `search_symbols`/`outline` → `get_digest` → `expand`. A digest is
signatures only and answers most structural questions without a body.

---

## 5. End-to-end flow (the daily loop)

```
concept ─ /api/retrieve ───▶ Sestacon names a capability/symbol ("rate limiting" → RateLimitInterceptor)
            │
exact  ─ search_symbols ───▶ Dylext → get_digest → expand  (minimal tokens, the real span)
            │
impact ─ impact_of ────────▶ Dylext callers + tests (what you might break)
            │
edit   ─ working_changes ──▶ Dylext shows what you're touching right now
```

Every step is one gateway hop carrying the same `X-Trace-Id`, so the whole task is correlatable in the
gateway, Dylext, and Sestacon logs.

---

## 6. Data flow & boundaries

- **Static code context** (structure) is indexed by path, keyed by content hash (incremental), in
  Dylext (Java + sidecar languages).
- **Dynamic context** (git history + uncommitted working tree) is computed on read in Dylext, never
  cached stale — so `working_changes` reflects edits not yet committed.
- **Document context** (prose) lives only in Sestacon, embedded with a real model; it is never
  duplicated into Dylext. The gateway fetches it from Sestacon on demand.
- **No leaf-to-leaf calls.** Neither provider reads the other's store or calls its API. The gateway is
  the only component that talks to both.

Configuration: the gateway holds the provider upstreams (`gateway.providers.dylext-url`,
`sestacon-url`). Dylext has **no** federation config — it is a standalone provider.

### Consistency model (PACELC)

The two providers make opposite, deliberate consistency choices:

- **Dylext — PACELC "EC" (consistency over latency).** Every static read runs a freshness-on-read gate:
  if git HEAD moved past the indexed cursor it syncs first, so reads are tagged with — and computed
  against — the current commit (the `epoch` on every response). This is read-your-writes after a commit
  at the cost of a HEAD check (and an occasional sync) per read. `working_changes` is always `dirty`
  (live working tree, never cacheable); the response cache keys on live HEAD so a commit always busts it.
- **Sestacon — AP-leaning (availability/eventual consistency).** A document is retrievable once ingest
  completes; there is no read-your-writes guarantee across an in-flight ingest, and retrieval stays
  available rather than blocking for strict consistency.

The gateway itself is stateless and makes no consistency promise beyond faithfully forwarding each call
to the provider that owns the data.

---

## 7. Deployment shape (local, single developer)

```
AI agent ──MCP /mcp──┐
                     ▼
                GATEWAY (:18070)  ──/mcp──▶ Dylext (:18090, java -jar)
  docs client ──/api─┘           ──/api──▶ Sestacon (:18080) + pgvector + redis
```

- The gateway is thin and stateless (no DB); bind loopback-only. Providers run as containers or
  `java -jar`. Pure code work needs the gateway + Dylext; add Sestacon for docs.
- Lifecycle (tester): `bash agents/tester/{gateway,dylext,parsers,sestacon}.sh`.

---

## 8. Honest limitations

- Sestacon's KB in dev is ephemeral (wiped on restart) — ingest before retrieving.
- The gateway is a single ingress, so it is a shared dependency; it is stateless and horizontally
  scalable, and its circuit breaker keeps a flapping provider from cascading.
- `answer` (merging code + docs into one response) is **not** a gateway tool — the AI composes code
  (Dylext) and docs (Sestacon) itself; gateway-side composition is a possible future step.

---

## 9. Session memory (AI task reflection)

The AI's working memory for a **task** is a session, kept in **Dylext** in an isolated store (own
table/port/tools, never mixed with code context): a durable `decisions[]` log (typed
decision/finding/next, each with a `why`, batched at checkpoints, never auto-evicted) and a cheap
`facts[]` ring buffer. Verbs: `session_fact`, `session_commit`; reads `session_list` (headers) and
`session_recall` (decisions + newest facts); plus `session_start`/`session_close`/`session_purge`.
See `SKILL.md` → "Session memory".

## See also

`AI-USAGE.md` (terse routing guide mirrored into tool descriptions), `ARCHITECTURE-ROADMAP.md`
(where the ecosystem is going), and `EVIDENCE.md` (token-savings measurements).
