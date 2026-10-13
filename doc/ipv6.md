# IPv6 support and limitations

This document explains how JEmuleServer handles IPv6 and what to expect from it.

## 1. What works

- **Listening**: the server binds its TCP and UDP sockets to the IPv6 wildcard address (`::`) when the platform
  supports it, which accepts both IPv6 and IPv4 clients. It falls back to IPv4 (`0.0.0.0`) otherwise. Starting the
  JVM with `-Djava.net.preferIPv4Stack=true` (as `./gradlew run` does) disables IPv6.
- **Connections**: an IPv6 client can connect, log in, publish its files and search.

## 2. Protocol limits

The ed2k protocol only carries 4-byte IPv4 addresses: client IDs, sources (`OP_FOUNDSOURCES`,
`OP_GLOBFOUNDSOURCES`), callbacks and the server address (`OP_SERVERIDENT`). No eMule client knows any IPv6
extension, so the server does not invent one. As a consequence:

- An IPv6 client always gets a **LowID**: its address cannot be its ID. Like any LowID, it is returned as a source
  and other clients reach it through a callback (`OP_CALLBACKREQUESTED` asks it to connect to the requester's IPv4
  address, so it needs IPv4 connectivity as well).
- An IPv6 client **cannot request callbacks**: the callback carries the requester's address, which must be IPv4.
  It can download from HighID sources only.
- The **IP filter** only applies to IPv4 clients (including IPv4-mapped IPv6 addresses): `ipfilter.dat` describes
  IPv4 ranges only.
- The server announces an **IPv4 address** in `OP_SERVERIDENT`: the configured `publicIp` if it is IPv4, otherwise
  an auto-detected IPv4 address, or the IPv4 address the client connected to.

## 3. Tests

Integration tests use `InetAddress.getLoopbackAddress()`, so they pass whether the loopback is `127.0.0.1` or `::1`.
