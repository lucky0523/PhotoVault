"""LAN address detection.

Both the server QR code (``/server/info``) and the 关于服务端 page
(``/server/about``) need to answer the same question: which addresses can a
phone on the same network use to reach this server?

The original implementation answered it with a single UDP probe — connecting a
UDP socket to 8.8.8.8 sends no packets, it just makes the kernel pick the source
address for that route. That is one line of code, but it reports the
*internet-facing* address. On any machine running a VPN that is the tunnel
address (utun / tun / wg / ppp), which no phone on the local Wi-Fi can reach.
For example a Mac with Wi-Fi 192.168.1.40 and a VPN on utun4 reports
10.225.164.168, because the default route belongs to the tunnel.

So we enumerate interfaces instead, and keep only IPv4 addresses that have a
**broadcast address**. That is exactly the property we care about: loopback and
point-to-point tunnels have no broadcast address, while real Ethernet / Wi-Fi /
bridge LANs do.

There is no stdlib API for interface enumeration (that would be
``psutil.net_if_addrs()``, and this is not worth a new dependency), so we parse
the output of the standard OS tools and keep the UDP probe as a last-resort
fallback for minimal container images that ship neither.
"""

from __future__ import annotations

import logging
import shutil
import socket
import subprocess
from typing import List, Optional

logger = logging.getLogger("photovault.network")

# Tools are given a hard timeout so a wedged process can never hang a request.
_COMMAND_TIMEOUT_SECONDS = 2.0


def _is_usable_lan_ip(ip: str) -> bool:
    """Reject loopback and link-local (self-assigned) addresses."""
    return bool(ip) and not ip.startswith("127.") and not ip.startswith("169.254.")


def _run_command(argv: List[str]) -> Optional[str]:
    """Run a command and return its stdout, or None if it is unusable."""
    if shutil.which(argv[0]) is None:
        return None
    try:
        result = subprocess.run(
            argv,
            capture_output=True,
            text=True,
            timeout=_COMMAND_TIMEOUT_SECONDS,
            check=False,
        )
    except Exception:
        logger.debug("Could not run %s", " ".join(argv), exc_info=True)
        return None
    if result.returncode != 0:
        return None
    return result.stdout


def _parse_ip_addr(output: str) -> List[str]:
    """Parse ``ip -o -4 addr show`` (Linux / iproute2).

    Lines look like::

        2: eth0    inet 192.168.1.5/24 brd 192.168.1.255 scope global eth0\\  ...
        5: tun0    inet 10.8.0.2/24 scope global tun0\\  ...

    Only entries carrying ``brd`` are real broadcast LANs, so ``tun0`` above is
    correctly skipped.
    """
    ips: List[str] = []
    for line in output.splitlines():
        fields = line.split()
        if "inet" not in fields or "brd" not in fields:
            continue
        address = fields[fields.index("inet") + 1].split("/")[0]
        if _is_usable_lan_ip(address):
            ips.append(address)
    return ips


def _parse_ifconfig(output: str) -> List[str]:
    """Parse ``ifconfig -a`` (macOS / BSD).

    Address lines look like::

        inet 192.168.1.40 netmask 0xffffff00 broadcast 192.168.1.255
        inet 10.225.164.168 --> 10.225.164.168 netmask 0xfffff000
        inet 127.0.0.1 netmask 0xff000000

    Requiring the ``broadcast`` keyword keeps the first and drops the
    point-to-point tunnel and loopback.
    """
    ips: List[str] = []
    for line in output.splitlines():
        fields = line.split()
        if not fields or fields[0] != "inet" or "broadcast" not in fields:
            continue
        address = fields[1]
        if _is_usable_lan_ip(address):
            ips.append(address)
    return ips


def _enumerate_broadcast_ips() -> List[str]:
    """Return every broadcast-capable IPv4 address, in the order the OS lists them."""
    # Linux first: `ip` exists on virtually every modern distro, including the
    # NAS targets, whereas `ifconfig` is deprecated there and often absent.
    output = _run_command(["ip", "-o", "-4", "addr", "show"])
    if output:
        ips = _parse_ip_addr(output)
        if ips:
            return ips

    output = _run_command(["ifconfig", "-a"])
    if output:
        return _parse_ifconfig(output)

    return []


def _probe_default_route_ip() -> List[str]:
    """Fallback: the source address the kernel uses for the default route.

    Connecting a UDP socket sends no packets; it only performs a route lookup.
    This is the address that may belong to a VPN tunnel, hence fallback only.
    """
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.settimeout(1.0)
        try:
            s.connect(("8.8.8.8", 80))
            address = s.getsockname()[0]
        finally:
            s.close()
    except Exception:
        return []
    return [address] if _is_usable_lan_ip(address) else []


def detect_lan_ips() -> List[str]:
    """Return the addresses a client on the local network can use, best first.

    The default-route address is moved to the front when it is itself a real LAN
    address: on a machine without a VPN that is the interface actually facing the
    router, which is the best single guess for the QR code. When it is a tunnel
    address it does not survive the broadcast filter and is simply dropped.
    """
    ips = _enumerate_broadcast_ips()

    if not ips:
        # Neither `ip` nor `ifconfig` available (slim container image).
        return _probe_default_route_ip()

    preferred = _probe_default_route_ip()
    for address in reversed(preferred):
        if address in ips:
            ips.remove(address)
            ips.insert(0, address)

    # Deduplicate while preserving order (aliased interfaces can repeat).
    seen: set[str] = set()
    unique: List[str] = []
    for address in ips:
        if address not in seen:
            seen.add(address)
            unique.append(address)
    return unique
