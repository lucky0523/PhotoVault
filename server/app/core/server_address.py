"""Server address validation, host scope, effective-address fallback and the
single encoder for the server QR code payload.

服务器二维码协议 v1，见 protocol/README.md 与 protocol/server-qr-v1.json。

Everything here is a pure function or an immutable value and depends on the
standard library only. **This module must never import ``app.core.config`` or
any other ``app.*`` module**: ``config.py`` imports this module at top level
(to re-validate ``.server_address.json`` on read), and almost every module in
the app imports ``config.py``, so any ``app.*`` import here would create an
import cycle.

Regex convention (shared with the Web mirror in ``web/src/utils/serverAddress.ts``):
every pattern is matched against the *whole* string with ``re.fullmatch`` —
Python's ``$`` also matches before a trailing newline, and ``int("80\\n")``
silently accepts it, so digits are only converted after a ``[0-9]`` full match.
The single exception is the port pre-check, which is a prefix match by design.
Digits are always ``[0-9]``, never ``\\d`` (which matches Unicode digits).

Public API (``app.api.server`` imports only these names):
``AddressError``, ``ADDRESS_ERROR_MESSAGES``, ``same_as_other_message``,
``AddressCheck``, ``validate_address``, ``validate_address_pair``,
``host_scope``, ``detected_lan_address``, ``EffectiveAddresses``,
``resolve_effective_addresses``, ``encode_connect_payload``,
``MAX_ADDRESS_LENGTH``, ``MAX_PAYLOAD_LENGTH``, ``PAYLOAD_PREFIX``,
``PAYLOAD_VERSION``.
"""

from __future__ import annotations

import ipaddress
import re
from dataclasses import dataclass
from enum import Enum
from typing import Literal, Optional
from urllib.parse import quote

MAX_ADDRESS_LENGTH = 255
# 37 bytes of fixed overhead + 2 × (255 + 8 bytes of percent-encoding growth).
MAX_PAYLOAD_LENGTH = 563
PAYLOAD_PREFIX = "photovault://connect?"
PAYLOAD_VERSION = 1

_DEFAULT_PORTS = {"http": 80, "https": 443}

# Port pre-check (§3.1 step 4): a *prefix* match, same semantics as the fixed
# Android PORT_REGEX but with [0-9] instead of \d.
_PORT_PRECHECK = re.compile(r"^[A-Za-z][A-Za-z0-9+.-]*://(\[[^\]]+\]|[^/:?#@\[]+):([0-9]+)")
_AUTHORITY_END = re.compile(r"[/?#\\]")
_PRINTABLE_ASCII = re.compile(r"[\x21-\x7e]*")
_PORT_DIGITS = re.compile(r"[0-9]{1,5}")
_IPV6_CHARS = re.compile(r"[0-9A-Fa-f:]*")
_DIGITS_AND_DOTS = re.compile(r"[0-9.]+")
_IPV4_OCTET = re.compile(r"0|[1-9][0-9]{0,2}")
_HOST_LABEL = re.compile(r"[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?")
_EXPLICIT_HTTP_SCHEME = re.compile(r"(?i)https?://")

_LAN_NETWORKS_V4 = tuple(
    ipaddress.ip_network(n)
    for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16")
)
_LAN_NETWORKS_V6 = tuple(ipaddress.ip_network(n) for n in ("fe80::/10", "fc00::/7"))
_UNSPECIFIED_V4_NETWORK = ipaddress.ip_network("0.0.0.0/8")


class AddressError(str, Enum):
    """Validation error codes; same names as Android ``AddressError``."""

    TOO_LONG = "TOO_LONG"
    BAD_SCHEME = "BAD_SCHEME"
    INVALID = "INVALID"
    BAD_PORT = "BAD_PORT"
    HAS_EXTRA = "HAS_EXTRA"
    SAME_AS_OTHER = "SAME_AS_OTHER"


# Wording is identical to Android values-zh validation_address_*.
ADDRESS_ERROR_MESSAGES: dict[AddressError, str] = {
    AddressError.TOO_LONG: "地址过长（最多 255 个字符）",
    AddressError.BAD_SCHEME: "仅支持 http:// 或 https://",
    AddressError.INVALID: "地址格式不正确",
    AddressError.BAD_PORT: "端口须在 1–65535 之间",
    AddressError.HAS_EXTRA: "地址只能包含协议、主机和端口",
}


def same_as_other_message(field: Literal["public_address", "lan_address_override"]) -> str:
    """Text for ``SAME_AS_OTHER`` on the given field (keys of the 422 ``fields``)."""
    if field == "public_address":
        return "与局域网地址相同"
    if field == "lan_address_override":
        return "与公网地址相同"
    raise ValueError(f"unknown address field: {field!r}")


@dataclass(frozen=True)
class AddressCheck:
    """Result of validating one address.

    ``canonical`` is ``None`` both when the input was empty (clear the value,
    ``error`` is ``None``) and when validation failed (``error`` is set).
    """

    canonical: Optional[str]
    error: Optional[AddressError]


def _err(code: AddressError) -> AddressCheck:
    return AddressCheck(None, code)


def _canonical_ipv6(text: str) -> Optional[str]:
    """RFC 5952 form of a bracket-less IPv6 literal, or None if unacceptable."""
    if not _IPV6_CHARS.fullmatch(text):
        return None  # also excludes zone ids (%) and dotted IPv4 tails
    try:
        ip = ipaddress.IPv6Address(text)
    except ValueError:
        return None
    if ip.ipv4_mapped is not None:
        return None  # ::ffff:0:0/96
    # ipaddress renders RFC 5952: lowercase, no leading zeros, longest run of
    # >= 2 zero groups compressed (first one on ties).
    return str(ip)


def validate_address(raw: Optional[str]) -> AddressCheck:
    """Validate and canonicalise a server address (design §3.1 steps 1–13)."""
    # 1. Empty means "clear".
    if raw is None or not raw.strip():
        return AddressCheck(None, None)

    # 2. Trim and bound the raw length.
    t = raw.strip()
    if len(t) > MAX_ADDRESS_LENGTH:
        return _err(AddressError.TOO_LONG)

    # 3. Scheme.
    if "://" in t:
        idx = t.index("://")
        scheme = t[:idx].lower()
        if scheme not in _DEFAULT_PORTS:
            return _err(AddressError.BAD_SCHEME)
        rest = t[idx + 3:]
    else:
        scheme = "http"
        rest = t

    # 4. Port pre-check (prefix match only).
    m = _PORT_PRECHECK.match(f"{scheme}://{rest}")
    if m is not None:
        digits = m.group(2)
        if len(digits) > 5 or not 1 <= int(digits) <= 65535:
            return _err(AddressError.BAD_PORT)

    # 5. Split authority from path / query / fragment / backslash.
    end = _AUTHORITY_END.search(rest)
    if end is None:
        authority, remainder = rest, ""
    else:
        authority, remainder = rest[: end.start()], rest[end.start():]

    # 6. userinfo.
    has_userinfo = "@" in authority
    hostport = authority.rsplit("@", 1)[-1]

    # 7. Only printable ASCII (no IDN, whitespace or control characters).
    if not _PRINTABLE_ASCII.fullmatch(hostport):
        return _err(AddressError.INVALID)

    port: Optional[int] = None
    if hostport.startswith("["):
        # 8. IPv6 literal.
        close = hostport.find("]")
        if close == -1:
            return _err(AddressError.INVALID)
        after = hostport[close + 1:]
        if after:
            if not after.startswith(":") or not _PORT_DIGITS.fullmatch(after[1:]):
                return _err(AddressError.INVALID)
            port = int(after[1:])
        compressed = _canonical_ipv6(hostport[1:close])
        if compressed is None:
            return _err(AddressError.INVALID)
        host = f"[{compressed}]"
    else:
        # 9. IPv4 literal or host name.
        if hostport.count(":") > 1:
            return _err(AddressError.INVALID)
        if ":" in hostport:
            host, port_text = hostport.split(":", 1)
            if not _PORT_DIGITS.fullmatch(port_text):
                return _err(AddressError.INVALID)
            port = int(port_text)
        else:
            host = hostport
        host = host.lower()
        if _DIGITS_AND_DOTS.fullmatch(host):
            octets = host.split(".")
            if len(octets) != 4 or not all(
                _IPV4_OCTET.fullmatch(o) and int(o) <= 255 for o in octets
            ):
                return _err(AddressError.INVALID)
        else:
            if not host or len(host) > 253:
                return _err(AddressError.INVALID)
            if not all(_HOST_LABEL.fullmatch(label) for label in host.split(".")):
                return _err(AddressError.INVALID)

    # 10. Port range (only reachable when step 4 did not see the port).
    if port is not None and not 1 <= port <= 65535:
        return _err(AddressError.INVALID)

    # 11. Nothing but scheme, host and port.
    if has_userinfo or remainder not in ("", "/"):
        return _err(AddressError.HAS_EXTRA)

    # 12. Canonical form; default port omitted.
    canonical = f"{scheme}://{host}"
    if port is not None and port != _DEFAULT_PORTS[scheme]:
        canonical += f":{port}"

    # 13. Adding "http://" may have pushed it over the limit.
    if len(canonical) > MAX_ADDRESS_LENGTH:
        return _err(AddressError.TOO_LONG)
    return AddressCheck(canonical, None)


def validate_address_pair(
    public_raw: Optional[str], lan_raw: Optional[str]
) -> tuple[AddressCheck, AddressCheck]:
    """Validate both addresses; identical canonical forms flag both fields."""
    pub = validate_address(public_raw)
    lan = validate_address(lan_raw)
    if (
        pub.error is None
        and lan.error is None
        and pub.canonical is not None
        and pub.canonical == lan.canonical
    ):
        same = _err(AddressError.SAME_AS_OTHER)
        return same, same
    return pub, lan


def _host_of(canonical: str) -> str:
    rest = canonical.split("://", 1)[-1]
    if rest.startswith("["):
        return rest[1: rest.find("]")]
    return rest.split(":", 1)[0]


def host_scope(canonical: str) -> Literal["public", "lan", "loopback"]:
    """Classify a canonical address's host (design §3.4)."""
    host = _host_of(canonical)
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        if host == "localhost" or host.endswith(".localhost"):
            return "loopback"
        if host.endswith(".local"):
            return "lan"
        return "public"

    if ip.is_loopback or ip.is_unspecified:
        return "loopback"
    if isinstance(ip, ipaddress.IPv4Address):
        if ip in _UNSPECIFIED_V4_NETWORK:
            return "loopback"
        networks = _LAN_NETWORKS_V4
    else:
        networks = _LAN_NETWORKS_V6
    if any(ip in net for net in networks):
        return "lan"
    return "public"


def detected_lan_address(ips: list[str], port: int) -> Optional[str]:
    """Canonical ``http://{ips[0]}:{port}``, or None if empty / invalid.

    Only the first detected IP is used; if it fails validation the detection is
    treated as empty (the rest are not tried).
    """
    if not ips:
        return None
    check = validate_address(f"http://{ips[0]}:{port}")
    if check.error is not None:
        return None
    return check.canonical


@dataclass(frozen=True)
class EffectiveAddresses:
    """The addresses that go into the QR code, with where each one came from."""

    public_address: Optional[str]
    public_source: Optional[Literal["configured", "origin"]]
    lan_address: Optional[str]
    lan_source: Optional[Literal["configured", "origin", "detected"]]


def resolve_effective_addresses(
    *,
    configured_public: str,
    configured_lan: str,
    origin: Optional[str],
    detected_lan_ips: list[str],
    server_port: int,
) -> EffectiveAddresses:
    """Apply the fallback rules of design §4.2.

    ``configured_*`` must already be canonical (or ""), as returned by
    ``get_server_address_settings``. ``origin`` is untrusted browser input: it
    is only considered when it carries an explicit ``http(s)://`` and passes
    ``validate_address``; otherwise it is silently ignored.
    """
    o: Optional[str] = None
    if origin is not None and _EXPLICIT_HTTP_SCHEME.match(origin.strip()):
        o = validate_address(origin).canonical
    o_scope = host_scope(o) if o else None

    public: Optional[str] = None
    public_source: Optional[Literal["configured", "origin"]] = None
    if configured_public:
        public, public_source = configured_public, "configured"
    elif o and o_scope == "public":
        public, public_source = o, "origin"

    lan: Optional[str] = None
    lan_source: Optional[Literal["configured", "origin", "detected"]] = None
    if configured_lan:
        lan, lan_source = configured_lan, "configured"
    elif o and o_scope == "lan":
        lan, lan_source = o, "origin"
    else:
        detected = detected_lan_address(detected_lan_ips, server_port)
        if detected:
            lan, lan_source = detected, "detected"

    if public and lan and public == lan:
        lan, lan_source = None, None

    return EffectiveAddresses(
        public_address=public,
        public_source=public_source,
        lan_address=lan,
        lan_source=lan_source,
    )


def encode_connect_payload(public: Optional[str], lan: Optional[str]) -> Optional[str]:
    """服务器二维码协议 v1 的唯一编码点（见 protocol/README.md）。

    - ``public`` and ``lan`` both ``None`` / ``""``: returns ``None``.
    - A non-empty argument must already be canonical
      (``validate_address(x) == AddressCheck(x, None)``), else ``ValueError``.
    - Both non-empty and equal: ``ValueError``.
    - Result longer than ``MAX_PAYLOAD_LENGTH``: ``ValueError``.

    These ``ValueError``s are programming errors (handled by the global 500
    handler). Output order is ``v``, ``public``, ``lan``; a missing address is
    omitted entirely; values are ``quote(addr, safe="")``.
    """
    public = public or None
    lan = lan or None
    if public is None and lan is None:
        return None
    for name, value in (("public", public), ("lan", lan)):
        if value is not None and validate_address(value) != AddressCheck(value, None):
            raise ValueError(f"{name} address is not canonical: {value!r}")
    if public is not None and public == lan:
        raise ValueError("public and lan addresses must differ")

    payload = f"{PAYLOAD_PREFIX}v={PAYLOAD_VERSION}"
    if public is not None:
        payload += "&public=" + quote(public, safe="")
    if lan is not None:
        payload += "&lan=" + quote(lan, safe="")
    if len(payload) > MAX_PAYLOAD_LENGTH:
        raise ValueError(f"payload too long: {len(payload)} > {MAX_PAYLOAD_LENGTH}")
    return payload
