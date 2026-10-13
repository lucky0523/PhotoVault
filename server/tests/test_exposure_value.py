"""Unit tests for app.services.exposure_value (scene-ev design §4.1, §7; AC1–AC3).

``ev = log2(N²/t)`` (camera-settings EV) and ``ev100 = ev − log2(ISO/100)``
(「场景曝光量」, what the statistics page shows).
"""

from __future__ import annotations

import dataclasses
import math
import random
import sqlite3
from fractions import Fraction

import pytest
from PIL.TiffImagePlugin import IFDRational

from app.services import media_metadata
from app.services.exposure_value import (
    SQL_EV100_FUNCTION,
    ExposureValue,
    calculate_ev,
    ev100_value,
)

_ISO_CHOICES = [50, 100, 200, 400, 800, 1600, 3200, 6400, 12800]


def _random_inputs(n: int = 30) -> list[tuple[int, float, float]]:
    """Fixed-seed valid (iso, aperture, shutter) triples (NIT-3)."""
    rng = random.Random(20261012)
    return [
        (rng.choice(_ISO_CHOICES), round(rng.uniform(1.0, 22.0), 2), 2 ** rng.uniform(-13, 5))
        for _ in range(n)
    ]


class TestFormula:
    @pytest.mark.parametrize(
        "iso, aperture, shutter, ev, ev100",
        [
            # Step requirement: ev100 (scene EV) ≈ 14.97 / 12.97.
            (100, 16, 1 / 125, 14.9658, 14.9658),
            (400, 16, 1 / 125, 14.9658, 12.9658),
            # AC1
            (100, 2.8, 1 / 250, 10.9366, 10.9366),
            (3200, 2.8, 1 / 60, 8.8777, 3.8777),
        ],
    )
    def test_ac1_known_values(self, iso, aperture, shutter, ev, ev100):
        result = calculate_ev(iso, aperture, shutter)
        assert result is not None
        assert result.ev == pytest.approx(ev, abs=1e-4)
        assert result.ev100 == pytest.approx(ev100, abs=1e-4)

    def test_user_example_rounds_to_two_decimals(self):
        assert round(calculate_ev(100, 16, 1 / 125).ev100, 2) == 14.97
        assert round(calculate_ev(400, 16, 1 / 125).ev100, 2) == 12.97

    @pytest.mark.parametrize(
        "iso, aperture, shutter, ev, ev100",
        [
            (400, 4, 0.25, 6, 4),
            (200, 8, 1 / 128, 13, 12),
        ],
    )
    def test_ac2_exact_values(self, iso, aperture, shutter, ev, ev100):
        result = calculate_ev(iso, aperture, shutter)
        assert result.ev == pytest.approx(ev, abs=1e-9)
        assert result.ev100 == pytest.approx(ev100, abs=1e-9)

    def test_matches_formula_verbatim(self):
        for iso, n, t in _random_inputs():
            ev = math.log2((n * n) / t)
            ev100 = ev - math.log2(iso / 100)
            assert calculate_ev(iso, n, t) == ExposureValue(ev=ev, ev100=ev100)


class TestFractionalShutter:
    def test_fraction_ifdrational_and_float_agree(self):
        ref = calculate_ev(100, 16, 1 / 125)
        for shutter in (Fraction(1, 125), IFDRational(1, 125)):
            got = calculate_ev(100, 16, shutter)
            assert got is not None
            assert got.ev == pytest.approx(ref.ev, abs=1e-12)
            assert got.ev100 == pytest.approx(ref.ev100, abs=1e-12)

    def test_rational_aperture_and_iso(self):
        got = calculate_ev(Fraction(400), IFDRational(28, 10), Fraction(1, 250))
        ref = calculate_ev(400, 2.8, 1 / 250)
        assert got.ev100 == pytest.approx(ref.ev100, abs=1e-12)

    def test_text_fraction_is_not_parsed(self):
        assert calculate_ev(100, 16, "1/125") is None

    def test_stored_value_is_already_float(self):
        assert media_metadata._clean_float(IFDRational(1, 125), 3600.0, None) == 0.008

    def test_zero_denominator_rational(self):
        assert calculate_ev(100, 16, IFDRational(1, 0)) is None


_BAD = [None, 0, -1, float("nan"), float("inf"), float("-inf"), True, "abc", b"x"]


class TestInvalidInputs:
    @pytest.mark.parametrize("bad", _BAD, ids=repr)
    @pytest.mark.parametrize("position", [0, 1, 2])
    def test_ac3_skipped(self, position, bad):
        args = [100, 2.8, 1 / 250]
        args[position] = bad
        assert calculate_ev(*args) is None
        assert ev100_value(*args) is None

    def test_overflow_returns_none(self):
        assert calculate_ev(100, 1e200, 1 / 125) is None

    def test_iso_underflow_returns_none(self):
        assert calculate_ev(5e-324, 2.8, 1 / 250) is None

    def test_non_real_object(self):
        assert calculate_ev(100, 2.8, object()) is None
        assert calculate_ev(100, complex(2.8, 0), 1 / 250) is None


class TestApi:
    def test_ev100_value_matches_calculate_ev(self):
        for iso, n, t in _random_inputs():
            assert ev100_value(iso, n, t) == calculate_ev(iso, n, t).ev100

    def test_field_names(self):
        assert [f.name for f in dataclasses.fields(ExposureValue)] == ["ev", "ev100"]

    def test_sql_function_name(self):
        assert SQL_EV100_FUNCTION == "pv_ev100"


class TestSqlFunction:
    @pytest.fixture
    def conn(self):
        conn = sqlite3.connect(":memory:")
        conn.create_function(SQL_EV100_FUNCTION, 3, ev100_value)
        yield conn
        conn.close()

    def _sql(self, conn, *args):
        return conn.execute(f"SELECT {SQL_EV100_FUNCTION}(?, ?, ?)", args).fetchone()[0]

    def test_matches_pure_function(self, conn):
        inputs = _random_inputs()
        assert len(inputs) >= 20
        for iso, n, t in inputs:
            assert self._sql(conn, iso, n, t) == pytest.approx(
                ev100_value(iso, n, t), abs=1e-9
            )

    @pytest.mark.parametrize(
        "args",
        [(None, 2.8, 0.004), (100, None, 0.004), (100, 2.8, None),
         ("100", 2.8, 0.004), (100, 2.8, "1/125"), (100, 0, 0.004)],
    )
    def test_null_or_text_gives_null(self, conn, args):
        assert self._sql(conn, *args) is None
