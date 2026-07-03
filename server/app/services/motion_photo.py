"""Motion Photo (动态照片) detection.

Android "Motion Photo" (and the older Google Camera "MicroVideo", plus
Samsung/OnePlus live photos) store a still JPEG with an MP4 video appended to
the end of the same file. The primary image's XMP metadata describes the
embedded video:

- Legacy Google format: ``GCamera:MicroVideo="1"`` +
  ``GCamera:MicroVideoOffset="<bytes>"`` where the offset is the video length
  counted from the END of the file.
- Current format (https://developer.android.com/media/platform/motion-photo-format):
  ``GCamera:MotionPhoto="1"`` plus a ``Container`` directory whose second
  ``Item`` has ``Item:Mime="video/mp4"`` and ``Item:Length="<bytes>"``. Items
  are concatenated in file order, so the video occupies the last ``Length``
  bytes.

In both cases the embedded video starts at ``file_size - video_length``.

This module locates that offset so the server can stream just the video part.
"""

from __future__ import annotations

import logging
import os
import re
from typing import Optional

logger = logging.getLogger("photovault.motion")

# How much of the file head to read when looking for the XMP packet.
_XMP_SCAN_BYTES = 2 * 1024 * 1024

# Common MP4 major brands found right after the "ftyp" box tag.
_MP4_BRANDS = (
    b"isom", b"iso2", b"iso4", b"iso5", b"iso6", b"mp41", b"mp42",
    b"avc1", b"3gp", b"qt  ", b"MSNV", b"M4V ", b"dash",
)


def _extract_xmp(data: bytes) -> Optional[str]:
    """Extract the (first) XMP packet from raw file bytes, if present."""
    start = data.find(b"<x:xmpmeta")
    if start == -1:
        start = data.find(b"<?xpacket")
    if start == -1:
        return None
    end = data.find(b"</x:xmpmeta>")
    if end != -1:
        end += len(b"</x:xmpmeta>")
    else:
        end = len(data)
    try:
        return data[start:end].decode("utf-8", errors="ignore")
    except Exception:
        return None


def _video_length_from_xmp(xmp: str) -> Optional[int]:
    """Return the embedded video length in bytes as declared by the XMP."""
    # Legacy MicroVideo: offset counted from end == video length.
    m = re.search(r"MicroVideoOffset\s*=\s*[\"'](\d+)[\"']", xmp)
    if not m:
        m = re.search(r"MicroVideoOffset>\s*(\d+)\s*<", xmp)
    if m:
        return int(m.group(1))

    # Current MotionPhoto Container format: find the video item's Length.
    # Attributes can appear in either order and either as attributes or nested
    # elements, so try a few shapes.
    has_motion = (
        re.search(r"MotionPhoto\s*=\s*[\"']1[\"']", xmp) is not None
        or "MotionPhoto>" in xmp
        or "GCamera:MotionPhoto" in xmp
    )
    if has_motion or "video/mp4" in xmp:
        patterns = [
            r'Item:Mime="video/[^"]*"[^>]*?Item:Length="(\d+)"',
            r'Item:Length="(\d+)"[^>]*?Item:Mime="video/',
            r'Mime="video/mp4"[^>]*?Length="(\d+)"',
            r'Length="(\d+)"[^>]*?Mime="video/mp4"',
        ]
        for pat in patterns:
            mm = re.search(pat, xmp, re.DOTALL)
            if mm:
                return int(mm.group(1))
    return None


def _looks_like_mp4(f, offset: int) -> bool:
    """Check whether bytes at [offset] look like the start of an MP4 box."""
    try:
        f.seek(offset)
        header = f.read(12)
        return len(header) >= 8 and header[4:8] == b"ftyp"
    except Exception:
        return False


def _scan_mp4_offset(f, file_size: int) -> Optional[int]:
    """Fallback: locate an appended MP4 by scanning for the ``ftyp`` box.

    Searches after the first JPEG End-Of-Image marker so we don't match bytes
    inside the still image. Returns the byte offset of the MP4 box, or None.
    """
    try:
        f.seek(0)
        data = f.read(file_size)
    except Exception:
        return None

    eoi = data.find(b"\xff\xd9")
    search_from = eoi + 2 if eoi != -1 else 0

    idx = data.find(b"ftyp", search_from)
    while idx != -1:
        box_start = idx - 4
        if box_start >= search_from:
            brand = data[idx + 4: idx + 8]
            if any(brand.startswith(b) for b in _MP4_BRANDS):
                return box_start
        idx = data.find(b"ftyp", idx + 4)
    return None


def detect_ultra_hdr(file_path: str) -> bool:
    """Detect whether [file_path] is an Ultra HDR image.

    Ultra HDR (https://developer.android.com/media/platform/hdr-image-format) is
    a JPEG carrying an SDR base image plus a secondary "gain map" image,
    described by XMP using the ``hdr-gain-map`` (``hdrgm``) namespace, with the
    gain map referenced as a ``Container`` item whose semantic is ``GainMap``.

    Detection looks for those XMP markers in the primary image.
    """
    ext = os.path.splitext(file_path)[1].lower()
    if ext not in (".jpg", ".jpeg"):
        return False

    try:
        file_size = os.path.getsize(file_path)
        with open(file_path, "rb") as f:
            head = f.read(min(file_size, _XMP_SCAN_BYTES))
        xmp = _extract_xmp(head)
    except OSError:
        return False

    if not xmp:
        return False

    # Adobe/ISO gain-map namespace or attribute is the canonical Ultra HDR marker.
    if "hdr-gain-map" in xmp or "hdrgm:Version" in xmp or "hdrgm:" in xmp:
        return True
    # Google GContainer gain-map item.
    if re.search(r'Semantic\s*=\s*["\']GainMap["\']', xmp):
        return True
    return False


def detect_motion_photo(file_path: str) -> tuple[bool, Optional[int]]:
    """Detect whether [file_path] is a motion photo and where its video starts.

    Returns:
        (is_motion_photo, video_offset). video_offset is the byte offset within
        the file where the embedded MP4 begins, or None when not a motion photo.
    """
    try:
        file_size = os.path.getsize(file_path)
    except OSError:
        return False, None

    # Only JPEG containers carry appended-video motion photos.
    ext = os.path.splitext(file_path)[1].lower()
    if ext not in (".jpg", ".jpeg"):
        return False, None

    try:
        with open(file_path, "rb") as f:
            head = f.read(min(file_size, _XMP_SCAN_BYTES))
            xmp = _extract_xmp(head)

            video_len = _video_length_from_xmp(xmp) if xmp else None
            if video_len and 0 < video_len < file_size:
                offset = file_size - video_len
                if _looks_like_mp4(f, offset):
                    return True, offset

            # Fallback content scan (covers files with stripped/odd XMP).
            offset = _scan_mp4_offset(f, file_size)
            if offset is not None and 0 < offset < file_size:
                return True, offset
    except Exception as e:
        logger.debug("motion photo detection failed for %s: %s", file_path, e)

    return False, None
