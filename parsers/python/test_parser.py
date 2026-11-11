#!/usr/bin/env python3
"""Self-test for the Python parser sidecar's extract() — stdlib unittest, no deps."""
import unittest

from parser_server import extract


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


if __name__ == "__main__":
    unittest.main()
