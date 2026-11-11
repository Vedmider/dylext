#!/usr/bin/env python3
"""Dylext Python parser sidecar.

A dumb, stdlib-only language parser: POST /parse {repo,filePath,language,source} -> neutral
symbol JSON that Dylext's HttpSkeletonExtractor maps into its Symbol model. It only parses —
no indexing, storage, or git. Replaceable by any service honouring the same contract (Lego).

Neutral symbol shape:
  {kind, name, symbolPath, signature, docFirstLine, startLine, endLine, callTargets[]}
kind uses Dylext's vocabulary: MODULE, CLASS, METHOD, FIELD.
"""
import ast
import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn


class ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True


def first_doc_line(node):
    doc = ast.get_docstring(node)
    if not doc:
        return ""
    for line in doc.splitlines():
        s = line.strip()
        if s:
            return s
    return ""


def func_signature(node):
    args = []
    a = node.args
    for p in getattr(a, "posonlyargs", []) + a.args:
        args.append(p.arg)
    if a.vararg:
        args.append("*" + a.vararg.arg)
    for p in a.kwonlyargs:
        args.append(p.arg)
    if a.kwarg:
        args.append("**" + a.kwarg.arg)
    prefix = "async def " if isinstance(node, ast.AsyncFunctionDef) else "def "
    return f"{prefix}{node.name}({', '.join(args)})"


def call_targets(node):
    names = []
    for n in ast.walk(node):
        if isinstance(n, ast.Call):
            f = n.func
            if isinstance(f, ast.Name):
                names.append(f.id)
            elif isinstance(f, ast.Attribute):
                names.append(f.attr)
    seen, out = set(), []
    for n in names:
        if n not in seen:
            seen.add(n)
            out.append(n)
    return out


def end_line(node, source_lines):
    return getattr(node, "end_lineno", None) or node.lineno


def extract(source):
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return None  # signal: let Dylext synthesize the MODULE fallback
    symbols = []

    def visit(node, prefix):
        for child in node.body if hasattr(node, "body") else []:
            if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef)):
                path = f"{prefix}#{child.name}" if prefix else child.name
                symbols.append({
                    "kind": "METHOD",
                    "name": child.name,
                    "symbolPath": path,
                    "signature": func_signature(child),
                    "docFirstLine": first_doc_line(child),
                    "startLine": child.lineno,
                    "endLine": end_line(child, None),
                    "callTargets": call_targets(child),
                })
            elif isinstance(child, ast.ClassDef):
                path = f"{prefix}.{child.name}" if prefix else child.name
                symbols.append({
                    "kind": "CLASS",
                    "name": child.name,
                    "symbolPath": path,
                    "signature": f"class {child.name}",
                    "docFirstLine": first_doc_line(child),
                    "startLine": child.lineno,
                    "endLine": end_line(child, None),
                    "callTargets": [],
                })
                visit(child, path)

    visit(tree, "")
    return symbols


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # quiet

    def do_GET(self):
        if self.path == "/health":
            self._send(200, {"status": "UP"})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/parse":
            self._send(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("content-length", 0))
            req = json.loads(self.rfile.read(length) or b"{}")
            symbols = extract(req.get("source", ""))
            self._send(200, {"symbols": symbols if symbols is not None else []})
        except Exception as e:  # never 500 the indexer; degrade to empty
            self._send(200, {"symbols": [], "error": str(e)})

    def _send(self, status, body):
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def main():
    port = int(os.environ.get("PORT", "9091"))
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(f"python parser sidecar on :{port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
