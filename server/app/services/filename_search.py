"""File name fuzzy search: the server-side port of the old Web ``filenameSearch.ts``.

The Web timeline used to match file names in the browser
(``web/src/utils/filenameSearch.ts``). Search now runs in SQL, and this module
keeps the exact same semantics, rule for rule:

1. Both the query and the file name are NFKC-normalised and lower-cased
   (case-insensitive; full-width letters/digits typed by Chinese IMEs fold to
   their ASCII forms).
2. The query is split on whitespace into keywords; every keyword must match
   (AND). A whitespace-only query has no keywords and matches everything.
3. Separators ``_ - . ( )`` and whitespace are ignored on both sides, so
   ``img12`` / ``img-12`` / ``img 12`` all match ``IMG_1234.JPG``.
4. A keyword made only of separators (e.g. a lone ``_``) falls back to a plain
   substring match against the normalised name, so it never matches everything.
5. Matching is plain substring comparison (``instr`` in SQL); ``%``, ``_``,
   ``*`` and ``?`` are literal characters.

"Whitespace" is exactly JavaScript's ``\\s`` (ECMAScript WhiteSpace +
LineTerminator), not Python's: Python's ``\\s`` / ``str.split()`` also include
``\\x1c``-``\\x1f`` and ``\\x85`` but not ``\\ufeff``.

The functions are pure and dependency free; the upload write paths, the
background backfill (``filename_key_backfill``) and the timeline queries
(``timeline_service``) share them.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

# Exactly the characters of the JavaScript regex class ``\s``.
JS_WHITESPACE = "\t\n\x0b\x0c\r \xa0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000\ufeff"
_WHITESPACE_RE = re.compile(f"[{JS_WHITESPACE}]+")
_SEPARATORS_RE = re.compile(f"[{JS_WHITESPACE}_\\-.()]+")


def normalize_for_search(s: str) -> str:
    """NFKC normalisation + lower case (``normalizeForSearch`` in the old TS)."""
    return unicodedata.normalize("NFKC", s).lower()


def file_name_key(name: str) -> str:
    """The stored search key: normalised name with every separator removed."""
    return _SEPARATORS_RE.sub("", normalize_for_search(name))


def file_ext(name: str) -> str:
    """Upper-cased text after the last ``.``; ``''`` without one or when it ends the name.

    Same definition as the Web timeline's ``fileExt`` (format filter / options).
    """
    i = name.rfind(".")
    return "" if i < 0 or i == len(name) - 1 else name[i + 1:].upper()


def derived_name_columns(name: str) -> tuple[str, str]:
    """``(file_name_key, file_ext)`` stored alongside ``file_records.file_name``."""
    return file_name_key(name), file_ext(name)


@dataclass(frozen=True)
class SearchKeyword:
    """One AND-ed search keyword."""

    value: str  # keyword with separators removed; the original keyword when raw=True
    raw: bool   # keyword is only separators: substring match on normalize_for_search(file_name)


def parse_search_query(q: str | None) -> list[SearchKeyword]:
    """Split a search box value into keywords; ``[]`` means "no search"."""
    words = [w for w in _WHITESPACE_RE.split(normalize_for_search(q or "")) if w]
    out: list[SearchKeyword] = []
    for w in words:
        stripped = _SEPARATORS_RE.sub("", w)
        out.append(SearchKeyword(stripped, False) if stripped else SearchKeyword(w, True))
    return out
