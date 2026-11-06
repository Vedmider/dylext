#!/usr/bin/env python3
"""External A/B token-savings proof for context-mcp, using the REAL tokenizer (tiktoken cl100k).

For each task it talks to a running context-mcp over stdio JSON-RPC:
  WITH-MCP   = search_symbols -> the digests served (+ one expand span when drilling in)
  WITHOUT    = read, whole, every file that holds a candidate symbol

Both sides are tokenized with tiktoken so the comparison is apples-to-apples. The in-JVM
HarnessServiceIT proves the same logic headlessly in CI; this script is the external, real-
tokenizer version for the evidence doc.

Run (server must be started with contextmcp.mcp.stdio=true, repo already indexed):
  PY=~/.kiro/crew-venv/bin/python3.12 $PY scripts/ab-harness.py --repo self --root src/main/java
"""
import argparse
import json
import os
import subprocess
import sys

try:
    import tiktoken
    ENC = tiktoken.get_encoding("cl100k_base")
    def toklen(s): return len(ENC.encode(s or ""))
except Exception:
    # Fallback estimate if tiktoken is unavailable; prefer the py3.12 crew venv which has it.
    def toklen(s): return len((s or "").split())

TASKS = [
    {"name": "find retrieval service", "query": "searchSymbols", "expand": True},
    {"name": "locate token ledger", "query": "recordServed", "expand": True},
    {"name": "git working changes", "query": "workingChanges", "expand": False},
]


class Mcp:
    def __init__(self, jar, env):
        self.p = subprocess.Popen(
            [env.get("JAVA", "java"), "-jar", jar],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            env={**os.environ, "contextmcp.mcp.stdio": "true", **env},
            text=True, bufsize=1)
        self._id = 0

    def call(self, name, arguments):
        self._id += 1
        req = {"jsonrpc": "2.0", "id": self._id, "method": "tools/call",
               "params": {"name": name, "arguments": arguments}}
        self.p.stdin.write(json.dumps(req) + "\n")
        self.p.stdin.flush()
        line = self.p.stdout.readline()
        payload = json.loads(line)["result"]["content"][0]["json"]
        return payload

    def close(self):
        self.p.stdin.close()
        self.p.terminate()


def file_of(symbol_id):
    parts = symbol_id.split(":")
    return parts[1] if len(parts) > 2 else ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    ap.add_argument("--root", required=True)
    ap.add_argument("--jar", default="target/context-mcp-0.1.0.jar")
    args = ap.parse_args()

    mcp = Mcp(args.jar, {})
    try:
        mcp.call("index", {"repo": args.repo, "root": args.root})
        rows, tot_with, tot_without = [], 0, 0
        for t in TASKS:
            hits = mcp.call("search_symbols", {"repo": args.repo, "query": t["query"], "topK": 5})
            served = "".join("%s %s  %s\n" % (h["kind"], h["name"], h["signature"]) for h in hits)
            if t["expand"] and hits:
                span = mcp.call("expand", {"symbolId": hits[0]["id"]})
                served += span["source"]
            with_mcp = toklen(served)
            files = {file_of(h["id"]) for h in hits}
            without = 0
            for f in files:
                p = os.path.join(args.root, f)
                without += toklen(open(p, encoding="utf-8", errors="ignore").read()) \
                    if os.path.exists(p) else 0
            saved = 0 if without == 0 else 100.0 * (without - with_mcp) / without
            rows.append((t["name"], len(hits), with_mcp, without, saved))
            tot_with += with_mcp
            tot_without += without

        print("| task | hits | WITH-MCP | WITHOUT | saved |")
        print("|---|---:|---:|---:|---:|")
        for name, h, w, wo, s in rows:
            print("| %s | %d | %d | %d | %+.0f%% |" % (name, h, w, wo, s))
        tot_saved = 0 if tot_without == 0 else 100.0 * (tot_without - tot_with) / tot_without
        print("| **total** |  | **%d** | **%d** | **%+.0f%%** |" % (tot_with, tot_without, tot_saved))
    finally:
        mcp.close()


if __name__ == "__main__":
    main()
