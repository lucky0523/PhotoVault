"""Property-based tests for the place-analysis pure logic (Hypothesis).

Covers correctness properties from the design document for
``server/app/services/place_analyzer.py``. Each property runs at least 100
iterations.

Property label format (per design):
    Feature: web-explore-people-places-scenes, Property {number}: {property_text}
"""

from __future__ import annotations

from typing import Any

from hypothesis import given, settings
from hypothesis import strategies as st

from app.services.place_analyzer import parse_gps_ifd

# GPS IFD tag numbers (mirrors the private constants in place_analyzer).
_GPS_LATITUDE_REF = 1
_GPS_LATITUDE = 2
_GPS_LONGITUDE_REF = 3
_GPS_LONGITUDE = 4


# ---------------------------------------------------------------------------
# Strategies
# ---------------------------------------------------------------------------


def _component_repr(value: float, data: st.DataObject) -> Any:
    """Return one of the EXIF-rational-ish shapes for a non-negative number.

    Mirrors the shapes ``parse_gps_ifd`` must accept: a plain float, an
    ``int``/``float``, or a ``(numerator, denominator)`` pair. Reconstruction
    is intentionally not exact (ratio rounding) — the tests only assert range
    and sign invariants, computed from whatever the parser reads back.
    """
    shape = data.draw(st.sampled_from(["float", "int_ratio", "ratio"]))
    if shape == "float":
        return float(value)
    if shape == "int_ratio":
        return (int(round(value)), 1)
    den = data.draw(st.integers(min_value=1, max_value=1000))
    return (int(round(value * den)), den)


def _dms_triple(degrees: int, minutes: int, seconds: float, data: st.DataObject):
    """Build a (deg, min, sec) triple where each component is a random shape."""
    return (
        _component_repr(float(degrees), data),
        _component_repr(float(minutes), data),
        _component_repr(float(seconds), data),
    )


# ---------------------------------------------------------------------------
# Property 1: GPS 解析范围与方向正确
# ---------------------------------------------------------------------------

# Feature: web-explore-people-places-scenes, Property 1: GPS 解析范围与方向正确
@given(data=st.data())
@settings(max_examples=200)
def test_parse_gps_ifd_range_and_direction(data: st.DataObject):
    """Feature: web-explore-people-places-scenes, Property 1: GPS 解析范围与方向正确

    **Validates: Requirements 2.1**

    For any valid GPS IFD (latitude/longitude rational DMS components plus an
    N/S/E/W reference), ``parse_gps_ifd`` returns a latitude in [-90, 90] and a
    longitude in [-180, 180], with S/W producing a negative sign and N/E a
    positive sign.
    """
    # Latitude: 0..90 degrees. When exactly 90, minutes/seconds must be 0 so the
    # total cannot exceed the valid range.
    lat_deg = data.draw(st.integers(min_value=0, max_value=90))
    if lat_deg == 90:
        lat_min, lat_sec = 0, 0.0
    else:
        lat_min = data.draw(st.integers(min_value=0, max_value=59))
        lat_sec = data.draw(
            st.floats(min_value=0.0, max_value=59.0, allow_nan=False, allow_infinity=False)
        )

    # Longitude: 0..180 degrees, same boundary handling.
    lon_deg = data.draw(st.integers(min_value=0, max_value=180))
    if lon_deg == 180:
        lon_min, lon_sec = 0, 0.0
    else:
        lon_min = data.draw(st.integers(min_value=0, max_value=59))
        lon_sec = data.draw(
            st.floats(min_value=0.0, max_value=59.0, allow_nan=False, allow_infinity=False)
        )

    lat_ref = data.draw(st.sampled_from(["N", "S"]))
    lon_ref = data.draw(st.sampled_from(["E", "W"]))

    gps_ifd = {
        _GPS_LATITUDE_REF: lat_ref,
        _GPS_LATITUDE: _dms_triple(lat_deg, lat_min, lat_sec, data),
        _GPS_LONGITUDE_REF: lon_ref,
        _GPS_LONGITUDE: _dms_triple(lon_deg, lon_min, lon_sec, data),
    }

    result = parse_gps_ifd(gps_ifd)

    assert result is not None, f"Valid GPS IFD should parse: {gps_ifd!r}"
    latitude, longitude = result

    # Range invariants.
    assert -90.0 <= latitude <= 90.0, f"Latitude out of range: {latitude}"
    assert -180.0 <= longitude <= 180.0, f"Longitude out of range: {longitude}"

    # Sign / direction invariants. The magnitude is non-negative by
    # construction, so the sign must follow the reference direction. (A zero
    # magnitude is sign-agnostic and always valid.)
    if lat_ref == "S":
        assert latitude <= 0.0, f"S reference must be non-positive: {latitude}"
    else:  # "N"
        assert latitude >= 0.0, f"N reference must be non-negative: {latitude}"

    if lon_ref == "W":
        assert longitude <= 0.0, f"W reference must be non-positive: {longitude}"
    else:  # "E"
        assert longitude >= 0.0, f"E reference must be non-negative: {longitude}"


# Feature: web-explore-people-places-scenes, Property 1: GPS 解析范围与方向正确
@given(
    data=st.data(),
    defect=st.sampled_from(
        [
            "drop_lat_ref",
            "drop_lon_ref",
            "drop_lat_dms",
            "drop_lon_dms",
            "bad_lat_ref",
            "bad_lon_ref",
        ]
    ),
)
@settings(max_examples=200)
def test_parse_gps_ifd_missing_or_invalid_returns_none(data: st.DataObject, defect: str):
    """Feature: web-explore-people-places-scenes, Property 1: GPS 解析范围与方向正确

    **Validates: Requirements 2.1**

    A GPS IFD missing a required coordinate component or a valid N/S/E/W
    reference direction returns ``None``.
    """
    lat_deg = data.draw(st.integers(min_value=0, max_value=89))
    lon_deg = data.draw(st.integers(min_value=0, max_value=179))
    lat_dms = _dms_triple(lat_deg, 0, 0.0, data)
    lon_dms = _dms_triple(lon_deg, 0, 0.0, data)

    gps_ifd: dict[int, Any] = {
        _GPS_LATITUDE_REF: "N",
        _GPS_LATITUDE: lat_dms,
        _GPS_LONGITUDE_REF: "E",
        _GPS_LONGITUDE: lon_dms,
    }

    if defect == "drop_lat_ref":
        del gps_ifd[_GPS_LATITUDE_REF]
    elif defect == "drop_lon_ref":
        del gps_ifd[_GPS_LONGITUDE_REF]
    elif defect == "drop_lat_dms":
        del gps_ifd[_GPS_LATITUDE]
    elif defect == "drop_lon_dms":
        del gps_ifd[_GPS_LONGITUDE]
    elif defect == "bad_lat_ref":
        # A reference direction that is neither N nor S.
        gps_ifd[_GPS_LATITUDE_REF] = data.draw(
            st.sampled_from(["X", "E", "W", "", "1"])
        )
    elif defect == "bad_lon_ref":
        gps_ifd[_GPS_LONGITUDE_REF] = data.draw(
            st.sampled_from(["X", "N", "S", "", "1"])
        )

    assert parse_gps_ifd(gps_ifd) is None, f"Defective GPS IFD should return None: {gps_ifd!r}"
