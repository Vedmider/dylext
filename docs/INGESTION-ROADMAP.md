# Ingestion & Repo-Meta Roadmap

Cross-app plan for Sestacon document ingestion and Dylext repo-meta (C′). Companion to
`ARCHITECTURE-THREE-APPS.md`. Status: Phase 1 + Phase 2 implemented; C′ half-1 is the repo-meta
approach (half-2 not pursued); image ingestion disabled (code retained).

## Locked scope decisions

- **Sestacon = document functionality only.** Explicit ingest/retrieve/chat over submitted
  documents. The app **never tracks a filesystem** (no watching/crawling/polling).
- **Change detection = content identity, never time.** Per-chunk hash + whole-doc hash + size.
  Pull-based: the app **computes, compares and flags**; the **AI decides** whether/what to re-ingest.
  No time flag, no background scheduler.
- **Repo structure/relations are NOT ingested.** Dylext serves folder/module structure and
  cross-module influence live (`repo_overview`, `outline`, `impact_of`). No snapshot, no duplication.
- **C′ only.** Dylext attaches agent-authored purpose/intent summaries (C′ half-1). The Sestacon
  repo-narrative layer (half-2) is **not pursued** — C′ half-1 is the chosen repo-meta approach.
- **Image ingestion disabled.** No OCR backend; image types are not allow-listed. The surrogate/OCR
  code in `TikaDocumentParser` is retained (re-enable via the allowlist + a tesseract backend).
- **No fixed meta template.** The AI decides what to capture and how to describe it (boundaries only).
- **Everything becomes:** text surrogate + typed metadata + optional pointer to the original blob,
  stored in the one `DocumentChunk` envelope (no per-source storage fork).

## SRP principle (applies throughout)

App = mechanical signal (hash/compare/flag). AI = all ingest/re-ingest decisions and all semantic
generation. Repos use Dylext HEAD/hash as the change signal; documents use per-chunk hash.

## Workstream A — Sestacon document pipeline

- **A1 Widen formats.** Allowlist + tests for Office (docx/pptx/xlsx/rtf/odt), HTML, CSV/JSON/YAML
  (naive text chunking). Already Tika-capable — mostly allowlist + per-type tests.
- **A2 Image producer — DISABLED.** Image ingestion is off (no OCR backend); image types are not
  allow-listed. The `TikaDocumentParser` surrogate code (filename surrogate when no text extractable)
  is retained for re-enablement on a tesseract host. Also hardens blank/no-text uploads generally.
- **A3 Per-chunk-hash versioning.** Store `(size, docHash, chunkHashes[])` per document. On
  (re)ingest, compare and emit a change descriptor `{status: unchanged|full|partial,
  changedSections[]}`; incremental re-ingest replaces only changed chunks. Expose a documents
  status/diff endpoint so the AI can pull the descriptor without submitting the whole file.
- **A4 Tests/probe (SX-series).** Per-format ingest+retrieve; OCR; partial re-ingest replaces only
  changed chunks; unchanged is skipped; hash stability across identical content.

## Workstream B — Dylext C′ half-1 (purpose/intent summaries)

- **B1 Summary-backed meta.** Reuse `SummaryService`/`saveSummary`: one-line purpose/intent on the
  module symbol and key symbols. Retrieved via `get_digest` (+summary). Content-hash invalidated —
  staleness is free. Structure/relations stay live in Dylext, never copied into the summary.
- **B2 "analyze-before-ingest" agent rule.** Skill/steering: the agent walks the repo via Dylext
  (`repo_overview`/`outline`/`impact_of`), fills the core template, extends where warranted,
  `verify_claims` the generated meta, then writes summaries. The agent decides scope; the app doesn't.
- **B3 No fixed template.** The AI decides what to capture and how to describe each symbol (purpose/
  intent), guided by the repo's shape — not a prescribed template. Boundaries only: notes add *why*,
  not *what/where* (Dylext serves structure live); one line per symbol. See `docs/REPO-META-SKILL.md`.
- **B4 Tests.** Summary attach/retrieve, hash invalidation on change, `verify_claims` guard rejects
  hallucinated symbols before write.

## Workstream C — Sestacon repo narrative (C′ half-2) — NOT PURSUED

Dropped: C′ half-1 (Dylext agent-authored summaries) is the chosen repo-meta approach. Repo prose
narrative in Sestacon is not built. (Sestacon remains the document layer; see workstream A.)

## Backlog (parked)

- Filesystem quick-search-with-context (the dropped file-tracking idea, as a future capability).
- Vision captions for images (upgrade from OCR-only) if photo semantics are needed.
- CSV/structured record-aware chunking (upgrade from naive text).

## Sequencing

1. **Phase 1 [DONE]:** A1 + A3 (formats + versioning — cheap, high value).
   Shipped in sestacon: A1 widened allowlist (Office/HTML/CSV/JSON/YAML) + `IngestPropertiesTest`;
   A3 per-chunk-hash versioning (`V4` migration, `DocumentVersionStore`/`PgDocumentVersionStore`,
   `DocumentDiff`, unchanged-skip, ingest `change` descriptor, `GET /api/documents/{id}/version`),
   full `mvn verify` green. Follow-up: version the `POST /api/documents` chunk path too.
2. **Phase 2 [DONE]:** A2 + B1–B4 (C′ half-1).
   - A2 (sestacon): **image ingestion disabled** (image types not allow-listed; no OCR backend). The
     `TikaDocumentParser` surrogate code (filename surrogate when no text is extractable) is retained
     and unit-tested; re-enable image/OCR by adding the image types to the allowlist on a tesseract host.
   - B (dylext): `set_summary{symbolId,text}` tool persists agent-authored purpose/intent notes as
     the symbol's `Summary`, hash-stamped (auto-invalidates on contract change), surfaced by
     `get_digest`, cache-busting. `docs/REPO-META-SKILL.md` is the analyze-before-ingest rule; **no
     fixed template — the AI decides what to capture.** Full `mvn verify` green.
3. **Phase 3:** C — **not pursued** (C′ half-1 is the chosen repo-meta approach).

## Follow-ups / open
- Image ingestion + A2 OCR verification: only if re-enabled on a tesseract-enabled host (add an OCR IT there).
