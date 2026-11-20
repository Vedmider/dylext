# Contributing to Dylext

Thanks for helping improve Dylext. This repo favours small, test-first changes.

## Prerequisites

- JDK 21 (`source env.sh` sets the project's toolchain)
- Docker (for the pgvector Testcontainers integration tests)
- Go 1.21 and Node 20 only if you touch `parsers/go` or `parsers/ts`

## Build & test

```bash
mvn -B clean verify          # unit (surefire) + integration (failsafe + Testcontainers)
cd parsers/go && go test ./... && gofmt -l .   # Go sidecar (gofmt must be clean)
cd parsers/ts && npm ci && npm test            # TypeScript sidecar
```

The developer-activity and black-box harnesses live under `e2e/` (run against a local
stack): `bash e2e/run.sh` (Dylext) and `python3 e2e/sestacon_probe.py` (Sestacon).

## Pull requests

1. Write or update a test first; watch it fail, then make it pass.
2. Keep the change focused; match existing style and the minimal-comment convention.
3. Run `mvn -B clean verify` and the relevant sidecar/e2e checks before pushing.
4. Fill in the PR template (what/changes/testing). CI must be green.

## Reporting issues

Use the issue templates. For anything security-sensitive, see [SECURITY.md](SECURITY.md).
