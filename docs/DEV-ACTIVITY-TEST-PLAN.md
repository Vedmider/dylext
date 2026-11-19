# Developer-activity test plan (gap finding)

**Goal:** find gaps in Dylext by replaying what developers actually do in a repository — from
the first minutes after downloading it, through daily branch/commit/pull work — and checking that
Dylext's three layers (static index, live git view, session memory) stay truthful and helpful.

**Method:** black-box. A harness (`e2e/dev_activity.py`) drives the real MCP server against real
temporary git repos. Every case states the **desired** behaviour (the oracle). Where Dylext falls
short the case is a **GAP**, reported with the observed evidence. Gaps are *identified* by this suite;
each GAP becomes a bug fixed test-first. Current state: all 61 cases PASS (see `DEV-ACTIVITY-FINDINGS.md`).

**Statuses:** `PASS` (desired behaviour holds) · `GAP` (it does not) · `ERROR` (harness problem —
must be investigated, never reported as a gap) · `SKIP` (environment can't run it).
**Severity:** `H` wrong/silent answers or a run aborting · `M` misleading or missing capability ·
`L` nicety. **Basis:** `R` regression guard (believed to work) · `V` verified in an earlier probe ·
`H?` hypothesis from reading the code.

Model facts the cases rely on: the index is keyed by **repo name** and mirrors **one HEAD** (one
sync cursor per repo; no branch dimension); reads re-sync only when HEAD moves; `working_changes`
is live git (a snapshot); only `index` (not `sync`) picks up uncommitted code; Dylext is
pull-based and never watches files.

## O — First contact with a freshly downloaded repo

| ID | Developer does | Desired Dylext behaviour | Sev | Basis |
|---|---|---|---|---|
| O01 | asks for symbols in a repo never indexed | actionable `-32602 … call index`, never a silent empty result | H | H? |
| O02 | asks for a file outline in a never-indexed repo | same actionable error | H | H? |
| O03 | asks the unified `answer` about a never-indexed repo | code side reported unavailable, not "available, 0 results" | M | H? |
| O04 | indexes the fresh clone, then indexes again | correct counts; second run skips unchanged files | – | R |
| O05 | repo contains only languages with no parser (.ts/.rs/.md) | index says files were ignored for unsupported language, not a silent 0/0 | M | H? |
| O06 | mixed repo but the Python parser is not configured | index reports files skipped for lack of a parser | H | H? |
| O07 | parser configured but its sidecar is down | index reports degraded (unparsed) files | H | H? |
| O08 | asks "which repos are indexed / which languages work?" | a way to list indexed repos and supported languages | M | H? |
| O09 | asks "what is in this repo?" with no file known yet | repo overview/map, or outline of `.` returns something | M | H? |
| O10 | looks for the entry point (`main`) | found with file location | – | R |
| O11 | downloaded a ZIP (no `.git`) | index works; git tools answer gracefully, no internal error | M | H? |
| O12 | shallow clone (`--depth 1`), later pulls | history tool works; pull re-syncs | – | R |
| O13 | indexes only the `src/` folder of a git repo | git tools still work (git root found upward) | M | H? |
| O14 | passes a relative root path | rejected with a clear error (client cannot know server cwd) | L | H? |
| O15 | typo: root directory does not exist | `-32602` with a clear message | M | H? |
| O16 | root points at a file | `-32602` | L | H? |
| O17 | re-uses a repo name for a different checkout | rejected/warned or old symbols purged — no orphans | M | H? |
| O18 | first index of a large repo | within a client timeout, or progress/async (projected 20k files ≤ 120 s) | M | V |
| O19 | asks a docs question while the doc backend is unreachable | response distinguishes "unreachable" from "no hits" | M | H? |
| O20 | indexes only `src/`, later commits a new method | freshness re-sync still works (symbol searchable after the commit) | H | triage of O13 |

## B — Branches & pull (a developer switches branches and pulls)

| ID | Developer does | Desired Dylext behaviour | Sev | Basis |
|---|---|---|---|---|
| B01 | switches feature ↔ main repeatedly (feature adds, modifies, deletes files) | index follows HEAD correctly in both directions, every time | H | H? |
| B02 | `git pull` fast-forward on current branch | new upstream symbols searchable on next read | H | R |
| B03 | `git pull` with divergent local/remote commits (merge commit) | both local and upstream symbols present | M | H? |
| B04 | `git pull --rebase` with local commits | both present, nothing stale | M | H? |
| B05 | switches to another branch, then pulls there | index reflects that branch's pulled state | M | H? |
| B06 | pulls upstream commits that delete and rename files | deleted symbols gone, renamed file indexed under new path | M | H? |
| B07 | switches branch carrying uncommitted edits | `working_changes` is relative to the new HEAD; index follows new HEAD | M | H? |
| B08 | rebases a feature branch onto updated main | index shows both sets of changes | M | H? |
| B09 | checks out an older commit (detached HEAD) | symbols from later commits disappear | M | H? |
| B10 | wonders which branch the symbols reflect | some tool exposes branch/HEAD the index mirrors | M | H? |
| B11 | switches between two very different branches | re-sync after a switch stays interactive (≤ 15 s for 300 changed files) | M | H? |
| B12 | records session facts while on different branches | facts carry the branch they were made on | L | H? |

## M — Multi-module repositories (Maven-style: `core` + `web`; `web` calls `core`)

| ID | Developer does | Desired Dylext behaviour | Sev | Basis |
|---|---|---|---|---|
| M01 | indexes the whole multi-module repo at its root | symbols of every module found; ids carry the module directory | – | R |
| M02 | has the same class name in two modules | both found and distinguishable by module path | M | R |
| M03 | a class in `web` calls a method in `core` | `impact_of` lists the cross-module caller | M | R |
| M04 | edits and commits inside one module (repo indexed at the repo root) | `working_changes` shows the module path; the commit is searchable | – | R |
| M05 | indexes each module as its own repo (root = module dir) | `working_changes` scoped to the module, paths module-relative | H | H? |
| M06 | commits in one module while modules are separate repos | each module repo re-syncs on its own changes; no leakage between modules | H | H? |
| M07 | has a module `target/` build directory (gitignored) | build output not indexed | H | H? |
| M08 | asks "which modules does this repo have?" | an overview lists modules (pom.xml / go.mod / package.json directories) | M | H? |
| M09 | wants to search inside one module only | `search_symbols` accepts a path prefix and honours it | M | H? |

Not covered here: git submodules (nested repositories are excluded from the parent's file listing
by design) and Go/Python monorepos (parser sidecars; the Go/Python real-repo e2e runs in
`docs/EVIDENCE.md` cover those).

## G — Everyday git and file lifecycle

| ID | Developer does | Desired Dylext behaviour | Sev | Basis |
|---|---|---|---|---|
| G01 | creates a new file, not yet `git add`ed | `working_changes` lists it | H | H? |
| G02 | stages an edit (`git add`) | `working_changes` lists it | – | R |
| G03 | edits then reverts (`git checkout -- f`) | `working_changes` no longer lists it | – | R |
| G04 | commits | next read finds the new symbol | – | V |
| G05 | `git commit --amend` | index shows amended content, old symbol gone | M | H? |
| G06 | `git reset --hard HEAD~1` | symbols from the dropped commit gone | M | H? |
| G07 | merge leaves conflict markers in a file | index reports the file as unparsed | M | H? |
| G08 | `git stash` then `stash pop` | `working_changes` empties then returns | – | R |
| G09 | deletes a file and commits | its symbols gone | – | R |
| G10 | renames a file (`git mv`) and commits | old path gone, new path indexed | M | H? |
| G11 | has gitignored build output on disk (`build/Gen.java`) | ignored files are not indexed | H | H? |

## F — File kinds

| ID | Developer does | Desired Dylext behaviour | Sev | Basis |
|---|---|---|---|---|
| F01 | repo has a non-UTF-8 (Latin-1) source file | index continues; one bad file never aborts the run | H | H? |
| F02 | source file with BOM + CRLF line endings | parsed normally | M | H? |
| F03 | binary file named `*.java` | index continues | H | H? |
| F04 | empty and comment-only source files | no crash | – | R |

## T — Topology, S — Agent sessions

| ID | Developer does | Desired Dylext behaviour | Sev | Basis |
|---|---|---|---|---|
| T01 | edits repo A while repo B is also indexed | the two stay independent | – | V |
| T02 | uses a `git worktree` | git tools work in the worktree | M | H? |
| S01 | closes the agent, returns later (server restart) | sessions, repos tag and index persist | – | R |
| S02 | two tasks in parallel on the same repo | both listed for the repo, facts not mixed | – | R |
| S03 | agent records a fact with a typo'd repo name | rejected or warned, not silently tagged | L | H? |

## Out of scope (by design, see `docs/SKILL.md`)
Automatic file watching / edit tracking (Dylext is pull-based); making the symbol index follow
uncommitted edits without an explicit `index`; document ingestion (Sestacon's job).

## Running
```bash
mvn -B -DskipTests package            # builds target/dylext-0.2.0.jar
bash e2e/run.sh                       # throwaway Postgres + all cases + teardown
bash e2e/run.sh --only O,B            # a subset (group letters or case ids)
bash scripts/dev-local.sh e2e         # same suite with --strict: exits 1 on any GAP/ERROR (CI gate)
bash e2e/run.sh --report docs/DEV-ACTIVITY-GAP-REPORT.md
```
Results of the latest run: `docs/DEV-ACTIVITY-GAP-REPORT.md` (per-case evidence) and
`docs/DEV-ACTIVITY-FINDINGS.md` (root causes + suggested fix order).
