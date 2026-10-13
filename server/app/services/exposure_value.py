"""Exposure value (EV) helpers for the「场景曝光量」statistics (scene-ev design §4.1).

    ev    = log2(N² / t)              camera-settings EV (aperture N, shutter t seconds),
                                      independent of ISO
    ev100 = ev − log2(ISO / 100)      scene exposure normalised to ISO 100 (「场景曝光量」)

Names follow the photographic convention: EV100 is the value normalised to
ISO 100. The statistics page shows ``ev100``.

Pure (no database access). ``ev100_value`` is also registered on the stats
connection as the SQL function ``pv_ev100`` so the SQL path and the Python path
are the same code (FR-1.3).
"""

from __future__ import annotations

import math
import numbers
from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:  # pragma: no cover
    import aiosqlite

SQL_EV100_FUNCTION = "pv_ev100"


@dataclass(frozen=True)
class ExposureValue:
    ev: float      # log2(N²/t), camera-settings EV
    ev100: float   # ev − log2(ISO/100), scene exposure at ISO 100（「场景曝光量」）


def _positive_finite(value: object) -> float | None:
    """``float(value)`` when it is a finite real number > 0, else None.

    Accepts ``int`` / ``float`` / ``Fraction`` / Pillow ``IFDRational`` (all
    ``numbers.Real``). Rejects None, ``bool``, ``str`` / ``bytes`` (including
    text such as ``"1/125"``), NaN, ±Infinity, 0 and negatives.
    """
    if value is None or isinstance(value, (bool, str, bytes)):
        return None
    if not isinstance(value, numbers.Real):
        return None
    try:
        x = float(value)
    except (TypeError, ValueError, ZeroDivisionError, OverflowError):
        return None
    if not math.isfinite(x) or x <= 0:
        return None
    return x


def calculate_ev(
    iso: object, aperture: object, shutter_seconds: object
) -> ExposureValue | None:
    """EV of one photo from ISO, aperture (f-number) and shutter time in seconds.

    Returns None when any input is missing, not a number, <= 0 or not finite,
    or when the result is not finite. Never raises.
    """
    iso = _positive_finite(iso)
    aperture = _positive_finite(aperture)
    shutter_seconds = _positive_finite(shutter_seconds)
    if iso is None or aperture is None or shutter_seconds is None:
        return None
    try:
        ev = math.log2((aperture * aperture) / shutter_seconds)
        ev100 = ev - math.log2(iso / 100)
    except (ValueError, OverflowError, ZeroDivisionError):
        return None
    if not (math.isfinite(ev) and math.isfinite(ev100)):
        return None
    return ExposureValue(ev=ev, ev100=ev100)


def ev100_value(
    iso: object, aperture: object, shutter_seconds: object
) -> float | None:
    """``calculate_ev(...).ev100`` or None (the SQL function ``pv_ev100``)."""
    result = calculate_ev(iso, aperture, shutter_seconds)
    return None if result is None else result.ev100


async def register_sql_functions(db: "aiosqlite.Connection") -> None:
    """Register ``pv_ev100(iso, f_number, exposure_time)`` on this connection."""
    await db.create_function(SQL_EV100_FUNCTION, 3, ev100_value)
