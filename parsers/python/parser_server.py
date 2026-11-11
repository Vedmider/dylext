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


def _expr(node):
    """Render a simple expression to source — portable across Python versions (no ast.unparse)."""
    if node is None:
        return ""
    if hasattr(ast, "unparse"):
        try:
            return ast.unparse(node)
        except Exception:
            pass
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        return _expr(node.value) + "." + node.attr
    if isinstance(node, ast.Constant):
        return repr(node.value)
    if isinstance(node, getattr(ast, "Str", ())):
        return repr(node.s)
    if isinstance(node, getattr(ast, "Num", ())):
        return repr(node.n)
    if isinstance(node, getattr(ast, "NameConstant", ())):
        return repr(node.value)
    if isinstance(node, ast.Subscript):
        return _expr(node.value) + "[" + _expr(_subscript_value(node.slice)) + "]"
    if isinstance(node, ast.Tuple):
        return ", ".join(_expr(e) for e in node.elts)
    return "..."


def _subscript_value(sl):
    # py3.8-: slice wrapped in ast.Index; py3.9+: bare expr
    return getattr(sl, "value", sl)


def annotation(node):
    return _expr(node)


def _literal(node):
    return _expr(node)


def _arg(p):
    return p.arg + (": " + annotation(p.annotation) if p.annotation else "")


def func_signature(node):
    a = node.args
    parts = []
    posonly = getattr(a, "posonlyargs", [])
    positional = posonly + a.args
    # defaults align to the tail of positional args
    defaults = a.defaults
    first_default = len(positional) - len(defaults)
    for i, p in enumerate(positional):
        s = _arg(p)
        if i >= first_default:
            s += " = " + _literal(defaults[i - first_default])
        parts.append(s)
    if posonly:
        parts.insert(len(posonly), "/")
    if a.vararg:
        parts.append("*" + _arg(a.vararg))
    elif a.kwonlyargs:
        parts.append("*")
    for i, p in enumerate(a.kwonlyargs):
        s = _arg(p)
        if a.kw_defaults[i] is not None:
            s += " = " + _literal(a.kw_defaults[i])
        parts.append(s)
    if a.kwarg:
        parts.append("**" + _arg(a.kwarg))
    prefix = "async def " if isinstance(node, ast.AsyncFunctionDef) else "def "
    ret = " -> " + annotation(node.returns) if node.returns else ""
    return f"{prefix}{node.name}({', '.join(parts)}){ret}"


def decorators(node):
    names = []
    for d in getattr(node, "decorator_list", []):
        t = d.func if isinstance(d, ast.Call) else d
        if isinstance(t, ast.Name):
            names.append(t.id)
        elif isinstance(t, ast.Attribute):
            names.append(t.attr)
    return names


def call_targets(node):
    """Qualified where resolvable: receiver.method / alias.func / self.attr.method; bare otherwise."""
    names = []
    for n in ast.walk(node):
        if isinstance(n, ast.Call):
            rendered = _callee(n.func)
            if rendered:
                names.append(rendered)
    seen, out = set(), []
    for n in names:
        if n not in seen:
            seen.add(n)
            out.append(n)
    return out


def _callee(f):
    """Render a call target: Name -> id; Attribute chain -> a.b.c; else bare attr/''"""
    if isinstance(f, ast.Name):
        return f.id
    if isinstance(f, ast.Attribute):
        base = _callee(f.value)
        return (base + "." + f.attr) if base else f.attr
    return ""


def module_imports(tree):
    out = []
    for n in tree.body:
        if isinstance(n, ast.Import):
            for a in n.names:
                out.append(a.name + (" as " + a.asname if a.asname else ""))
        elif isinstance(n, ast.ImportFrom):
            mod = n.module or ""
            for a in n.names:
                out.append(mod + "." + a.name + (" as " + a.asname if a.asname else ""))
    return out


def end_line(node):
    return getattr(node, "end_lineno", None) or node.lineno


def _field_from_assign(child, prefix, receiver):
    """A module-level or class-body assignment -> FIELD. Returns one symbol or None."""
    target = None
    ret = ""
    value = None
    if isinstance(child, ast.AnnAssign) and isinstance(child.target, ast.Name):
        target = child.target.id
        ret = annotation(child.annotation)
        value = child.value
    elif isinstance(child, ast.Assign) and len(child.targets) == 1 \
            and isinstance(child.targets[0], ast.Name):
        target = child.targets[0].id
        value = child.value
    if target is None:
        return None
    sig = target
    if ret:
        sig += ": " + ret
    if value is not None:
        sig += " = " + _literal(value)
    path = (prefix + "." + target) if prefix else target
    sym = {"kind": "FIELD", "name": target, "symbolPath": path, "signature": sig,
           "startLine": child.lineno, "endLine": end_line(child), "callTargets": []}
    if receiver:
        sym["receiver"] = receiver
    if ret:
        sym["returnType"] = ret
    return sym


def extract(source):
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return None  # signal: let Dylext synthesize the MODULE fallback
    symbols = []

    def visit(node, prefix, receiver):
        for child in node.body if hasattr(node, "body") else []:
            if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef)):
                path = f"{prefix}#{child.name}" if prefix else child.name
                mods = decorators(child)
                if isinstance(child, ast.AsyncFunctionDef):
                    mods.append("async")
                # __init__ inside a class is the constructor; @property accessors stay METHOD.
                kind = "CONSTRUCTOR" if (receiver and child.name == "__init__") else "METHOD"
                sym = {
                    "kind": kind, "name": child.name, "symbolPath": path,
                    "signature": func_signature(child),
                    "docFirstLine": first_doc_line(child),
                    "startLine": child.lineno, "endLine": end_line(child),
                    "callTargets": call_targets(child),
                    "fqName": path.replace("#", "."),
                }
                if receiver:
                    sym["receiver"] = receiver
                if child.returns is not None:
                    sym["returnType"] = annotation(child.returns)
                if mods:
                    sym["modifiers"] = mods
                symbols.append(sym)
            elif isinstance(child, ast.ClassDef):
                path = f"{prefix}.{child.name}" if prefix else child.name
                symbols.append({
                    "kind": "CLASS", "name": child.name, "symbolPath": path,
                    "signature": f"class {child.name}",
                    "docFirstLine": first_doc_line(child),
                    "startLine": child.lineno, "endLine": end_line(child),
                    "callTargets": [], "fqName": path,
                })
                visit(child, path, child.name)
            elif isinstance(child, (ast.Assign, ast.AnnAssign)):
                f = _field_from_assign(child, prefix, receiver)
                if f is not None:
                    symbols.append(f)

    visit(tree, "", "")
    return symbols


def module_exports(source):
    """Names listed in module-level __all__, if any (the public API surface)."""
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return []
    for n in tree.body:
        targets = []
        if isinstance(n, ast.Assign):
            targets = n.targets
        elif isinstance(n, ast.AnnAssign):
            targets = [n.target]
        if any(isinstance(t, ast.Name) and t.id == "__all__" for t in targets):
            val = n.value
            if isinstance(val, (ast.List, ast.Tuple)):
                return [_expr(e).strip("'\"") for e in val.elts]
    return []


def extract_imports(source):
    try:
        return module_imports(ast.parse(source))
    except SyntaxError:
        return []


def build_symbols(source, file_path):
    """Full wire response: a MODULE symbol (carrying imports) + member symbols."""
    members = extract(source)
    if members is None:
        return []
    name = file_path.rsplit("/", 1)[-1] if file_path else "<module>"
    module = {"kind": "MODULE", "name": name, "symbolPath": "<module>",
              "signature": name, "docFirstLine": "", "startLine": 1,
              "endLine": max((m.get("endLine", 1) for m in members), default=1),
              "callTargets": [], "imports": extract_imports(source)}
    exports = module_exports(source)
    if exports:
        module["modifiers"] = ["__all__:" + ",".join(exports)]
    return [module] + members


def parse_result(source, file_path):
    """Returns (symbols, parse_ok, error). Distinguishes a syntax failure (parse_ok=False)
    from a genuinely empty-but-valid file (parse_ok=True, symbols=[module])."""
    try:
        ast.parse(source)
    except SyntaxError as e:
        return [], False, "SyntaxError: %s (line %s)" % (e.msg, e.lineno)
    return build_symbols(source, file_path), True, None


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
            symbols, parse_ok, error = parse_result(
                req.get("source", ""), req.get("filePath", ""))
            body = {"symbols": symbols, "parseOk": parse_ok}
            if error:
                body["error"] = error
            self._send(200, body)
        except Exception as e:  # never 500 the indexer; degrade to empty
            self._send(200, {"symbols": [], "parseOk": False, "error": str(e)})

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
