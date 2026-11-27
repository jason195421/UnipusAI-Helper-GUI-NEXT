"""Check the optional native matcher against the portable Python reference."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from unipus_helper import text_matching


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--require-native", action="store_true")
    args = parser.parse_args()
    if args.require_native and text_matching._textmatch is None:
        raise RuntimeError("C++ matcher was built but could not be imported")

    cases = [
        (["alpha beta starts here", "gamma delta follows"],
         ["alpha beta choice", "gamma delta choice"], [0, 1]),
        (["the answer is in this short sentence"], ["the and in"], None),
        (["alpha beta", "gamma delta", "alpha gamma"],
         ["alpha gamma option"], [2]),
        (["alpha beta", "gamma delta"], ["missing words"], None),
        (["alpha beta"], [], None),
    ]
    for sentences, options, expected in cases:
        reference = text_matching._python_best_sentence_positions(sentences, options)
        selected = text_matching.best_sentence_positions(sentences, options)
        if selected != reference or selected != expected:
            raise AssertionError(
                f"fallback mismatch: expected={expected}, reference={reference}, actual={selected}"
            )
        if text_matching._textmatch is not None:
            actual = text_matching._textmatch.best_sentence_positions(sentences, options)
            if actual != reference:
                raise AssertionError(
                    f"native mismatch: reference={reference}, actual={actual}"
                )
    if text_matching._textmatch is None:
        print(f"Python matcher passed {len(cases)} cases; C++ extension not built")
        return 0
    print(f"C++ matcher passed {len(cases)} parity cases")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
