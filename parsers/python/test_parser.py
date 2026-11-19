#!/usr/bin/env python3
"""Self-test for the Python parser sidecar's extract() — stdlib unittest, no deps."""
import json
import os
import unittest

from parser_server import extract, extract_imports, parse_result


def _conformance_dir():
    return os.path.join(os.path.dirname(__file__), "..", "conformance")


class ExtractTest(unittest.TestCase):

    def test_top_level_function(self):
        syms = extract("def greet(name):\n    return 'hi ' + name\n")
        fn = next(s for s in syms if s["name"] == "greet")
        self.assertEqual(fn["kind"], "METHOD")
        self.assertEqual(fn["symbolPath"], "greet")
        self.assertEqual(fn["signature"], "def greet(name)")
        self.assertEqual(fn["startLine"], 1)

    def test_class_and_method_paths(self):
        src = (
            "class Greeter:\n"
            "    \"\"\"Greets people.\"\"\"\n"
            "    def hello(self, name):\n"
            "        print(name)\n"
        )
        syms = extract(src)
        cls = next(s for s in syms if s["kind"] == "CLASS")
        self.assertEqual(cls["name"], "Greeter")
        self.assertEqual(cls["docFirstLine"], "Greets people.")
        method = next(s for s in syms if s["name"] == "hello")
        self.assertEqual(method["symbolPath"], "Greeter#hello")
        self.assertIn("print", method["callTargets"])

    def test_async_and_varargs_signature(self):
        syms = extract("async def run(*args, **kw):\n    pass\n")
        fn = syms[0]
        self.assertEqual(fn["signature"], "async def run(*args, **kw)")

    def test_syntax_error_returns_none(self):
        self.assertIsNone(extract("def broken(:\n"))

    def test_empty_source_has_no_symbols(self):
        self.assertEqual(extract(""), [])


class ConformanceTest(unittest.TestCase):
    """The shared contract fixture: extract() must emit the expected neutral symbols."""

    def setUp(self):
        d = _conformance_dir()
        with open(os.path.join(d, "sample.py")) as f:
            self.source = f.read()
        with open(os.path.join(d, "sample.py.expected.json")) as f:
            self.expected = json.load(f)

    def test_matches_expected_symbols(self):
        got = {s["symbolPath"]: s for s in extract(self.source)}
        for exp in self.expected["symbols"]:
            path = exp["symbolPath"]
            self.assertIn(path, got, f"missing symbol {path}")
            actual = got[path]
            for field, value in exp.items():
                self.assertEqual(actual.get(field), value,
                                 f"{path}.{field}: expected {value!r} got {actual.get(field)!r}")

    def test_module_imports(self):
        self.assertEqual(extract_imports(self.source), self.expected["moduleImports"])

    def test_module_exports(self):
        from parser_server import module_exports
        self.assertEqual(module_exports(self.source), self.expected["moduleExports"])

    def test_init_is_constructor(self):
        kinds = {s["symbolPath"]: s["kind"] for s in extract(self.source)}
        self.assertEqual(kinds.get("Greeter#__init__"), "CONSTRUCTOR")
        self.assertEqual(kinds.get("Greeter#hello"), "METHOD")


class ParseVisibilityTest(unittest.TestCase):
    """A syntax failure is distinguishable from a genuinely empty-but-valid file."""

    def test_syntax_error_is_reported_not_silently_empty(self):
        symbols, ok, err = parse_result("def broken(:\n", "bad.py")
        self.assertFalse(ok)
        self.assertEqual(symbols, [])
        self.assertIn("SyntaxError", err)

    def test_valid_empty_file_parses_ok_with_module_only(self):
        symbols, ok, err = parse_result("# just a comment\n", "empty.py")
        self.assertTrue(ok)
        self.assertIsNone(err)
        self.assertEqual([s["kind"] for s in symbols], ["MODULE"])

    def test_valid_file_parses_ok_with_members(self):
        symbols, ok, err = parse_result("def f():\n    pass\n", "f.py")
        self.assertTrue(ok)
        self.assertTrue(any(s["kind"] == "METHOD" for s in symbols))


if __name__ == "__main__":
    unittest.main()
