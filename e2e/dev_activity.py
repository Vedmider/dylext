#!/usr/bin/env python3
"""Dylext developer-activity GAP FINDER (see docs/DEV-ACTIVITY-TEST-PLAN.md).

Drives the real Dylext MCP server (stdio) against real temporary git repos, replaying what
developers do: first contact with a downloaded repo, editing, committing, switching branches,
pulling, rebasing... Each case states the DESIRED behaviour; where Dylext falls short the case is
reported as a GAP with the observed evidence. ERROR means a harness problem, never a gap.

Needs: built jar, Postgres at DB_URL (e2e/run.sh starts a throwaway one), git. Python 3.6+ stdlib.
"""
import argparse
import io
import json
import os
import queue
import shutil
import subprocess
import sys
import threading
import time

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace",
                              line_buffering=True)

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
JAR = os.environ.get("DYLEXT_JAR", os.path.join(ROOT, "target", "dylext-0.2.0.jar"))
DB_URL = os.environ.get("DB_URL", "jdbc:postgresql://localhost:55433/dylext")
WORK = os.environ.get("DEVACT_WORK", "/tmp/devact")
RUN = str(int(time.time()) % 100000)


# ---------------------------------------------------------------- MCP client
class Res(object):
    def __init__(self, ok, data=None, code=None, msg=None, epoch=None, cached=None):
        self.ok, self.data, self.code, self.msg, self.epoch, self.cached = \
            ok, data, code, msg, epoch, cached


class Mcp(object):
    def __init__(self, extra=None, cwd=None):
        # cap the DB pool per spawned server: the suite starts many stdio servers against one Postgres,
        # and the default HikariCP pool (10) exhausts max_connections, making late spawns fail to start.
        cmd = ["java", "-jar", JAR, "--dylext.mcp.stdio=true",
               "--spring.datasource.hikari.maximum-pool-size=3"] + list(extra or [])
        env = dict(os.environ, DB_URL=DB_URL, DB_USERNAME="dylext", DB_PASSWORD="dylext")
        self.p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.DEVNULL, encoding="utf-8", errors="replace",
                                  bufsize=1, env=env, cwd=cwd)
        self.q = queue.Queue()
        self.noise = []
        self._id = 0
        threading.Thread(target=self._pump, daemon=True).start()
        r = self.rpc("initialize", {}, timeout=120)
        if not r or "result" not in r:
            raise RuntimeError("dylext did not start")

    def _pump(self):
        for line in self.p.stdout:
            line = line.strip()
            if line:
                try:
                    self.q.put(json.loads(line))
                except ValueError:
                    self.noise.append(line[:120])
        self.q.put(None)

    def rpc(self, method, params=None, timeout=300):
        self._id += 1
        msg = {"jsonrpc": "2.0", "id": self._id, "method": method}
        if params is not None:
            msg["params"] = params
        try:
            self.p.stdin.write(json.dumps(msg) + "\n")
            self.p.stdin.flush()
            return self.q.get(timeout=timeout)
        except queue.Empty:
            return {"error": {"code": -1, "message": "TIMEOUT after %ss" % timeout}}
        except (BrokenPipeError, OSError):
            return None

    def call(self, name, args=None, timeout=300):
        r = self.rpc("tools/call", {"name": name, "arguments": args or {}}, timeout)
        if r is None:
            return Res(False, msg="server closed")
        if "error" in r:
            return Res(False, code=r["error"].get("code"), msg=r["error"].get("message"))
        return Res(True, data=r["result"]["content"][0]["json"], epoch=r["result"].get("epoch"),
                   cached=r["result"].get("cached"))

    def tools(self):
        return [t["name"] for t in self.rpc("tools/list", {})["result"]["tools"]]

    def close(self):
        try:
            self.p.stdin.close()
            self.p.wait(timeout=10)
        except Exception:
            self.p.kill()


# ---------------------------------------------------------------- git/repo helpers
def sh(cmd, cwd=None, check=True):
    p = subprocess.run(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = p.stdout.decode("utf-8", "replace")
    if check and p.returncode != 0:
        raise RuntimeError("%s failed: %s" % (" ".join(cmd), out.strip()))
    return out


class Repo(object):
    def __init__(self, path):
        self.path = path

    @classmethod
    def create(cls, name):
        path = os.path.join(WORK, name)
        shutil.rmtree(path, ignore_errors=True)
        os.makedirs(path)
        r = cls(path)
        r.git("init", "-q")
        return r

    def git(self, *args, **kw):
        base = ["git", "-C", self.path, "-c", "user.email=t@t", "-c", "user.name=t",
                "-c", "commit.gpgsign=false"]
        return sh(base + list(args), check=kw.get("check", True))

    def write(self, rel, content, binary=False):
        p = os.path.join(self.path, rel)
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "wb" if binary else "w") as f:
            f.write(content)

    def read(self, rel):
        with open(os.path.join(self.path, rel)) as f:
            return f.read()

    def commit(self, msg="c"):
        self.git("add", "-A")
        self.git("commit", "-q", "-m", msg)

    def branch(self):
        return self.git("rev-parse", "--abbrev-ref", "HEAD").strip()

    def checkout(self, *args):
        self.git("checkout", "-q", *args)


def java(pkg, cls, *methods):
    ms = methods or ("base",)
    body = "".join("  public int %s() { return 1; }\n" % m for m in ms)
    return "package %s;\npublic class %s {\n%s}\n" % (pkg, cls, body)


def add_method(r, rel, m):
    s = r.read(rel).rstrip()
    assert s.endswith("}"), rel
    r.write(rel, s[:-1] + "  public int %s() { return 9; }\n}\n" % m)


def replace_in(r, rel, a, b):
    r.write(rel, r.read(rel).replace(a, b))


def clone(src, name, extra=()):
    dst = os.path.join(WORK, name)
    shutil.rmtree(dst, ignore_errors=True)
    sh(["git", "clone", "-q"] + list(extra) + ["file://" + src.path, dst])
    return Repo(dst)


def show(res, n=160):
    if res.ok:
        s = json.dumps(res.data)
        return "ok " + (s if len(s) <= n else s[:n] + "...")
    return "ERR %s %s" % (res.code, (res.msg or "")[:n])


SIGNAL_WORDS = ("unsupported", "warn", "ignored", "noparser", "degrad", "unparsed", "failed", "error", "unreach")


def has_signal(d, words=SIGNAL_WORDS):
    """True only if a signal field is present AND non-empty/non-zero (a '0 degraded' counter is not a signal)."""
    return isinstance(d, dict) and any(w in k.lower() and bool(v) for k, v in d.items() for w in words)


def search(m, repo, q, k=50):
    r = m.call("search_symbols", {"repo": repo, "query": q, "topK": k})
    return r.data if (r.ok and isinstance(r.data, list)) else []


def names(m, repo, q):
    return [s.get("name") for s in search(m, repo, q)]


def has_exact(m, repo, name):
    """True iff a symbol named exactly `name` is indexed. search_symbols is fuzzy, so an
    absence check must test exact membership, not list non-emptiness (a near name fuzzy-matches)."""
    return name in names(m, repo, name)


def sids(m, repo, q):
    return [s.get("id", "") for s in search(m, repo, q)]


def wc_paths(m, repo):
    r = m.call("working_changes", {"repo": repo})
    paths = [w.get("path", "") for w in r.data] if (r.ok and isinstance(r.data, list)) else None
    return paths, r


# ---------------------------------------------------------------- framework
CASES = []


class Skip(Exception):
    pass


def case(cid, title, sev, desired):
    def deco(fn):
        CASES.append({"id": cid, "title": title, "sev": sev, "desired": desired, "fn": fn})
        return fn
    return deco


class Chk(object):
    def __init__(self, desired):
        self.desired, self.passes, self.fails, self.notes = desired, 0, [], []

    def expect(self, cond, observed="", desired=None):
        if cond:
            self.passes += 1
        else:
            self.fails.append((desired or self.desired, observed))

    def note(self, text):
        self.notes.append(text)


class Ctx(object):
    def __init__(self):
        self._m = None
        self._cache = None
        self.servers = []

    @property
    def m(self):
        if self._m is None:
            self._m = self.server()
        return self._m

    def cache_server(self):
        """A single shared cache-ENABLED server reused across cache cases (each case uses its own repo,
        so cache keys never collide). Avoids spawning one server per case and exhausting DB connections."""
        if self._cache is None:
            self._cache = self.server(extra=["--dylext.cache.enabled=true"])
        return self._cache

    def server(self, extra=None, cwd=None):
        s = Mcp(extra, cwd)
        self.servers.append(s)
        return s

    def rname(self, cid):
        return "dx%s%s" % (cid.lower(), RUN)

    def close_all(self):
        for s in self.servers:
            s.close()


def seeded(ctx, cid, server=None, n=5):
    """A committed repo of n small Java files, indexed, with the sync cursor set by a first read."""
    m = server or ctx.m
    name = ctx.rname(cid)
    r = Repo.create(name)
    for i in range(1, n + 1):
        r.write("F%d.java" % i, java("p", "F%d" % i, "base"))
    r.commit("baseline")
    m.call("index", {"repo": name, "root": r.path})
    m.call("search_symbols", {"repo": name, "query": "base"})
    return name, r


# ================================================================ O: first contact
@case("O01", "search on a never-indexed repo", "H",
      "actionable -32602 'repo not indexed ... call index', never a silent empty result")
def o01(ctx, c):
    r = ctx.m.call("search_symbols", {"repo": ctx.rname("o01ghost"), "query": "anything"})
    c.expect((not r.ok) and r.code == -32602 and "index" in (r.msg or "").lower(), show(r))


@case("O02", "outline on a never-indexed repo", "H",
      "actionable -32602 'repo not indexed ... call index', never a silent empty result")
def o02(ctx, c):
    r = ctx.m.call("outline", {"repo": ctx.rname("o02ghost"), "path": "F1.java"})
    c.expect((not r.ok) and r.code == -32602 and "index" in (r.msg or "").lower(), show(r))


@case("O03", "federation tools (answer/explain/recall) are gone after the two-app split", "M",
      "Dylext is code-only; answer/explain/recall/summary are not advertised")
def o03(ctx, c):
    # Two-app refactor: federation tools (answer/explain/recall) were removed from Dylext.
    names = ctx.m.tools()
    gone = [t for t in ("answer", "explain", "recall", "summary") if t in names]
    c.expect(not gone, "removed federation tools still advertised: %s" % gone)


@case("O04", "index a fresh clone, then index again", "-", "correct counts; 2nd run skips unchanged")
def o04(ctx, c):
    name = ctx.rname("o04")
    r = Repo.create(name)
    for i in range(1, 6):
        r.write("F%d.java" % i, java("p", "F%d" % i))
    r.commit("init")
    a = ctx.m.call("index", {"repo": name, "root": r.path})
    c.expect(a.ok and a.data["filesScanned"] == 5 and a.data["symbols"] >= 5, show(a))
    b = ctx.m.call("index", {"repo": name, "root": r.path})
    c.expect(b.ok and b.data["filesSkipped"] == 5, show(b))


@case("O05", "repo with only unsupported languages (.ts/.rs/.md)", "M",
      "index response says files were ignored for unsupported language, not a silent 0/0")
def o05(ctx, c):
    name = ctx.rname("o05")
    r = Repo.create(name)
    r.write("a.ts", "export const x = 1;\n")
    r.write("lib.rs", "fn main() {}\n")
    r.write("README.md", "# hi\n")
    r.commit("init")
    res = ctx.m.call("index", {"repo": name, "root": r.path})
    c.expect(res.ok and has_signal(res.data), show(res))


# ================================================================ EP: epoch / snapshot (Phase 2)
@case("EP01", "every repo-scoped read carries its committed epoch", "M",
      "result.epoch = {provider:dylext, repo, commit=indexed cursor, dirty:false} for a committed read")
def ep01(ctx, c):
    name, r = seeded(ctx, "ep01")
    rc = ctx.m.call("recent_changes", {"repo": name})
    e = rc.epoch or {}
    c.expect(e.get("provider") == "dylext" and e.get("repo") == name
             and bool(e.get("commit")) and e.get("dirty") is False, show(rc) + " epoch=%s" % e)


@case("EP02", "working_changes is tagged epoch=dirty (never cacheable)", "M",
      "result.epoch.dirty == true for the live working-tree read")
def ep02(ctx, c):
    name, r = seeded(ctx, "ep02")
    wc = ctx.m.call("working_changes", {"repo": name})
    e = wc.epoch or {}
    c.expect(e.get("dirty") is True and bool(e.get("commit")), "epoch=%s" % e)


@case("EP03", "symbolId tools derive the epoch from the id prefix; non-repo tools omit it", "L",
      "impact_of carries the repo epoch; ledger carries no epoch")
def ep03(ctx, c):
    name, r = seeded(ctx, "ep03")
    sid = sids(ctx.m, name, "base")
    if sid:
        imp = ctx.m.call("impact_of", {"symbolId": sid[0]})
        c.expect((imp.epoch or {}).get("repo") == name, "epoch=%s" % imp.epoch)
    led = ctx.m.call("ledger", {})
    c.expect(led.epoch is None, "ledger epoch=%s" % led.epoch)


# ================================================================ CA: response cache (Phase 3)
@case("CA01", "a repeated deterministic read is served from cache (cached=true), same result", "M",
      "2nd identical search_symbols on an unchanged repo is cached and byte-identical")
def ca01(ctx, c):
    srv = ctx.cache_server()
    name, r = seeded(ctx, "ca01", server=srv)
    a = srv.call("search_symbols", {"repo": name, "query": "F3"})   # seeded warms "base"; F3 is a cold key
    b = srv.call("search_symbols", {"repo": name, "query": "F3"})
    c.expect(a.cached is False and b.cached is True, "first.cached=%s second.cached=%s" % (a.cached, b.cached))
    c.expect(a.data == b.data, "cached result differs from fresh result")


@case("CA02", "a new commit (epoch bump) busts the cache", "M",
      "after a commit moves HEAD, the same read recomputes (cached=false)")
def ca02(ctx, c):
    srv = ctx.cache_server()
    name, r = seeded(ctx, "ca02", server=srv)
    srv.call("search_symbols", {"repo": name, "query": "base"})           # warm
    warm = srv.call("search_symbols", {"repo": name, "query": "base"})
    add_method(r, "F1.java", "ca02New")
    r.commit("bump")
    after = srv.call("search_symbols", {"repo": name, "query": "base"})   # read auto-syncs -> new epoch
    c.expect(warm.cached is True and after.cached is False,
             "warm.cached=%s afterBump.cached=%s" % (warm.cached, after.cached))


@case("CA03", "dirty working_changes is never cached; cache off by default emits no flag", "M",
      "working_changes carries no cached flag; default server does not cache")
def ca03(ctx, c):
    srv = ctx.cache_server()
    name, r = seeded(ctx, "ca03", server=srv)
    r.write("F1.java", java("p", "F1", "base") + "// edit\n")
    w1 = srv.call("working_changes", {"repo": name})
    w2 = srv.call("working_changes", {"repo": name})
    c.expect(w1.cached is None and w2.cached is None, "working_changes cached flags: %s %s" % (w1.cached, w2.cached))
    name2, _ = seeded(ctx, "ca03def")                                     # default server: cache disabled
    d2 = ctx.m.call("search_symbols", {"repo": name2, "query": "base"})
    c.expect(d2.cached is None, "default-server cached flag should be absent, got %s" % d2.cached)


# ================================================================ LT: capability tiers (Phase 4b)
@case("LT01", "languages report an honest capability tier", "L",
      "each language entry carries a tier; java is T3 (typed overload resolution)")
def lt01(ctx, c):
    langs = ctx.m.call("languages", {})
    rows = langs.data if isinstance(langs.data, list) else []
    c.expect(bool(rows) and all("tier" in r for r in rows), "a language row lacks a tier: %s" % rows)
    java_ = [r for r in rows if r.get("language") == "java"]
    c.expect(bool(java_) and java_[0].get("tier") == "T3", "java tier=%s" % (java_[0].get("tier") if java_ else None))


# ================================================================ SG: runtime signals (Phase 4c)
@case("SG01", "runtime signals reorder impact_of callers (hot & untested first) and expose weight", "M",
      "after ingest_signals, impact_of callers are ranked by weight with a signalRanking")
def sg01(ctx, c):
    name = ctx.rname("sg01")
    r = Repo.create(name)
    r.write("Target.java", "package p;\npublic class Target { public void hot(){} }\n")
    r.write("CallerA.java", "package p;\npublic class CallerA { public void a(){ new Target().hot(); } }\n")
    r.write("CallerB.java", "package p;\npublic class CallerB { public void b(){ new Target().hot(); } }\n")
    r.commit("init")
    ctx.m.call("index", {"repo": name, "root": r.path})
    tgt = [s for s in sids(ctx.m, name, "hot") if "Target#hot" in s]
    c.expect(bool(tgt), "no Target#hot symbol")
    if not tgt:
        return
    before = ctx.m.call("impact_of", {"symbolId": tgt[0]})
    callers = before.data.get("callers", []) if isinstance(before.data, dict) else []
    c.expect(len(callers) == 2, "expected 2 callers, got %s" % callers)
    if len(callers) != 2:
        return
    hot = [x for x in callers if "CallerB" in x][0]
    cold = [x for x in callers if "CallerA" in x][0]
    ctx.m.call("ingest_signals", {"repo": name, "signals": [
        {"symbolId": hot, "coverage": 0.0, "callFrequency": 100},     # untested & hot -> ranks first
        {"symbolId": cold, "coverage": 1.0, "callFrequency": 100}]})  # fully covered -> weight 0
    after = ctx.m.call("impact_of", {"symbolId": tgt[0]})
    ac = after.data.get("callers", []) if isinstance(after.data, dict) else []
    ranking = after.data.get("signalRanking", []) if isinstance(after.data, dict) else []
    c.expect(bool(ac) and ac[0] == hot, "hot caller should rank first: %s" % ac)
    c.expect(len(ranking) == 2 and ranking[0]["id"] == hot and ranking[0]["weight"] > ranking[1]["weight"],
             "signalRanking missing/incorrect: %s" % ranking)


# ---- epoch: edge/consistency cases -------------------------------------------------------------
@case("EP04", "the epoch is consistent across tools for the same repo at the same commit", "M",
      "outline, search_symbols and get_digest all report the same {provider, commit, dirty:false}")
def ep04(ctx, c):
    name, r = seeded(ctx, "ep04")
    o = ctx.m.call("outline", {"repo": name, "path": "F1.java"})
    s = ctx.m.call("search_symbols", {"repo": name, "query": "base"})
    sid = sids(ctx.m, name, "base")
    g = ctx.m.call("get_digest", {"symbolId": sid[0]}) if sid else None
    eps = [x.epoch for x in (o, s, g) if x is not None]
    first = eps[0] or {}
    c.expect(all(e == first for e in eps) and first.get("dirty") is False and bool(first.get("commit")),
             "epochs differ across tools: %s" % eps)


@case("EP05", "a commit moves the epoch commit; a symbolId tool sees the same new commit", "M",
      "after a commit, recent_changes and get_digest report the new HEAD, not the old one")
def ep05(ctx, c):
    name, r = seeded(ctx, "ep05")
    before = ctx.m.call("recent_changes", {"repo": name}).epoch or {}
    add_method(r, "F1.java", "ep05New")
    r.commit("move head")
    after = ctx.m.call("recent_changes", {"repo": name}).epoch or {}
    sid = sids(ctx.m, name, "ep05New")
    gep = (ctx.m.call("get_digest", {"symbolId": sid[0]}).epoch or {}) if sid else {}
    c.expect(before.get("commit") and after.get("commit") and before["commit"] != after["commit"],
             "commit did not move epoch: %s -> %s" % (before.get("commit"), after.get("commit")))
    c.expect(gep.get("commit") == after.get("commit"),
             "symbolId epoch %s != repo epoch %s" % (gep.get("commit"), after.get("commit")))


# ---- cache: edge/consistency cases -------------------------------------------------------------
@case("CA05", "distinct arguments get distinct cache entries (no key collision)", "M",
      "caching query F2 does not serve F2's result for query F3")
def ca05(ctx, c):
    srv = ctx.cache_server()
    name, r = seeded(ctx, "ca05", server=srv)
    f2a = srv.call("search_symbols", {"repo": name, "query": "F2"})
    f2b = srv.call("search_symbols", {"repo": name, "query": "F2"})
    f3a = srv.call("search_symbols", {"repo": name, "query": "F3"})
    c.expect(f2a.cached is False and f2b.cached is True, "F2 miss/hit: %s/%s" % (f2a.cached, f2b.cached))
    c.expect(f3a.cached is False, "a never-seen query F3 must miss, got cached=%s" % f3a.cached)
    c.expect(f2a.data != f3a.data or f2a.data == [] , "F2 and F3 returned the same payload (key collision)")


@case("CA06", "get_digest is cached and the cached payload equals the fresh one", "M",
      "a structural read (get_digest) caches and returns byte-identical content")
def ca06(ctx, c):
    srv = ctx.cache_server()
    name, r = seeded(ctx, "ca06", server=srv)
    sid = sids(srv, name, "base")
    c.expect(bool(sid), "no symbol to digest")
    if not sid:
        return
    a = srv.call("get_digest", {"symbolId": sid[0]})
    b = srv.call("get_digest", {"symbolId": sid[0]})
    c.expect(a.cached is False and b.cached is True, "get_digest miss/hit: %s/%s" % (a.cached, b.cached))
    c.expect(a.data == b.data, "cached digest differs from fresh")


@case("CA07", "a commit busts the cache AND the recomputed read is fresh (no stale hit)", "H",
      "after adding a method and committing, a cache-enabled outline shows it and is not a stale hit")
def ca07(ctx, c):
    srv = ctx.cache_server()
    name, r = seeded(ctx, "ca07", server=srv)
    srv.call("outline", {"repo": name, "path": "F1.java"})            # warm
    warm = srv.call("outline", {"repo": name, "path": "F1.java"})
    add_method(r, "F1.java", "ca07New")
    r.commit("add method")
    after = srv.call("outline", {"repo": name, "path": "F1.java"})
    names_after = [s.get("name") for s in (after.data if isinstance(after.data, list) else [])]
    c.expect(warm.cached is True, "second identical outline should be a hit, got %s" % warm.cached)
    c.expect(after.cached is False, "after a commit the outline must recompute, got cached=%s" % after.cached)
    c.expect("ca07New" in names_after, "recomputed outline is stale, missing ca07New: %s" % names_after)


# ---- tiers -------------------------------------------------------------------------------------
@case("LT02", "impact_of on a T3 (java) symbol carries no capabilityNote (byte-identical)", "L",
      "a T2+ language never gets the tier-limited note")
def lt02(ctx, c):
    name, r = seeded(ctx, "lt02")
    sid = sids(ctx.m, name, "base")
    if not sid:
        c.expect(False, "no symbol")
        return
    imp = ctx.m.call("impact_of", {"symbolId": sid[0]})
    has_note = isinstance(imp.data, dict) and "capabilityNote" in imp.data
    c.expect(not has_note, "java impact_of should have no capabilityNote: %s" % imp.data)


# ---- signals -----------------------------------------------------------------------------------
def _caller_repo(ctx, cid):
    name = ctx.rname(cid)
    r = Repo.create(name)
    r.write("Target.java", "package p;\npublic class Target { public void hot(){} }\n")
    r.write("CallerA.java", "package p;\npublic class CallerA { public void a(){ new Target().hot(); } }\n")
    r.commit("init")
    ctx.m.call("index", {"repo": name, "root": r.path})
    return name, r


@case("SG02", "with no signals, impact_of has no signalRanking (byte-identical to pre-4c)", "M",
      "the signalRanking field is absent until signals are ingested")
def sg02(ctx, c):
    name, r = _caller_repo(ctx, "sg02")
    tgt = [s for s in sids(ctx.m, name, "hot") if "Target#hot" in s]
    if not tgt:
        c.expect(False, "no Target#hot")
        return
    imp = ctx.m.call("impact_of", {"symbolId": tgt[0]})
    c.expect(isinstance(imp.data, dict) and "signalRanking" not in imp.data,
             "signalRanking must be absent with no signals: %s" % imp.data)


@case("SG03", "re-ingesting a symbol's signal updates its weight (upsert, no duplicate)", "M",
      "the second ingest replaces the weight; callers are not duplicated")
def sg03(ctx, c):
    name, r = _caller_repo(ctx, "sg03")
    tgt = [s for s in sids(ctx.m, name, "hot") if "Target#hot" in s]
    caller = [x for x in ctx.m.call("impact_of", {"symbolId": tgt[0]}).data.get("callers", [])] if tgt else []
    if not caller:
        c.expect(False, "no caller")
        return
    cid = caller[0]
    ctx.m.call("ingest_signals", {"repo": name, "signals": [{"symbolId": cid, "coverage": 0.0, "callFrequency": 10}]})
    ctx.m.call("ingest_signals", {"repo": name, "signals": [{"symbolId": cid, "coverage": 0.0, "callFrequency": 20}]})
    imp = ctx.m.call("impact_of", {"symbolId": tgt[0]})
    ranking = imp.data.get("signalRanking", [])
    c.expect(len(imp.data.get("callers", [])) == 1, "caller list duplicated: %s" % imp.data.get("callers"))
    c.expect(len(ranking) == 1 and ranking[0]["weight"] == 20.0,
             "weight not upserted to 20: %s" % ranking)


@case("SG04", "ingest_signals validates its input (-32602, never a 5xx)", "M",
      "missing repo, empty signals, and a signal without symbolId are all invalid params")
def sg04(ctx, c):
    bad = [
        {"signals": [{"symbolId": "x:F.java:C#m", "coverage": 0, "callFrequency": 1}]},   # no repo
        {"repo": "r", "signals": []},                                                     # empty
        {"repo": "r", "signals": [{"coverage": 0, "callFrequency": 1}]},                  # no symbolId
    ]
    for args in bad:
        r = ctx.m.call("ingest_signals", args)
        c.expect((not r.ok) and r.code == -32602, "expected -32602 for %s, got ok=%s code=%s" % (args, r.ok, r.code))


@case("SG05", "out-of-range coverage is clamped so weight stays sane (never negative)", "L",
      "coverage 2.0 clamps to 1.0 -> weight 0; weight is never negative")
def sg05(ctx, c):
    name, r = _caller_repo(ctx, "sg05")
    tgt = [s for s in sids(ctx.m, name, "hot") if "Target#hot" in s]
    caller = ctx.m.call("impact_of", {"symbolId": tgt[0]}).data.get("callers", []) if tgt else []
    if not caller:
        c.expect(False, "no caller")
        return
    ctx.m.call("ingest_signals", {"repo": name,
               "signals": [{"symbolId": caller[0], "coverage": 2.0, "callFrequency": 50}]})
    ranking = ctx.m.call("impact_of", {"symbolId": tgt[0]}).data.get("signalRanking", [])
    c.expect(len(ranking) == 1 and ranking[0]["weight"] == 0.0,
             "coverage>1 should clamp weight to 0: %s" % ranking)


@case("SG06", "impact_of reflects a caller added by a later commit (freshness, not stale edges)", "H",
      "after committing a new caller, impact_of lists it without an explicit sync")
def sg06(ctx, c):
    name, r = _caller_repo(ctx, "sg06")
    tgt = [s for s in sids(ctx.m, name, "hot") if "Target#hot" in s]
    if not tgt:
        c.expect(False, "no Target#hot")
        return
    before = ctx.m.call("impact_of", {"symbolId": tgt[0]}).data.get("callers", [])
    r.write("CallerB.java", "package p;\npublic class CallerB { public void b(){ new Target().hot(); } }\n")
    r.commit("add caller B")
    after = ctx.m.call("impact_of", {"symbolId": tgt[0]}).data.get("callers", [])
    c.expect(len(before) == 1, "expected 1 caller before, got %s" % before)
    c.expect(len(after) == 2 and any("CallerB" in x for x in after),
             "impact_of did not pick up the new committed caller (stale edges): %s" % after)


@case("SG08", "ingest_signals invalidates the cache (no stale impact_of when cache is on)", "H",
      "with cache enabled, impact_of reflects freshly ingested signals without needing a commit (F-017)")
def sg08(ctx, c):
    srv = ctx.cache_server()
    name = ctx.rname("sg08")
    r = Repo.create(name)
    r.write("Target.java", "package p;\npublic class Target { public void hot(){} }\n")
    r.write("CallerA.java", "package p;\npublic class CallerA { public void a(){ new Target().hot(); } }\n")
    r.commit("init")
    srv.call("index", {"repo": name, "root": r.path})
    tgt = [s for s in sids(srv, name, "hot") if "Target#hot" in s]
    if not tgt:
        c.expect(False, "no Target#hot")
        return
    warm = srv.call("impact_of", {"symbolId": tgt[0]})        # cache it, no signals yet
    caller = warm.data.get("callers", []) if isinstance(warm.data, dict) else []
    c.expect(warm.cached is False and "signalRanking" not in warm.data, "warmup unexpected: %s" % warm.data)
    if not caller:
        c.expect(False, "no caller")
        return
    srv.call("ingest_signals", {"repo": name,
             "signals": [{"symbolId": caller[0], "coverage": 0.0, "callFrequency": 7}]})
    after = srv.call("impact_of", {"symbolId": tgt[0]})
    rank = after.data.get("signalRanking") if isinstance(after.data, dict) else None
    c.expect(after.cached is False, "impact_of should recompute after ingest, cached=%s" % after.cached)
    c.expect(bool(rank) and rank[0]["weight"] == 7.0,
             "ranking not reflected after ingest (stale cache, F-017): %s" % rank)


# ================================================================ CM: cache x freshness interaction matrix
# Review lesson (F-017/F-018): the suite tested tools once, cache-off, rarely re-reading the SAME tool
# after a state change. These assert every at-risk cacheable read is fresh after a change under cache-ON.
@case("CM01", "repo_overview is fresh after a commit (no stale counts; F-018)", "M",
      "repo_overview as the ONLY reader reflects a newly committed file")
def cm01(ctx, c):
    name = ctx.rname("cm01")
    r = Repo.create(name)
    r.write("A.java", java("p", "A", "m"))
    r.commit("init")
    ctx.m.call("index", {"repo": name, "root": r.path})
    before = ctx.m.call("repo_overview", {"repo": name}).data.get("files")
    r.write("B.java", java("p", "B", "n"))
    r.commit("add B")
    after = ctx.m.call("repo_overview", {"repo": name}).data.get("files")
    c.expect(before == 1 and after == 2, "repo_overview stale (no freshness): files %s -> %s" % (before, after))


@case("CM02", "cache-on: impact_of busts on commit and lists the new caller (not a stale hit)", "H",
      "a cached impact_of reflects a caller added by a later commit")
def cm02(ctx, c):
    srv = ctx.cache_server()
    name = ctx.rname("cm02")
    r = Repo.create(name)
    r.write("Target.java", "package p;\npublic class Target { public void hot(){} }\n")
    r.write("CallerA.java", "package p;\npublic class CallerA { public void a(){ new Target().hot(); } }\n")
    r.commit("init")
    srv.call("index", {"repo": name, "root": r.path})
    tgt = [s for s in sids(srv, name, "hot") if "Target#hot" in s]
    if not tgt:
        c.expect(False, "no target")
        return
    srv.call("impact_of", {"symbolId": tgt[0]})                # warm
    warm = srv.call("impact_of", {"symbolId": tgt[0]})
    r.write("CallerB.java", "package p;\npublic class CallerB { public void b(){ new Target().hot(); } }\n")
    r.commit("add caller B")
    after = srv.call("impact_of", {"symbolId": tgt[0]})
    ac = after.data.get("callers", []) if isinstance(after.data, dict) else []
    c.expect(warm.cached is True, "warm should hit: %s" % warm.cached)
    c.expect(after.cached is False and len(ac) == 2 and any("CallerB" in x for x in ac),
             "impact_of stale hit after commit: cached=%s callers=%s" % (after.cached, ac))


@case("CM03", "cache-on: expand busts on commit and returns the new body (not a stale hit)", "H",
      "a cached expand reflects an edited+committed method body")
def cm03(ctx, c):
    srv = ctx.cache_server()
    name = ctx.rname("cm03")
    r = Repo.create(name)
    r.write("A.java", "package p;\npublic class A { public void m(){ int x = 1; } }\n")
    r.commit("init")
    srv.call("index", {"repo": name, "root": r.path})
    sid = [s for s in sids(srv, name, "m") if "A#m" in s]
    if not sid:
        c.expect(False, "no symbol")
        return
    srv.call("expand", {"symbolId": sid[0]})                   # warm
    warm = srv.call("expand", {"symbolId": sid[0]})
    r.write("A.java", "package p;\npublic class A { public void m(){ int y = 99; } }\n")
    r.commit("edit body")
    after = srv.call("expand", {"symbolId": sid[0]})
    src = after.data.get("source", "") if isinstance(after.data, dict) else ""
    c.expect(warm.cached is True, "warm should hit: %s" % warm.cached)
    c.expect(after.cached is False and "99" in src,
             "expand stale hit after commit: cached=%s src=%s" % (after.cached, src))


# ================================================================ IL: impact_of large-dataset handling (top-N cap)
@case("IL01", "impact_of limit caps callers to top-N and flags truncation (large blast radius)", "M",
      "limit returns N callers with truncated=true and totalCallers = the full count")
def il01(ctx, c):
    name = ctx.rname("il01")
    r = Repo.create(name)
    r.write("Target.java", "package p;\npublic class Target { public void hot(){} }\n")
    for nm in ("A", "B", "C"):
        r.write("Caller%s.java" % nm,
                "package p;\npublic class Caller%s { public void m(){ new Target().hot(); } }\n" % nm)
    r.commit("init")
    ctx.m.call("index", {"repo": name, "root": r.path})
    tgt = [s for s in sids(ctx.m, name, "hot") if "Target#hot" in s]
    if not tgt:
        c.expect(False, "no target")
        return
    full = ctx.m.call("impact_of", {"symbolId": tgt[0]}).data
    capped = ctx.m.call("impact_of", {"symbolId": tgt[0], "limit": 2}).data
    c.expect(isinstance(full, dict) and len(full.get("callers", [])) == 3, "expected 3 callers: %s" % full)
    c.expect(isinstance(capped, dict) and len(capped.get("callers", [])) == 2
             and capped.get("truncated") is True and capped.get("totalCallers") == 3,
             "limit not applied / truncation not flagged: %s" % capped)


@case("O06", "mixed repo, Python parser not configured", "H",
      "index reports files skipped for lack of a parser (python files must not vanish silently)")
def o06(ctx, c):
    name = ctx.rname("o06")
    r = Repo.create(name)
    r.write("A.java", java("p", "A"))
    r.write("B.java", java("p", "B"))
    for i in range(1, 4):
        r.write("mod%d.py" % i, "def py_func_%d():\n    return %d\n" % (i, i))
    r.commit("init")
    res = ctx.m.call("index", {"repo": name, "root": r.path})
    c.expect(res.ok and has_signal(res.data), show(res))


@case("O07", "Python parser configured but its sidecar is down", "H",
      "index reports parserUnreachable/degraded files (distinct from normally indexed) when the sidecar is down")
def o07(ctx, c):
    srv = ctx.server(extra=["--dylext.parsers.python=http://127.0.0.1:1"])
    name = ctx.rname("o07")
    r = Repo.create(name)
    for i in range(1, 4):
        r.write("mod%d.py" % i, "def pyfunc_%d():\n    return %d\n" % (i, i))
    r.commit("init")
    res = srv.call("index", {"repo": name, "root": r.path})
    found = names(srv, name, "pyfunc_1")
    c.note("observed: %s ; search pyfunc_1 -> %s" % (show(res), found))
    c.expect(res.ok and has_signal(res.data), show(res))


@case("O08", "which repos are indexed / which languages work?", "M",
      "a tool to list indexed repos and a way to learn supported languages")
def o08(ctx, c):
    tools = ctx.m.tools()
    c.note("tools: %s" % ", ".join(tools))
    c.expect(any(("repo" in t and "list" in t) or t in ("repos", "list_repos") for t in tools),
             "no repo-listing tool", "a tool listing indexed repos")
    c.expect(any(w in t for t in tools for w in ("language", "capabilit", "status", "health")),
             "no language/capability/status tool", "a tool exposing supported languages / status")


@case("O09", "what is in this repo? (no file known yet)", "M",
      "a repo overview/map tool, or outline of '.' returns something")
def o09(ctx, c):
    name, r = seeded(ctx, "o09")
    tools = ctx.m.tools()
    o = ctx.m.call("outline", {"repo": name, "path": "."})
    has_tool = any(w in t for t in tools for w in ("overview", "tree", "map", "structure", "files"))
    c.expect(has_tool or (o.ok and bool(o.data)), "outline('.') -> %s ; overview tool: %s" % (show(o), has_tool))


@case("O10", "find the entry point (main)", "-", "found with its file location")
def o10(ctx, c):
    name = ctx.rname("o10")
    r = Repo.create(name)
    r.write("app/Main.java", "package app;\npublic class Main {\n"
            "  public static void main(String[] args) { }\n}\n")
    r.commit("init")
    ctx.m.call("index", {"repo": name, "root": r.path})
    hits = search(ctx.m, name, "main")
    c.expect(any(h.get("name") == "main" and "Main.java" in h.get("id", "") for h in hits),
             "hits=%s" % [h.get("id") for h in hits])


@case("O11", "downloaded as a ZIP (no .git)", "M",
      "index works; git tools answer gracefully (ok/empty or -32602), not an internal -32603")
def o11(ctx, c):
    src = Repo.create(ctx.rname("o11src"))
    for i in range(1, 4):
        src.write("F%d.java" % i, java("p", "F%d" % i))
    src.commit("init")
    dst = os.path.join(WORK, ctx.rname("o11zip"))
    shutil.rmtree(dst, ignore_errors=True)
    shutil.copytree(src.path, dst, ignore=shutil.ignore_patterns(".git"))
    name = ctx.rname("o11")
    idx = ctx.m.call("index", {"repo": name, "root": dst})
    c.expect(idx.ok and idx.data["symbols"] > 0, show(idx), "index works on a non-git directory")
    wc = ctx.m.call("working_changes", {"repo": name})
    rc = ctx.m.call("recent_changes", {"repo": name})
    c.expect(wc.ok or wc.code == -32602, "working_changes: " + show(wc))
    c.expect(rc.ok or rc.code == -32602, "recent_changes: " + show(rc))


@case("O12", "shallow clone (--depth 1), later pulls", "-", "history works; pull re-syncs")
def o12(ctx, c):
    src = Repo.create(ctx.rname("o12src"))
    src.write("F1.java", java("p", "F1"))
    src.commit("c1")
    add_method(src, "F1.java", "m2")
    src.commit("c2")
    dst = clone(src, ctx.rname("o12dst"), ("--depth", "1"))
    name = ctx.rname("o12")
    ctx.m.call("index", {"repo": name, "root": dst.path})
    ctx.m.call("search_symbols", {"repo": name, "query": "base"})
    rc = ctx.m.call("recent_changes", {"repo": name, "limit": 5})
    c.expect(rc.ok and len(rc.data) >= 1, "recent_changes: " + show(rc))
    src.write("F2.java", java("p", "F2", "shallowNew"))
    src.commit("c3")
    dst.git("pull", "-q", "--no-rebase")
    c.expect("shallowNew" in names(ctx.m, name, "shallowNew"), "after pull in shallow clone")


@case("O13", "indexes only the src/ folder of a git repo", "M",
      "git tools still work (git root found upward from the indexed folder)")
def o13(ctx, c):
    name = ctx.rname("o13")
    r = Repo.create(name)
    r.write("src/F1.java", java("p", "F1"))
    r.write("docs/readme.md", "# docs\n")
    r.commit("init")
    ctx.m.call("index", {"repo": name, "root": os.path.join(r.path, "src")})
    ctx.m.call("search_symbols", {"repo": name, "query": "base"})
    add_method(r, "src/F1.java", "o13Edit")
    paths, res = wc_paths(ctx.m, name)
    c.expect(paths is not None and any(p.endswith("F1.java") for p in paths), "working_changes: " + show(res))


@case("O14", "relative root path", "L", "rejected with a clear -32602 (client cannot know the server cwd)")
def o14(ctx, c):
    srv = ctx.server(cwd=WORK)
    rel = "rel%s" % RUN
    r = Repo.create(rel)
    r.write("F1.java", java("p", "F1"))
    r.commit("init")
    res = srv.call("index", {"repo": ctx.rname("o14"), "root": rel})
    c.expect((not res.ok) and res.code == -32602, show(res))


@case("O15", "typo: root directory does not exist", "M", "-32602 with a clear message")
def o15(ctx, c):
    res = ctx.m.call("index", {"repo": ctx.rname("o15"), "root": os.path.join(WORK, "no-such-dir-xyz")})
    c.expect((not res.ok) and res.code == -32602, show(res))


@case("O16", "root points at a file", "L", "-32602 with a clear message")
def o16(ctx, c):
    r = Repo.create(ctx.rname("o16src"))
    r.write("F1.java", java("p", "F1"))
    r.commit("init")
    res = ctx.m.call("index", {"repo": ctx.rname("o16"), "root": os.path.join(r.path, "F1.java")})
    c.expect((not res.ok) and res.code == -32602, show(res))


@case("O17", "re-uses a repo name for a different checkout", "M",
      "rejected/warned, or the old checkout's symbols are purged — no orphans")
def o17(ctx, c):
    name = ctx.rname("o17")
    a, b = Repo.create(name + "a"), Repo.create(name + "b")
    a.write("OnlyA.java", java("p", "OnlyA", "onlyAsym"))
    a.commit("a")
    b.write("OnlyB.java", java("p", "OnlyB", "onlyBsym"))
    b.commit("b")
    ctx.m.call("index", {"repo": name, "root": a.path})
    rb = ctx.m.call("index", {"repo": name, "root": b.path})
    stale = has_exact(ctx.m, name, "onlyAsym")
    c.expect((not rb.ok) or has_signal(rb.data) or not stale,
             "second index: %s ; stale symbol from first checkout still found: %s" % (show(rb), stale))


@case("O18", "first index of a large repo", "M",
      "returns within a client timeout, or reports progress/async (projected 20k files <= 120 s)")
def o18(ctx, c):
    n = 1500
    name = ctx.rname("o18")
    r = Repo.create(name)
    for i in range(n):
        r.write("pkg%d/C%d.java" % (i % 30, i), java("pkg%d" % (i % 30), "C%d" % i, "a", "b", "c"))
    r.commit("big")
    t0 = time.time()
    res = ctx.m.call("index", {"repo": name, "root": r.path}, timeout=900)
    dt = time.time() - t0
    rate = n / dt if dt > 0 else 0
    projected = 20000 / rate if rate else 9999
    c.note("%d files in %.1fs = %.0f files/s ; 20k files would take ~%.0fs" % (n, dt, rate, projected))
    c.expect(res.ok and projected <= 120, "projected %.0fs for 20k files; blocking call, no progress" % projected)


@case("O20", "indexes only src/, then commits a new method", "H",
      "freshness re-sync still works: the new symbol is searchable after the commit")
def o20(ctx, c):
    name = ctx.rname("o20")
    r = Repo.create(name)
    r.write("src/F1.java", java("p", "F1"))
    r.write("docs/readme.md", "# docs\n")
    r.commit("init")
    ctx.m.call("index", {"repo": name, "root": os.path.join(r.path, "src")})
    ctx.m.call("search_symbols", {"repo": name, "query": "base"})
    add_method(r, "src/F1.java", "o20New")
    r.commit("add method")
    c.expect(bool(names(ctx.m, name, "o20New")), "o20New not searchable after commit (index never re-synced)")


# ================================================================ B: branches & pull
@case("B01", "switch feature <-> main repeatedly", "H",
      "index follows HEAD correctly in both directions, every time")
def b01(ctx, c):
    name, r = seeded(ctx, "b01")
    b0 = r.branch()
    r.checkout("-b", "feat")
    add_method(r, "F1.java", "featMod")
    r.write("Feat.java", java("p", "Feat", "featNew"))
    r.git("rm", "-q", "F5.java")
    r.commit("feat")
    for rnd in range(1, 4):
        on_feat = names(ctx.m, name, "featNew") and names(ctx.m, name, "featMod")
        no_f5 = not any("F5.java" in i for i in sids(ctx.m, name, "base"))
        c.expect(on_feat and no_f5, "round %d on feat: featNew+featMod=%s F5 gone=%s" % (rnd, bool(on_feat), no_f5))
        r.checkout(b0)
        gone = not has_exact(ctx.m, name, "featNew") and not has_exact(ctx.m, name, "featMod")
        f5_back = any("F5.java" in i for i in sids(ctx.m, name, "base"))
        c.expect(gone and f5_back, "round %d on %s: feature symbols gone=%s F5 back=%s" % (rnd, b0, gone, f5_back))
        r.checkout("feat")


@case("B02", "git pull fast-forward on the current branch", "H", "new upstream symbols searchable on next read")
def b02(ctx, c):
    src = Repo.create(ctx.rname("b02src"))
    for i in range(1, 4):
        src.write("F%d.java" % i, java("p", "F%d" % i))
    src.commit("init")
    dst = clone(src, ctx.rname("b02dst"))
    name = ctx.rname("b02")
    ctx.m.call("index", {"repo": name, "root": dst.path})
    ctx.m.call("search_symbols", {"repo": name, "query": "base"})
    src.write("Pulled.java", java("p", "Pulled", "pulledSym"))
    src.commit("up")
    dst.git("pull", "-q", "--no-rebase")
    c.expect("pulledSym" in names(ctx.m, name, "pulledSym"), "after pull")


def _divergent(ctx, cid, pull_args):
    src = Repo.create(ctx.rname(cid + "src"))
    for i in range(1, 4):
        src.write("F%d.java" % i, java("p", "F%d" % i))
    src.commit("init")
    dst = clone(src, ctx.rname(cid + "dst"))
    name = ctx.rname(cid)
    ctx.m.call("index", {"repo": name, "root": dst.path})
    ctx.m.call("search_symbols", {"repo": name, "query": "base"})
    add_method(dst, "F1.java", "localSym")
    dst.commit("local")
    add_method(src, "F2.java", "remoteSym")
    src.commit("remote")
    dst.git(*pull_args)
    return name


@case("B03", "git pull with divergent commits (merge commit)", "M", "both local and upstream symbols present")
def b03(ctx, c):
    name = _divergent(ctx, "b03", ("pull", "-q", "--no-rebase", "--no-edit"))
    c.expect(names(ctx.m, name, "localSym") and names(ctx.m, name, "remoteSym"),
             "local=%s remote=%s" % (names(ctx.m, name, "localSym"), names(ctx.m, name, "remoteSym")))


@case("B04", "git pull --rebase with local commits", "M", "both present, nothing stale")
def b04(ctx, c):
    name = _divergent(ctx, "b04", ("pull", "-q", "--rebase"))
    c.expect(names(ctx.m, name, "localSym") and names(ctx.m, name, "remoteSym"),
             "local=%s remote=%s" % (names(ctx.m, name, "localSym"), names(ctx.m, name, "remoteSym")))


@case("B05", "switch to another branch, then pull there", "M", "index reflects that branch's pulled state")
def b05(ctx, c):
    src = Repo.create(ctx.rname("b05src"))
    for i in range(1, 4):
        src.write("F%d.java" % i, java("p", "F%d" % i))
    src.commit("init")
    b0 = src.branch()
    src.checkout("-b", "feat")
    src.write("Feat.java", java("p", "Feat", "featA"))
    src.commit("featA")
    src.checkout(b0)
    dst = clone(src, ctx.rname("b05dst"))
    name = ctx.rname("b05")
    ctx.m.call("index", {"repo": name, "root": dst.path})
    ctx.m.call("search_symbols", {"repo": name, "query": "base"})
    dst.checkout("feat")
    src.checkout("feat")
    add_method(src, "Feat.java", "featB")
    src.commit("featB")
    src.checkout(b0)
    dst.git("pull", "-q", "--no-rebase")
    c.expect(names(ctx.m, name, "featA") and names(ctx.m, name, "featB"),
             "featA=%s featB=%s" % (names(ctx.m, name, "featA"), names(ctx.m, name, "featB")))


@case("B06", "pull brings upstream deletes and renames", "M",
      "deleted symbols gone, renamed file indexed under its new path")
def b06(ctx, c):
    src = Repo.create(ctx.rname("b06src"))
    for i in range(1, 6):
        src.write("F%d.java" % i, java("p", "F%d" % i))
    src.commit("init")
    dst = clone(src, ctx.rname("b06dst"))
    name = ctx.rname("b06")
    ctx.m.call("index", {"repo": name, "root": dst.path})
    ctx.m.call("search_symbols", {"repo": name, "query": "base"})
    src.git("rm", "-q", "F5.java")
    src.git("mv", "F4.java", "R4.java")
    src.commit("del+rename")
    dst.git("pull", "-q", "--no-rebase")
    ids = sids(ctx.m, name, "base")
    c.expect(not any("F5.java" in i or "F4.java" in i for i in ids) and any("R4.java" in i for i in ids),
             "ids=%s" % sorted(i.split(":")[1] for i in ids if ":" in i))


@case("B07", "switch branch carrying uncommitted edits", "M",
      "working_changes is relative to the new HEAD; index follows the new HEAD")
def b07(ctx, c):
    name, r = seeded(ctx, "b07")
    b0 = r.branch()
    r.checkout("-b", "feat")
    r.write("Feat.java", java("p", "Feat", "featSym"))
    r.commit("feat")
    r.checkout(b0)
    add_method(r, "F1.java", "carried")
    r.checkout("feat")
    paths, res = wc_paths(ctx.m, name)
    c.expect(paths is not None and any(p.endswith("F1.java") for p in paths), "working_changes: " + show(res))
    c.expect(bool(names(ctx.m, name, "featSym")), "index follows the switch to feat")


@case("B08", "rebase a feature branch onto updated main", "M", "index shows both sets of changes")
def b08(ctx, c):
    name, r = seeded(ctx, "b08")
    b0 = r.branch()
    r.checkout("-b", "feat")
    add_method(r, "F4.java", "featNew")
    r.commit("feat")
    r.checkout(b0)
    add_method(r, "F3.java", "mainNew")
    r.commit("main")
    r.checkout("feat")
    names(ctx.m, name, "featNew")
    r.git("rebase", "-q", b0)
    c.expect(names(ctx.m, name, "featNew") and names(ctx.m, name, "mainNew"),
             "featNew=%s mainNew=%s" % (names(ctx.m, name, "featNew"), names(ctx.m, name, "mainNew")))


@case("B09", "checkout an older commit (detached HEAD)", "M", "symbols from later commits disappear")
def b09(ctx, c):
    name, r = seeded(ctx, "b09")
    add_method(r, "F3.java", "laterSym")
    r.commit("later")
    c.expect(bool(names(ctx.m, name, "laterSym")), "fixture: laterSym searchable after commit")
    r.checkout("--detach", "HEAD~1")
    c.expect(not has_exact(ctx.m, name, "laterSym"), "laterSym still found after detaching to HEAD~1")


@case("B10", "which branch do the symbols reflect?", "M",
      "some tool exposes the branch/HEAD the index mirrors")
def b10(ctx, c):
    name, r = seeded(ctx, "b10")
    parts = []
    for tool, args in (("sync", {"repo": name, "root": r.path}), ("index", {"repo": name, "root": r.path}),
                       ("recent_changes", {"repo": name}), ("working_changes", {"repo": name})):
        res = ctx.m.call(tool, args)
        parts.append("%s: %s" % (tool, show(res, 120)))
        if res.ok and "branch" in json.dumps(res.data).lower():
            c.expect(True)
            return
    tools = ctx.m.tools()
    c.expect(any("branch" in t for t in tools), "no 'branch' in sync/index/recent/working results or tool names; " + " | ".join(parts))


@case("B11", "switch between very different branches", "M",
      "re-sync after a switch stays interactive (<= 15 s for 300 changed files)")
def b11(ctx, c):
    name, r = seeded(ctx, "b11", n=400)
    b0 = r.branch()
    r.checkout("-b", "feat")
    for i in range(1, 301):
        add_method(r, "F%d.java" % i, "b11m")
    r.commit("many")
    r.checkout(b0)
    r.checkout("feat")
    t0 = time.time()
    names(ctx.m, name, "b11m")
    t_to_feat = time.time() - t0
    r.checkout(b0)
    t0 = time.time()
    names(ctx.m, name, "b11m")
    t_back = time.time() - t0
    c.note("first read after switch: ->feat %.1fs, ->%s %.1fs" % (t_to_feat, b0, t_back))
    c.expect(max(t_to_feat, t_back) <= 15, "switch re-sync took %.1fs / %.1fs" % (t_to_feat, t_back))


@case("B12", "session facts recorded on different branches", "L", "facts carry the branch they were made on")
def b12(ctx, c):
    s = ctx.m.call("session_start", {"task": "branch work"})
    sid = s.data["id"]
    ctx.m.call("session_fact", {"id": sid, "repo": ctx.rname("b12"), "file": "F1.java",
                                "what": "edit on feat", "branch": "feat"})
    rec = ctx.m.call("session_recall", {"id": sid, "full": True})
    fact = rec.data["facts"][0] if rec.ok and rec.data.get("facts") else {}
    c.expect("branch" in fact, "recalled fact keys: %s" % sorted(fact.keys()))


# ================================================================ M: multi-module repos
MODSRC = "src/main/java/com/acme"


def modrepo(ctx, cid):
    """A Maven-style two-module repo (core, web); web calls core. Committed, NOT indexed."""
    name = ctx.rname(cid)
    r = Repo.create(name)
    r.write("pom.xml", "<project><modules><module>core</module><module>web</module></modules></project>\n")
    r.write(".gitignore", "target/\n")
    r.write("core/pom.xml", "<project><artifactId>core</artifactId></project>\n")
    r.write("web/pom.xml", "<project><artifactId>web</artifactId></project>\n")
    r.write("core/%s/core/Util.java" % MODSRC, java("com.acme.core", "Util", "helper"))
    r.write("web/%s/web/Controller.java" % MODSRC,
            "package com.acme.web;\npublic class Controller {\n"
            "  public int handle() { return new com.acme.core.Util().helper(); }\n}\n")
    r.commit("init")
    return name, r


CORE_UTIL = "core/%s/core/Util.java" % MODSRC
WEB_CTRL = "web/%s/web/Controller.java" % MODSRC


@case("M01", "indexes a multi-module repo at its root", "-",
      "symbols of every module are found, ids carry the module directory")
def m01(ctx, c):
    name, r = modrepo(ctx, "m01")
    ctx.m.call("index", {"repo": name, "root": r.path})
    h, hd = sids(ctx.m, name, "helper"), sids(ctx.m, name, "handle")
    c.expect(any("core/" in i for i in h) and any("web/" in i for i in hd), "helper=%s handle=%s" % (h, hd))


@case("M02", "same class name in two modules", "M", "both found and distinguishable by module path")
def m02(ctx, c):
    name, r = modrepo(ctx, "m02")
    for mod in ("core", "web"):
        r.write("%s/%s/Config.java" % (mod, MODSRC), java("com.acme", "Config", "cfgMethod"))
    r.commit("dup")
    ctx.m.call("index", {"repo": name, "root": r.path})
    got = sids(ctx.m, name, "cfgMethod")
    c.expect(any("core/" in i for i in got) and any("web/" in i for i in got) and len(set(got)) == 2, "ids=%s" % got)


@case("M03", "a call from one module into another", "M", "impact_of lists the cross-module caller")
def m03(ctx, c):
    name, r = modrepo(ctx, "m03")
    ctx.m.call("index", {"repo": name, "root": r.path})
    target = [i for i in sids(ctx.m, name, "helper") if "core/" in i]
    imp = ctx.m.call("impact_of", {"symbolId": target[0]}) if target else None
    callers = (imp.data.get("callers") if imp and imp.ok and isinstance(imp.data, dict) else None) or []
    c.expect(any("web/" in x for x in callers), "callers=%s" % callers)


@case("M04", "edits then commits inside one module (repo indexed at the repo root)", "-",
      "working_changes shows the module path; the commit is searchable")
def m04(ctx, c):
    name, r = modrepo(ctx, "m04")
    ctx.m.call("index", {"repo": name, "root": r.path})
    ctx.m.call("search_symbols", {"repo": name, "query": "handle"})
    add_method(r, WEB_CTRL, "m04Edit")
    paths, res = wc_paths(ctx.m, name)
    c.expect(paths is not None and any(p.startswith("web/") for p in paths), "working_changes: " + show(res))
    r.commit("web change")
    c.expect(bool(names(ctx.m, name, "m04Edit")), "committed module change not searchable")


@case("M05", "each module indexed as its own repo (root = module dir)", "H",
      "working_changes is scoped to the module and paths are module-relative")
def m05(ctx, c):
    name, r = modrepo(ctx, "m05")
    web, core = name + "w", name + "c"
    ctx.m.call("index", {"repo": web, "root": os.path.join(r.path, "web")})
    ctx.m.call("index", {"repo": core, "root": os.path.join(r.path, "core")})
    ctx.m.call("search_symbols", {"repo": web, "query": "handle"})
    ctx.m.call("search_symbols", {"repo": core, "query": "helper"})
    add_method(r, WEB_CTRL, "mWebEdit")
    add_method(r, CORE_UTIL, "mCoreEdit")
    pw, rw = wc_paths(ctx.m, web)
    pc, rc = wc_paths(ctx.m, core)
    c.expect(pw is not None and len(pw) == 1 and pw[0].endswith("Controller.java") and not pw[0].startswith(("web/", "core/")),
             "web repo working_changes: %s" % (pw if pw is not None else show(rw)))
    c.expect(pc is not None and len(pc) == 1 and pc[0].endswith("Util.java") and not pc[0].startswith(("web/", "core/")),
             "core repo working_changes: %s" % (pc if pc is not None else show(rc)))


@case("M06", "commit in one module while another module is indexed as its own repo", "H",
      "each module repo re-syncs on its own changes; the sibling's changes do not leak in")
def m06(ctx, c):
    name, r = modrepo(ctx, "m06")
    web, core = name + "w", name + "c"
    ctx.m.call("index", {"repo": web, "root": os.path.join(r.path, "web")})
    ctx.m.call("index", {"repo": core, "root": os.path.join(r.path, "core")})
    ctx.m.call("search_symbols", {"repo": web, "query": "handle"})
    ctx.m.call("search_symbols", {"repo": core, "query": "helper"})
    add_method(r, WEB_CTRL, "m06Web")
    add_method(r, CORE_UTIL, "m06Core")
    r.commit("both modules")
    c.expect(bool(names(ctx.m, web, "m06Web")), "web repo did not pick up its own commit")
    c.expect(bool(names(ctx.m, core, "m06Core")), "core repo did not pick up its own commit")
    c.expect(not has_exact(ctx.m, web, "m06Core") and not has_exact(ctx.m, core, "m06Web"),
             "sibling module's symbols leaked in")


@case("M07", "module build output (target/) is gitignored", "H", "build output of a module is not indexed")
def m07(ctx, c):
    name, r = modrepo(ctx, "m07")
    r.write("web/target/classes/Gen.java", java("gen", "Gen", "modGen"))
    ctx.m.call("index", {"repo": name, "root": r.path})
    c.expect(not has_exact(ctx.m, name, "modGen"), "web/target/classes/Gen.java was indexed")


@case("M08", "which modules does this repo have?", "M", "an overview lists the modules (pom.xml directories)")
def m08(ctx, c):
    name, r = modrepo(ctx, "m08")
    ctx.m.call("index", {"repo": name, "root": r.path})
    tools = ctx.m.tools()
    tool = next((t for t in tools if "overview" in t), None)
    if tool is None:
        c.expect(False, "no overview tool (tools: %s)" % ", ".join(tools))
        return
    res = ctx.m.call(tool, {"repo": name})
    blob = json.dumps(res.data) if res.ok else ""
    c.expect(res.ok and "core" in blob and "web" in blob and "module" in blob.lower(), show(res, 300))


@case("M09", "search restricted to one module", "M", "search_symbols accepts a path prefix and honours it")
def m09(ctx, c):
    name, r = modrepo(ctx, "m09")
    r.write("web/%s/web/Other.java" % MODSRC, java("com.acme.web", "Other", "helper"))
    r.commit("dup name")
    ctx.m.call("index", {"repo": name, "root": r.path})
    res = ctx.m.call("search_symbols", {"repo": name, "query": "helper", "pathPrefix": "web/"})
    got = [s.get("id", "") for s in res.data] if res.ok and isinstance(res.data, list) else []
    c.expect(bool(got) and all(":web/" in i for i in got), "ids=%s" % got)


# ================================================================ G: git / file lifecycle
@case("G01", "creates a new file, not yet git-added", "H", "working_changes lists it")
def g01(ctx, c):
    name, r = seeded(ctx, "g01")
    r.write("NewFile.java", java("p", "NewFile", "newOne"))
    paths, res = wc_paths(ctx.m, name)
    c.expect(paths is not None and any(p.endswith("NewFile.java") for p in paths), "working_changes: " + show(res))


@case("G02", "stages an edit (git add)", "-", "working_changes lists it")
def g02(ctx, c):
    name, r = seeded(ctx, "g02")
    add_method(r, "F1.java", "g02m")
    r.git("add", "F1.java")
    paths, res = wc_paths(ctx.m, name)
    c.expect(paths is not None and any(p.endswith("F1.java") for p in paths), "working_changes: " + show(res))


@case("G03", "edits then reverts", "-", "working_changes no longer lists the file")
def g03(ctx, c):
    name, r = seeded(ctx, "g03")
    add_method(r, "F2.java", "g03m")
    r.git("checkout", "--", "F2.java")
    paths, res = wc_paths(ctx.m, name)
    c.expect(paths is not None and not any(p.endswith("F2.java") for p in paths), "working_changes: " + show(res))


@case("G04", "commits", "-", "next read finds the new symbol")
def g04(ctx, c):
    name, r = seeded(ctx, "g04")
    add_method(r, "F2.java", "g04Added")
    r.commit("add")
    c.expect(bool(names(ctx.m, name, "g04Added")), "after commit")


@case("G05", "git commit --amend", "M", "amended content indexed, old symbol gone")
def g05(ctx, c):
    name, r = seeded(ctx, "g05")
    add_method(r, "F3.java", "g05Old")
    r.commit("add")
    names(ctx.m, name, "g05Old")
    replace_in(r, "F3.java", "g05Old", "g05New")
    r.git("commit", "-q", "-a", "--amend", "--no-edit")
    new, old = has_exact(ctx.m, name, "g05New"), has_exact(ctx.m, name, "g05Old")
    c.expect(new and not old, "new=%s old(stale)=%s" % (new, old))


@case("G05b", "amend then gc prunes the indexed cursor commit (history rewrite)", "H",
      "sync falls back to a full index; new symbol found, old gone")
def g05b(ctx, c):
    name, r = seeded(ctx, "g05b")
    add_method(r, "F3.java", "g05bOld")
    r.commit("add")
    names(ctx.m, name, "g05bOld")
    replace_in(r, "F3.java", "g05bOld", "g05bNew")
    r.git("commit", "-q", "-a", "--amend", "--no-edit")
    r.git("reflog", "expire", "--expire=now", "--all")
    r.git("gc", "-q", "--prune=now")
    new, old = has_exact(ctx.m, name, "g05bNew"), has_exact(ctx.m, name, "g05bOld")
    c.expect(new and not old, "new=%s old(stale)=%s" % (new, old))


@case("G06", "git reset --hard HEAD~1", "M", "symbols from the dropped commit gone")
def g06(ctx, c):
    name, r = seeded(ctx, "g06")
    add_method(r, "F4.java", "g06Added")
    r.commit("add")
    names(ctx.m, name, "g06Added")
    r.git("reset", "-q", "--hard", "HEAD~1")
    c.expect(not has_exact(ctx.m, name, "g06Added"), "dropped symbol still found")


@case("G07", "merge leaves conflict markers in a file", "M", "index reports the file as unparsed")
def g07(ctx, c):
    name, r = seeded(ctx, "g07")
    b0 = r.branch()
    r.checkout("-b", "other")
    replace_in(r, "F5.java", "return 1", "return 2")
    r.commit("o")
    r.checkout(b0)
    replace_in(r, "F5.java", "return 1", "return 3")
    r.commit("m")
    r.git("merge", "-q", "other", check=False)
    if "<<<<<<<" not in r.read("F5.java"):
        raise RuntimeError("fixture: merge produced no conflict markers")
    res = ctx.m.call("index", {"repo": name, "root": r.path})
    c.expect(res.ok and has_signal(res.data), show(res))


@case("G08", "git stash, then stash pop", "-", "working_changes empties, then returns")
def g08(ctx, c):
    name, r = seeded(ctx, "g08")
    add_method(r, "F1.java", "g08m")
    r.git("stash", "-q")
    p1, _ = wc_paths(ctx.m, name)
    r.git("stash", "pop", "-q")
    p2, _ = wc_paths(ctx.m, name)
    c.expect(p1 == [] and p2 is not None and any(p.endswith("F1.java") for p in p2), "after stash=%s after pop=%s" % (p1, p2))


@case("G09", "deletes a file and commits", "-", "its symbols gone")
def g09(ctx, c):
    name, r = seeded(ctx, "g09")
    r.git("rm", "-q", "F5.java")
    r.commit("rm")
    c.expect(not any("F5.java" in i for i in sids(ctx.m, name, "base")), "F5 symbols remain")


@case("G10", "renames a file (git mv) and commits", "M", "old path gone, new path indexed")
def g10(ctx, c):
    name, r = seeded(ctx, "g10")
    r.git("mv", "F1.java", "R1.java")
    r.commit("mv")
    ids = sids(ctx.m, name, "base")
    c.expect(any("R1.java" in i for i in ids) and not any("F1.java" in i for i in ids), "ids=%s" % ids)
    r.git("mv", "F2.java", "R2.java")
    res = ctx.m.call("working_changes", {"repo": name})
    c.note("uncommitted rename shows as: %s" % ([(w["path"], w["changeType"]) for w in res.data] if res.ok else show(res)))


@case("G11", "gitignored build output present on disk", "H", "ignored files are not indexed")
def g11(ctx, c):
    name = ctx.rname("g11")
    r = Repo.create(name)
    r.write(".gitignore", "build/\n")
    r.write("F1.java", java("p", "F1"))
    r.commit("init")
    r.write("build/gen/Gen.java", java("gen", "Gen", "ignoredGen"))
    ctx.m.call("index", {"repo": name, "root": r.path})
    c.expect(not has_exact(ctx.m, name, "ignoredGen"), "gitignored build/gen/Gen.java was indexed")


# ================================================================ F: file kinds
@case("F01", "non-UTF-8 (Latin-1) source file in the repo", "H", "index continues; one bad file never aborts the run")
def f01(ctx, c):
    name = ctx.rname("f01")
    r = Repo.create(name)
    for i in range(1, 4):
        r.write("Good%d.java" % i, java("p", "Good%d" % i))
    r.write("Bad.java", b"package p;\n// caf\xe9\npublic class Bad { public int bad() { return 1; } }\n", binary=True)
    r.commit("init")
    res = ctx.m.call("index", {"repo": name, "root": r.path})
    c.expect(res.ok and res.data.get("filesIndexed", 0) >= 3, show(res))


@case("F02", "source file with BOM + CRLF line endings", "M", "parsed normally")
def f02(ctx, c):
    name = ctx.rname("f02")
    r = Repo.create(name)
    r.write("Bom.java", b"\xef\xbb\xbf" + java("p", "Bom", "bomMethod").replace("\n", "\r\n").encode(), binary=True)
    r.commit("init")
    ctx.m.call("index", {"repo": name, "root": r.path})
    c.expect(bool(names(ctx.m, name, "bomMethod")), "bomMethod not found")


@case("F03", "binary file named *.java", "H", "index continues")
def f03(ctx, c):
    name = ctx.rname("f03")
    r = Repo.create(name)
    for i in range(1, 3):
        r.write("Good%d.java" % i, java("p", "Good%d" % i))
    r.write("Blob.java", bytes(range(256)) * 4, binary=True)
    r.commit("init")
    res = ctx.m.call("index", {"repo": name, "root": r.path})
    c.expect(res.ok and res.data.get("filesIndexed", 0) >= 2, show(res))


@case("F04", "empty and comment-only source files", "-", "no crash")
def f04(ctx, c):
    name = ctx.rname("f04")
    r = Repo.create(name)
    r.write("Empty.java", "")
    r.write("OnlyComment.java", "// nothing here\n")
    r.write("Ok.java", java("p", "Ok"))
    r.commit("init")
    res = ctx.m.call("index", {"repo": name, "root": r.path})
    c.expect(res.ok, show(res))


# ================================================================ T / S
@case("T01", "edits repo A while repo B is also indexed", "-", "the two stay independent")
def t01(ctx, c):
    a, ra = seeded(ctx, "t01a")
    b, rb = seeded(ctx, "t01b")
    add_method(ra, "F1.java", "t01m")
    pa, _ = wc_paths(ctx.m, a)
    pb, _ = wc_paths(ctx.m, b)
    c.expect(pa is not None and len(pa) == 1 and pb == [], "A=%s B=%s" % (pa, pb))


@case("T02", "uses a git worktree", "M", "git tools work inside the worktree")
def t02(ctx, c):
    name, r = seeded(ctx, "t02")
    wt = os.path.join(WORK, name + "-wt")
    shutil.rmtree(wt, ignore_errors=True)
    r.git("worktree", "add", "-q", wt, "-b", "wtb", check=False)
    if not os.path.isdir(wt):
        raise Skip("git worktree unavailable")
    wname = name + "wt"
    ctx.m.call("index", {"repo": wname, "root": wt})
    ctx.m.call("search_symbols", {"repo": wname, "query": "base"})
    add_method(Repo(wt), "F1.java", "t02m")
    paths, res = wc_paths(ctx.m, wname)
    c.expect(paths is not None and any(p.endswith("F1.java") for p in paths), "working_changes in worktree: " + show(res))


@case("T03", "MCP client reads stdout of a default-launched server", "H", "stdout carries only JSON-RPC (no banner/log lines)")
def t03(ctx, c):
    m = ctx.server()
    m.call("list_repos", {})
    c.expect(not m.noise, "non-JSON lines on stdout: %r" % (m.noise[:3],))


@case("S01", "closes the agent, returns later (server restart)", "-", "sessions, repos tag and index persist")
def s01(ctx, c):
    a = ctx.server()
    name, r = seeded(ctx, "s01", server=a)
    sid = a.call("session_start", {"task": "resume test"}).data["id"]
    for i in range(3):
        a.call("session_fact", {"id": sid, "repo": name, "file": "F%d.java" % i, "what": "edit %d" % i})
    a.close()
    b = ctx.server()
    lst = b.call("session_list", {"repo": name})
    rec = b.call("session_recall", {"id": sid, "full": True})
    c.expect(lst.ok and sid in [h["id"] for h in lst.data], "session_list(repo): " + show(lst))
    c.expect(rec.ok and len(rec.data["facts"]) == 3, "recall: " + show(rec))
    c.expect(bool(names(b, name, "base")), "index searchable after restart without re-index")


@case("S02", "two tasks in parallel on the same repo", "-", "both listed for the repo, facts not mixed")
def s02(ctx, c):
    name, r = seeded(ctx, "s02")
    s1 = ctx.m.call("session_start", {"task": "task one"}).data["id"]
    s2 = ctx.m.call("session_start", {"task": "task two"}).data["id"]
    ctx.m.call("session_fact", {"id": s1, "repo": name, "file": "F1.java", "what": "one-a"})
    ctx.m.call("session_fact", {"id": s1, "repo": name, "file": "F2.java", "what": "one-b"})
    ctx.m.call("session_fact", {"id": s2, "repo": name, "file": "F3.java", "what": "two-a"})
    lst = ctx.m.call("session_list", {"repo": name})
    ids = [h["id"] for h in lst.data] if lst.ok else []
    f1 = [f["what"] for f in ctx.m.call("session_recall", {"id": s1, "full": True}).data["facts"]]
    f2 = [f["what"] for f in ctx.m.call("session_recall", {"id": s2, "full": True}).data["facts"]]
    c.expect(s1 in ids and s2 in ids and f1 == ["one-a", "one-b"] and f2 == ["two-a"], "list=%s f1=%s f2=%s" % (ids, f1, f2))


@case("S03", "agent records a fact with a typo'd repo name", "L", "rejected or warned, not silently tagged onto the session")
def s03(ctx, c):
    sid = ctx.m.call("session_start", {"task": "typo test"}).data["id"]
    typo = "reppo-typo-%s" % RUN
    res = ctx.m.call("session_fact", {"id": sid, "repo": typo, "file": "F1.java", "what": "edit"})
    rec = ctx.m.call("session_recall", {"id": sid})
    c.note("recalled repos tag: %s" % (rec.data.get("repos") if rec.ok else show(rec)))
    c.expect((not res.ok) or has_signal(res.data, ("warn", "unknown")), "session_fact accepted unknown repo: " + show(res))


# ================================================================ SM: agent-authored repo meta (C' half-1)
@case("SM01", "agent attaches a purpose note with set_summary; it shows in get_digest and "
      "auto-invalidates when the symbol's contract changes", "H",
      "set_summary stores the note (stamped with the symbol's content hash); get_digest surfaces it; "
      "after the symbol's hash changes (signature/doc/call edges) the stale note is no longer served; "
      "bad input is -32602")
def sm01(ctx, c):
    name, r = seeded(ctx, "sm01", n=1)
    ids = sids(ctx.m, name, "base")
    c.expect(bool(ids), "no symbol to annotate: " + show(ctx.m.call("search_symbols", {"repo": name, "query": "base"})))
    if not ids:
        return
    sid = ids[0]

    note = "orchestrates the F1 request flow"
    setr = ctx.m.call("set_summary", {"symbolId": sid, "text": note})
    c.expect(setr.ok and isinstance(setr.data, dict) and setr.data.get("stored") is True,
             "set_summary did not store: " + show(setr))

    dig = ctx.m.call("get_digest", {"symbolId": sid})
    shown = (dig.data or {}).get("summary") if dig.ok else None
    c.expect(isinstance(shown, dict) and shown.get("text") == note,
             "get_digest did not surface the note: " + show(dig))

    # change base's contract (it now calls a new method -> its call edges, hence content hash, change;
    # symbolPath base() is unchanged so the id stays valid). The stale note must not be served.
    replace_in(r, "F1.java", "public int base() { return 1; }",
               "public int helper() { return 2; }\n  public int base() { return helper(); }")
    r.commit("base now calls helper (hash changes)")
    dig2 = ctx.m.call("get_digest", {"symbolId": sid})
    shown2 = (dig2.data or {}).get("summary") if dig2.ok else None
    served_stale = isinstance(shown2, dict) and shown2.get("text") == note
    c.expect(dig2.ok and not served_stale, "stale note served after the contract changed: " + show(dig2))

    bad = ctx.m.call("set_summary", {"symbolId": name + ":F1.java:p.F1#ghost()", "text": "x"})
    c.expect((not bad.ok) and bad.code == -32602, "unknown symbol not rejected -32602: " + show(bad))
    blank = ctx.m.call("set_summary", {"symbolId": sid, "text": "   "})
    c.expect((not blank.ok) and blank.code == -32602, "blank text not rejected -32602: " + show(blank))


# ================================================================ reporting / main
def write_report(path, results, meta):
    gaps = [(cs, c) for cs, st, c, dt in results if st == "GAP"]
    order = {"H": 0, "M": 1, "L": 2, "-": 3}
    gaps.sort(key=lambda x: (order.get(x[0]["sev"], 9), x[0]["id"]))
    counts = {}
    for _, st, _, _ in results:
        counts[st] = counts.get(st, 0) + 1
    out = ["# Developer-activity gap report", "",
           "Generated by `e2e/dev_activity.py` against Dylext `%s` on %s." % (meta["rev"], meta["when"]), "",
           "Plan and case catalog: [`DEV-ACTIVITY-TEST-PLAN.md`](DEV-ACTIVITY-TEST-PLAN.md).", "",
           "**Totals:** " + ", ".join("%s %d" % (k, counts[k]) for k in ("PASS", "GAP", "ERROR", "SKIP") if k in counts),
           "", "## Gaps (ranked by severity)", ""]
    if not gaps:
        out.append("_None found._")
    for cs, c in gaps:
        out.append("### %s — %s  (severity %s)" % (cs["id"], cs["title"], cs["sev"]))
        for want, got in c.fails:
            out.append("- **Desired:** %s" % want)
            out.append("- **Observed:** `%s`" % got.replace("`", "'"))
        for n in c.notes:
            out.append("- _note:_ %s" % n.replace("`", "'"))
        out.append("")
    out += ["## All cases", "", "| ID | Status | Sev | Activity |", "|---|---|---|---|"]
    for cs, st, c, dt in results:
        out.append("| %s | %s | %s | %s |" % (cs["id"], st, cs["sev"], cs["title"]))
    with io.open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="", help="comma list of case ids or group letters, e.g. O,B05")
    ap.add_argument("--report", default="", help="write a markdown report to this path")
    ap.add_argument("--strict", action="store_true", help="exit 1 if any case is a GAP or ERROR (CI gate)")
    a = ap.parse_args()
    if not os.path.exists(JAR):
        sys.exit("jar not found: %s (run: mvn -B -DskipTests package)" % JAR)
    shutil.rmtree(WORK, ignore_errors=True)
    os.makedirs(WORK)
    sel = [t.strip().upper() for t in a.only.split(",") if t.strip()]
    ctx, results = Ctx(), []
    try:
        for cs in CASES:
            if sel and not any(cs["id"].startswith(t) for t in sel):
                continue
            c, t0 = Chk(cs["desired"]), time.time()
            try:
                cs["fn"](ctx, c)
                status = "GAP" if c.fails else "PASS"
            except Skip as e:
                status = "SKIP"
                c.notes.append(str(e))
            except Exception as e:  # harness problem — never reported as a gap
                status = "ERROR"
                c.notes.append("harness error: %r" % (e,))
            dt = time.time() - t0
            results.append((cs, status, c, dt))
            print("[%-5s] %s  %s  (%.1fs)" % (status, cs["id"], cs["title"], dt))
            for want, got in c.fails:
                print("          wanted: %s\n          got   : %s" % (want, got))
            for n in c.notes:
                print("          note  : %s" % n)
    finally:
        ctx.close_all()
    counts = {}
    for _, st, _, _ in results:
        counts[st] = counts.get(st, 0) + 1
    print("\nSUMMARY: " + ", ".join("%s=%d" % (k, counts[k]) for k in ("PASS", "GAP", "ERROR", "SKIP") if k in counts))
    if a.report:
        rev = sh(["git", "-C", ROOT, "rev-parse", "--short", "HEAD"], check=False).strip()
        write_report(a.report, results, {"rev": rev, "when": time.strftime("%Y-%m-%d")})
        print("report written: %s" % a.report)
    if a.strict and (counts.get("GAP", 0) or counts.get("ERROR", 0)):
        sys.exit(1)


if __name__ == "__main__":
    main()
