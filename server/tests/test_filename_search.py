"""Tests for app.services.filename_search (design V1.1, AC-2).

The parity table below was generated from the old Web implementation
``web/src/utils/filenameSearch.ts`` (Node 25, ``parseSearchQuery`` +
``matchesFilename``) before that file was deleted; the booleans and keyword
counts are pasted literally, the TS is never imported at test time.
"""

from __future__ import annotations

import pytest

from app.services.filename_search import (
    SearchKeyword,
    derived_name_columns,
    file_ext,
    file_name_key,
    normalize_for_search,
    parse_search_query,
)


def _matches(file_name: str, keywords: list[SearchKeyword]) -> bool:
    """Python mirror of the SQL predicates built by TimelineFilters.sql()."""
    if not keywords:
        return True
    norm = normalize_for_search(file_name)
    key = file_name_key(file_name)
    return all((k.value in norm) if k.raw else (k.value in key) for k in keywords)


# (query, file name, JS keyword count, JS matchesFilename result)
JS_PARITY_TABLE = [
    ("img12", "IMG_1234.JPG", 1, True),
    ("IMG12", "IMG_1234.JPG", 1, True),
    ("img-12", "IMG_1234.JPG", 1, True),
    ("img 12", "IMG_1234.JPG", 2, True),
    ("ＩＭＧ_１２", "IMG_1234.JPG", 1, True),
    ("img13", "IMG_1234.JPG", 1, False),
    ("1234.jpg", "IMG_1234.JPG", 1, True),
    ("jpg img", "IMG_1234.JPG", 2, True),
    ("%", "100%_done.png", 1, True),
    ("%", "IMG_1234.JPG", 1, False),
    ("_", "IMG_1234.JPG", 1, True),
    ("_", "photo.jpg", 1, False),
    ("_", "a-b.jpg", 1, False),
    ("screenshot 2024", "Screenshot_2024-05-01.png", 2, True),
    ("screenshot 2024", "Screenshot_2023-05-01.png", 2, False),
    ("screenshot 2024", "IMG_2024.jpg", 2, False),
    ("   ", "anything.jpg", 0, True),
    ("", "anything.jpg", 0, True),
    ("\u3000", "anything.jpg", 0, True),
    ("img\u300012", "IMG_1234.JPG", 2, True),
    ("img\ufeff12", "IMG_1234.JPG", 2, True),
    ("img\x1c12", "IMG_1234.JPG", 1, False),
    ("img\x1c12", "IMG\x1c1234.JPG", 1, True),
    ("img\x8512", "IMG\x851234.JPG", 1, True),
    ("img\x8512", "IMG_1234.JPG", 1, False),
    ("(1)", "photo (1).jpg", 1, True),
    ("()", "photo (1).jpg", 1, False),
    ("()", "photo1.jpg", 1, False),
    (".", "photo.jpg", 1, True),
    ("-", "photo.jpg", 1, False),
    ("- _", "a-b_c.jpg", 2, True),
    ("照片", "我的照片.HEIC", 1, True),
    ("\ufb01le", "file.jpg", 1, True),
    ("\u216b", "xii.jpg", 1, True),
    ("*", "a*b.jpg", 1, True),
    ("?", "ab.jpg", 1, False),
    ("mmexport", "mmexport1700000000000.jpg", 1, True),
    ("mm ex port", "mmexport1700000000000.jpg", 3, True),
    ("MMEXPORT1700", "mmexport1700000000000.jpg", 1, True),
]


@pytest.mark.parametrize(("query", "name", "js_count", "js_match"), JS_PARITY_TABLE)
def test_matches_old_filename_search_ts(query, name, js_count, js_match):
    keywords = parse_search_query(query)
    assert len(keywords) == js_count
    assert _matches(name, keywords) is js_match


@pytest.mark.parametrize("query", ["img12", "IMG12", "img-12", "img 12", "ＩＭＧ_１２"])
def test_ac2_variants_hit_img_1234(query):
    assert _matches("IMG_1234.JPG", parse_search_query(query))


def test_ac2_percent_and_underscore_are_literal():
    names = ["100%_done.png", "IMG_1234.JPG", "photo.jpg", "a-b.jpg"]
    assert [n for n in names if _matches(n, parse_search_query("%"))] == ["100%_done.png"]
    assert [n for n in names if _matches(n, parse_search_query("_"))] == [
        "100%_done.png",
        "IMG_1234.JPG",
    ]
    assert parse_search_query("_") == [SearchKeyword("_", True)]
    assert parse_search_query("%") == [SearchKeyword("%", False)]


def test_ac2_multiple_keywords_are_anded():
    keywords = parse_search_query("screenshot 2024")
    assert keywords == [SearchKeyword("screenshot", False), SearchKeyword("2024", False)]
    assert _matches("Screenshot_2024-01-01.png", keywords)
    assert not _matches("Screenshot_2023-01-01.png", keywords)


@pytest.mark.parametrize("query", [None, "", "   ", "\t\n", "\u3000\ufeff\xa0"])
def test_whitespace_only_query_is_no_search(query):
    assert parse_search_query(query) == []


def test_js_whitespace_set():
    # \u3000 and \ufeff are JS whitespace: they split keywords.
    assert parse_search_query("a\u3000b") == [SearchKeyword("a", False), SearchKeyword("b", False)]
    assert parse_search_query("a\ufeffb") == [SearchKeyword("a", False), SearchKeyword("b", False)]
    # \x1c and \x85 are Python whitespace but not JS whitespace: kept inside the keyword.
    assert parse_search_query("a\x1cb") == [SearchKeyword("a\x1cb", False)]
    assert parse_search_query("a\x85b") == [SearchKeyword("a\x85b", False)]
    assert file_name_key("A\x1cB.jpg") == "a\x1cbjpg"


def test_file_name_key():
    assert file_name_key("IMG_1234.JPG") == "img1234jpg"
    assert file_name_key("Photo (1) - copy.heic") == "photo1copyheic"
    assert file_name_key("ＩＭＧ＿１２.jpg") == "img12jpg"
    assert file_name_key("___") == ""


@pytest.mark.parametrize(
    ("name", "expected"),
    [
        ("a", ""),
        ("a.", ""),
        (".hidden", "HIDDEN"),
        ("x.tar.gz", "GZ"),
        ("照片.Jpg", "JPG"),
        ("Mr. Smith at the beach long name", " SMITH AT THE BEACH LONG NAME"),
    ],
)
def test_file_ext(name, expected):
    assert file_ext(name) == expected


def test_derived_name_columns():
    assert derived_name_columns("IMG_1234.JPG") == ("img1234jpg", "JPG")
    assert derived_name_columns("noext") == ("noext", "")
