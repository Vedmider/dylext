# Developer-activity findings

The first run of the suite (`e2e/dev_activity.py`) found **23 gaps in 52 cases**; adding the
multi-module group (M01-M09) brought it to 61 cases and 28 gaps. All of them are fixed, test-first:
the suite now runs **61 PASS, 0 GAP** (`bash scripts/dev-local.sh e2e`, a strict CI gate), backed by
130 unit + 67 integration tests. Per-case evidence: `DEV-ACTIVITY-GAP-REPORT.md`.

## Root causes and what fixed them

| # | Root cause | Cases | Fix |
|---|---|---|---|
| 1 | Git dir only found at `<root>/.git`: subfolder/module roots lost all git features | O13 O20 M05 M06 | `JGitClient` finds the git dir upward, scopes diffs/commits to the root and reports **root-relative paths**; linked worktrees keep their own work tree |
| 2 | One unreadable file aborted the whole index run | F01 F03 | UTF-8 then ISO-8859-1 fallback; binary = counted as failed, never aborts |
| 3 | Never-indexed repo answered with silent empties | O01 O02 O03 | `outline`/`search_symbols` (and `verify_claims`, `answer`) fail with `repo not indexed ... call index first` |
| 4 | Index hid what it skipped or half-parsed | O05 O06 O07 G07 | `index` reports `filesFailed`, `filesDegraded`, `skippedUnsupported`, `problems`; degraded files are retried next run |
| 5 | `.gitignore` ignored; reused repo name kept old data | G11 O17 M07 | ignore-aware file listing (nested repos excluded); re-pointing a name purges the old data and reports `replacedRoot` |
| 6 | No way to orient on a new repo | O08 O09 M08 | `list_repos`, `repo_overview` (languages, **modules**, top-level folders), `languages` |
| 7 | Branch invisible | B10 B12 | `branch`/`head` in `index`, `sync`, `list_repos`, `repo_overview`; optional `branch` on session entries |
| 8 | Thin validation | O14 O15 O16 S03 | absolute existing directory roots (`-32602`); `session_fact` warns on an unindexed repo name |
| 9 | Silent backend outage, slow first index | O19 O18 M09 | `explain`/`recall` return `{status: ok\|unreachable, hits}`; `answer.docsAvailable` means reachable; per-file transactions fixed throughput; `search_symbols` takes `pathPrefix` |

A regression found while fixing: git worktrees (T02) broke when git-dir discovery moved upward;
fixed by pinning the work tree to the nearest ancestor holding `.git`, with a unit test using the
real `git worktree add`.

## Multi-module repositories

Yes, they work, two ways:

- **Index the repo root** (recommended): every module's symbols are searchable, the same class name
  in two modules stays distinct (ids carry the module path), `impact_of` follows calls across
  modules, and `target/`/`node_modules/` output is excluded via `.gitignore`.
  `repo_overview` lists detected modules (maven, gradle, go, node, python, rust) and
  `search_symbols {pathPrefix: "web/"}` scopes a query to one module.
- **Index a module as its own repo** (root = module dir): works, including `working_changes`,
  `recent_changes` and commit re-sync, with module-relative paths.

## Residual limits (by design, documented)

- Pull-based: nothing watches files. `working_changes` is the live diff; the symbol index is
  commit-granular, and only `index` (not `sync`) picks up uncommitted code.
- Git submodule contents and nested repos are not indexed as part of the parent; index them as their
  own repos.
- `pathPrefix` is a literal string prefix (`web/` and `web` differ: `web` also matches `webapp/`).
- Module detection is by build file name only; it does not parse module declarations.
- Very large first indexes are still one blocking call (throughput is fixed, there is no
  progress/async mode).
