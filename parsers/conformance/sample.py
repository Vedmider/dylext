"""Sample module for conformance."""
import os
from typing import List as L

MAX_LEN = 10


class Greeter:
    """Greets people."""

    prefix: str = "hi"

    def hello(self, name: str) -> str:
        return self.prefix + name.upper()


def plain(a: int, b: int = 1) -> int:
    return add(a, b)
