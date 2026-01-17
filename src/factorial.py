"""Factorial helper along with a small CLI entrypoint.

Usage
-----
    python src/factorial.py 5  # prints 120

The module purposefully contains no third-party dependencies so it can serve as a
minimal demo target for the agent loop.
"""

from __future__ import annotations

import argparse
import sys
from typing import Iterable


def factorial(n: int) -> int:
    """Return n! for a non-negative integer ``n``.

    Raises:
        TypeError:      when ``n`` is not an integer.
        ValueError:     when ``n`` is negative.
    """

    if not isinstance(n, int):
        raise TypeError("factorial() requires an integer input")
    if n < 0:
        raise ValueError("factorial() is undefined for negative integers")

    product = 1
    for value in _range_inclusive(2, n):
        product *= value
    return product


def _range_inclusive(start: int, stop: int) -> Iterable[int]:
    """Return the inclusive range [start, stop]."""

    if stop < start:
        return []
    return range(start, stop + 1)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Compute n! for a non-negative integer n.")
    parser.add_argument("n", type=int, help="Target integer")
    args = parser.parse_args(argv)

    try:
        result = factorial(args.n)
    except (TypeError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    print(result)
    return 0


if __name__ == "__main__":  # pragma: no cover - exercised via CLI
    sys.exit(main())
