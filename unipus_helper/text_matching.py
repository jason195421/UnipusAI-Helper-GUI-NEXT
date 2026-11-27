"""Keyword matching for transcript ordering, with an optional C++ accelerator."""
from __future__ import annotations

import re
from typing import List, Optional, Sequence

STOP_WORDS = frozenset({
    "the", "and", "that", "with", "from", "this", "have", "were", "their",
    "they", "into", "such", "when", "what", "which", "been", "also", "more",
    "some", "many", "other", "after", "before", "over", "than", "them",
})

try:
    from . import _textmatch
except ImportError:
    _textmatch = None


def _python_best_sentence_positions(
    sentences: Sequence[str], option_texts: Sequence[str], minimum_score: float = 0.34,
) -> Optional[List[int]]:
    """Reference implementation kept as the portable fallback and parity oracle."""
    if not sentences or not option_texts:
        return None
    sentence_words = [set(sentence.split()) for sentence in sentences]
    result = []
    for option in option_texts:
        words = {word for word in option.split()
                 if len(word) > 2 and word not in STOP_WORDS}
        if len(words) < 2:
            return None
        best_index, best_score = -1, 0.0
        for index, candidates in enumerate(sentence_words):
            score = len(words & candidates) / len(words)
            if score > best_score:
                best_index, best_score = index, score
        if best_index < 0 or best_score < minimum_score:
            return None
        result.append(best_index)
    return result


def best_sentence_positions(
    sentences: Sequence[str], option_texts: Sequence[str], minimum_score: float = 0.34,
) -> Optional[List[int]]:
    """Return each option's best sentence index, or None when evidence is weak."""
    if not sentences or not option_texts:
        return None
    if _textmatch is not None:
        try:
            return _textmatch.best_sentence_positions(sentences, option_texts, minimum_score)
        except Exception:
            # Native acceleration is optional; matching must remain available on every platform.
            pass
    return _python_best_sentence_positions(sentences, option_texts, minimum_score)


def keyword_positions(corpus: str, option_texts: Sequence[str], minimum_score: float = 0.34
                      ) -> Optional[List[int]]:
    sentences = [sentence for sentence in re.split(r"(?<=[.!?])\s+", corpus)
                 if sentence.strip()]
    return best_sentence_positions(sentences, option_texts, minimum_score)
