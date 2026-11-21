# IPv6 support and limitations

This document explains how JEmuleServer handles IPv6 and what to expect from it.

## 1. What works

- **Listening**: the server binds its TCP and UDP sockets to the IPv6 wildcard address (`::`) when the platform
  supports it, which accepts both IPv6 and IPv4 clients. It falls back to IPv4 (`0.0.0.0`) otherwise. Starting the
  JVM with `-Djava.net.preferIPv4Stack=true` (as `./gradlew run` does) disables IPv6.
- **Connections**: an IPv6 client can connect, log in, publish its files and search.
- **IPv6 sources** (opt-in, `ipv6Sources`): a client that is LowID on IPv4 but reachable on a public IPv6 address is
  given as an IPv6 source to the clients that can read one. See section 3.

## 2. Protocol limits

The classic ed2k protocol only carries 4-byte IPv4 addresses: client IDs, sources (`OP_FOUNDSOURCES`,
`OP_GLOBFOUNDSOURCES`), callbacks and the server address (`OP_SERVERIDENT`). Only the IPv6 source extension below adds
IPv6 addresses, and only to the source answers of the clients that announce it. As a consequence:

- An IPv6 client always gets a **LowID**: its address cannot be its ID. Like any LowID, it is returned as a source
  and other clients reach it through a callback (`OP_CALLBACKREQUESTED` asks it to connect to the requester's IPv4
  address, so it needs IPv4 connectivity as well).
- An IPv6 client **cannot request callbacks**: the callback carries the requester's address, which must be IPv4.
  It can download from HighID sources only.
- The **IP filter** only applies to IPv4 clients (including IPv4-mapped IPv6 addresses): `ipfilter.dat` describes
  IPv4 ranges only.
- The server announces an **IPv4 address** in `OP_SERVERIDENT`: the configured `publicIp` if it is IPv4, otherwise
  an auto-detected IPv4 address, or the IPv4 address the client connected to.

## 3. IPv6 sources

aMule (master, behind its experimental `ENABLE_IPV6` build option) and eMuleQt announce their IPv6 address to the
server, and ed2k-server and eNode-go publish it as a source. JEmuleServer implements the same extension, as specified
by aMule master a6d6290be0 (`docs/IPv6_Server_Sources.md`, `src/include/protocol/ed2k/Client2Server/TCP.h`) and the
"IPv6" section of the ed2k-server README. It is off by default: set `ipv6Sources=true` in `server.properties`. It
stays off, whatever the setting, when the JVM runs with `java.net.preferIPv4Stack=true`: the server could not dial an
IPv6 address. The setting applies to new logins.

### Wire

| Direction | Element | Value | Meaning |
|---|---|---|---|
| client → server | `OP_LOGINREQUEST` tag `CT_MOD_IP_V6` | `0xAE`, `TAGTYPE_HASH`, 16 raw bytes | the client's IPv6 address; it also tells that the client reads IPv6 source records |
| server → client | `OP_IDCHANGE` TCP flags | `0x4000` | the server sends IPv6 source records |
| server → client | `OP_IDCHANGE` TCP flags | `0x8000` | the server accepts `OP_IPV6_UPDATE` (aMule trusts it only with `0x4000`) |
| server → client | `OP_IDCHANGE` TCP flags | `0x400000` | the server dials an address back before publishing it |
| client → server | `OP_IPV6_UPDATE` | `0x63` (`0xE3`), `<IPv6 16>` | a new address, or all zeros to withdraw it; no reply |
| server → client | `OP_SERVERIDENT` tag `ST_IPV6_STATUS` | `0xAB`, uint8 | `0x01` address held, `0x02` reachable, `0x04` dialled back |
| server → client | `OP_FOUNDSOURCES` record | `<0xFFFFFFFF 4><port 2><IPv6 16>` | an IPv6 source |
| server → client | `OP_FOUNDSOURCES_OBFU` record | `<0xFFFFFFFF 4><port 2><crypt options 1>[<user hash 16>]<IPv6 16>` | an IPv6 source; the address is always last, after the obfuscation fields |

The 3 flags are announced together when the option is on. The server does not announce its own IPv6 address
(`ST_IPV6`): clients connect to it over IPv4.

### Dial-back

An announced address is only the client's word, and many home routers drop inbound IPv6. So the server publishes an
address only once it has reached the client on it:

1. The address of the login tag, or of an `OP_IPV6_UPDATE`, is held. A client announcing an address that is not
   global unicast (`2000::/3`: never loopback, link-local, unique local, multicast or IPv4-mapped) reads IPv6 records
   but has no address held.
2. Once the login answers are sent (or at once for an update), the server connects to `[address]:login TCP port` on a
   thread of its own and runs the HighID test there: it sends `OP_HELLO` and expects an `OP_HELLOANSWER` carrying the
   user hash that logged in. The test uses `lowIdCheckTimeoutSeconds` (5 s when it is 0).
3. aMule reads `ST_IPV6_STATUS` from every `OP_SERVERIDENT`, so each step goes to the client in a new one: `0x01`
   while the dial runs (in the login's one, or in one sent as an update's dial starts), then `0x07` (reached: the
   address is published) or `0x05` (not reached: it is not). The server never publishes without a dial, so it never
   sends `0x03`. A withdrawn or unusable address gets an `OP_SERVERIDENT` without the tag.
4. The same address announced again keeps its verdict, except after a failure: it is dialled again, for a pinhole
   opened since (aMule sends it again once in that case). A new address is held back until its own dial.
5. At most 4 dials per connection, the login's included, as ed2k-server: the address goes where the client points
   it. Beyond, a new address is not dialled and its status is `0x05`.

aMule sends an address nothing has proved yet only in `OP_IPV6_UPDATE`, right after an `OP_IDCHANGE` with `0x4000`,
`0x8000` and `0x400000`: the login comes before the flags. Both the update and the dial-back are therefore needed, and
a non-zero `OP_IPV6_UPDATE` makes the client a reader of IPv6 records as the login tag does (ed2k-server
`apply_ipv6_update()`); an all-zero one ends it.

A client that announces the crypt layer in its login (`CT_SERVER_FLAGS`, `SRVCAP_SUPPORTCRYPT` or
`SRVCAP_REQUIRECRYPT`) is dialled with eMule's client-to-client obfuscation first, as ed2k-server and eNode-go do; the
HighID test of the login does the same. A client that requires the layer accepts a plain connection only as the test
of the server it is logging in to, recognised by the server's IPv4 address (`AwaitingTestFromIP`): never over IPv6, and
never in aMule's strict mode, where even the IPv4 HighID test is refused. The plain dial is only the fallback when the
obfuscated handshake fails on a client that does not require the layer; a refused or timed out connection is not
dialled again.

### Who gets IPv6 records

As ed2k-server since 0.9.83:

- only a client that announced an address (login tag or update, not withdrawn) and that is **LowID**: a HighID client
  reaches a LowID source by callback, which always works for it, and an IPv6 dial could fail where the callback would
  not;
- only for a source that is **LowID** on IPv4 and whose address the dial-back reached: a HighID source is reachable
  over IPv4 already. The other sources keep their usual record, and the requester's own record stays a classic one.

IPv6 records never leave the TCP answers to the clients that read them: not `OP_GLOBFOUNDSOURCES` (UDP), not the
answers to another server (relay mode, index update), not the server gossip. There, `0xFFFFFFFF` is above
`0x00FFFFFF` and would pass as the HighID 255.255.255.255. This server never announces `CT_MOD_IP_V6` to another
server, so it is never sent IPv6 records; a source answer carrying one anyway (relay `OP_FOUNDSOURCES`,
`OP_GLOBFOUNDSOURCES`) is read up to that record, which is dropped with the rest of the answer.

## 4. Tests

Integration tests use `InetAddress.getLoopbackAddress()`, so they pass whether the loopback is `127.0.0.1` or `::1`.
The dial-back tests use `::1` and are skipped when the JVM or the host has no IPv6.
`FirewallCheckerTest` dials a test client that requires the crypt layer over `127.0.0.1` and `::1`; that client plays
the receiving side of the obfuscated handshake, written from eMule's protocol description.
