"""Sample module for conformance."""
import os
from typing import List as L

MAX_LEN = 10

__all__ = ["Greeter", "plain"]


class Greeter:
    """Greets people."""

    prefix: str = "hi"

    def __init__(self, prefix: str) -> None:
        self.prefix = prefix

    @property
    def shout_prefix(self) -> str:
        return self.prefix.upper()

    def hello(self, name: str) -> str:
        return self.prefix + name.upper()


def plain(a: int, b: int = 1) -> int:
    return add(a, b)
