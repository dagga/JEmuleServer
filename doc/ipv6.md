IPv6 support and limitations

This document explains how JEmuleServer handles IPv6 and what to expect from the current implementation.

1. Server binding

- The server will attempt to bind TCP and UDP sockets to the IPv6 wildcard address (`::`). If binding to IPv6 fails,
  it will fall back to IPv4 (`0.0.0.0`). This allows the server to accept both IPv6 and IPv4 clients when the platform supports it.

2. TCP connections

- TCP connections are accepted transparently. The server uses the java.net APIs so remote addresses may be IPv4 or IPv6.
- Client addresses are stored as `InetAddress` in `ClientState`.

3. UDP protocol and IPv6

- The historical eDonkey/ED2K UDP protocol uses 4-byte IPv4 addresses in many messages. That format cannot carry full
  IPv6 addresses.
- To remain backward-compatible, JEmuleServer continues to send the legacy IPv4-format section in UDP source responses.
- If the server discovers sources that are IPv6-only (not IPv4-mapped), it appends an optional extension to the UDP
  `OP_GLOBFOUNDSOURCES` response. The extension format is:

  - "V6" marker (2 ASCII bytes: 0x56, 0x36)
  - 1 byte: IPv6 source count (N)
  - For each source:
    - 16 bytes: IPv6 address (network-order bytes as returned by InetAddress.getAddress())
    - 2 bytes: port (unsigned short, little-endian in current implementation)

- Legacy clients will ignore the extra bytes at the end of the packet (they won't parse them), while updated clients/tests
  can detect and parse the extension.

4. Limitations and recommendations

- UDP responses still contain the legacy IPv4 section first; IPv6-only sources will be provided only in the appended
  extension.
- If you need full IPv6-aware communication for discovery in heterogeneous networks, consider implementing an explicit
  protocol extension that is understood by clients, or use TCP-based discovery mechanisms where IPv6 addresses can be
  exchanged in textual or TLV metadata.

5. Tests and CI

- Integration tests in this repository were updated to be loopback-address agnostic: they use `InetAddress.getLoopbackAddress()`.
  On systems with IPv6 enabled the loopback will be `::1` and tests will validate the presence of IPv6 entries in UDP responses.
