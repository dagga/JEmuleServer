# Cross-server NAT callback (issue #118)

Study and implementation notes for the cross-server form of Lugdunum's NAT callback: a client logged in to
**another** server asks this server to wake one of its LowIDs. Part of #112, on top of the NAT callback on one
server (#115).

Sources, read for this study:

- the design thread [andrey23127/ed2k-server#40](https://github.com/andrey23127/ed2k-server/issues/40), where
  ed2k-server, aMule and eMuleQt agreed the contract (ed2k-server 0.9.86, crypt rule of 0.9.87);
- the ed2k-server README, section "Cross-server NAT callback (issue #40)", and its "`OP_IDCHANGE` TCP flag bits in
  use" tables;
- aMule master a6d6290be0, `docs/NAT_T_Protocol.md`: "Wire values", "Cross-server NAT callback (ed2k-server
  0.9.86)", "Lugdunum NAT callback (eserver 17.15+)", and `src/NatServerHolePunch.h`
  (`BuildForeignCallbackRequest`, `ParseForeignCallbackAnswer`, `ForeignCallbackRequests`), `src/ClientUDPSocket.cpp`
  (how a target reads a wake with requester ID 0). aMule 3.2.0 has none of this; it is only in master, behind the
  experimental build option `ENABLE_NATT_SERVER_COORDINATION` and runtime settings that are off by default;
- eNode-go `ed2k/natcallback.go` (`udpForeignCallbackRequest`, `udpNatKeepalive`, `foreignFallback`,
  `sendForeignNotices`).

## Why through the target's own server

The first proposal in ed2k-server#40 (eNode-go's `PR_NAT`) was a registry keyed by user hash on any participating
server. aMule objected, and ed2k-server agreed, for privacy: user hashes are public (source lists, Kad), so such a
registry turns any user hash into a lookup of that user's current public IP and port, at any time, by anybody, and
its wake-up even makes a logged-in client register on request. Routing through the target's own server keeps the
property a single server already has: **you must name the target's current server and its current LowID before you
learn anything**. A requester gets both from source exchange, which carries each source's server next to its
LowID in every eMule version. There is no standing registration, nothing keyed by hash, and no sweep of other
servers: a client asks only a server already in its own server list (otherwise a peer could aim every client's
status pings at a victim), and only one that announces the flag below.

## Wire

All under the eDonkey protocol byte `0xE3`; integers little-endian; an IPv4 is written as eMule stores it (first
octet in the lowest byte).

| Step | Wire |
|---|---|
| Announce | `0x100000` `SRV_UDPFLG_FOREIGN_NAT_CALLBACK` in the UDP flags of every status reply (`OP_GLOBSERVSTATRES`, plain and obfuscated, to clients and servers), of `OP_SERVER_DESC_RES`, and in `ST_UDPFLAGS` of `OP_SERVERIDENT` at login |
| Key | the status reply gives the requester this server's UDP key for its address, `IPObfuscate(secret, requester IP)` |
| Request, requester to this server, UDP | `0xA6` `OP_NAT_CALLBACKREQUEST` `<target ID 4><0 4>[<crypt 1>]`, server-key obfuscated, to the obfuscation UDP port (TCP + 12). Requester ID 0 marks it foreign. `crypt` is the requester's `(CT_SERVER_FLAGS >> 9) & 7`; absent means 0 (aMule always sends it). The datagram's source is the requester's endpoint |
| Wake, this server to the target | TCP `0x37` `OP_NAT_CALLBACKREQUESTED` `<requester IP 4><requester UDP port 2><0 4><crypt 1>`, 11 bytes, never a hash (the server does not know the requester's), `crypt` as the requester sent it; or the same body in UDP `0xA7` to the endpoint of the target's own `0xA6` when that one is less than 20 s old, as for a same-server notice. No `0x61` ever follows a wake with requester ID 0 |
| Target's endpoint, target to this server, UDP | `0x9F` `OP_NATT_KEEPALIVE` `<user hash 16>`, from the login IP; its source is the target's public UDP endpoint |
| Answer, this server to the requester, UDP | `0xA7` `OP_NAT_CALLBACKREQUESTED_UDP` `<target IP 4><target UDP port 2><target ID 4><crypt 1><target hash 16>`, always exactly 27 bytes, obfuscated with the requester's key, sent from the port the request arrived on to the requester's endpoint. `crypt` is the target's options, with `0x80` when both sides support the crypt layer and one asks for it. Sent on the target's first `0x9F` after the wake, or after 3 s with the endpoint the server already has; resent at +1.2 s and +3 s (the requester takes the first copy) |

**Who gets an answer.** Only the requester of a target that announced ed2k-server's hole-punch extension
(`0x100000` `SRVCAP_HOLEPUNCH_EXTENSION` in its `CT_SERVER_FLAGS`): on an extension server, such a target answers a
requester-ID-0 wake with its `0x9F` burst, punches towards the requester and waits to be dialled; the requester
dials, with the target's hash from the `0xA7` (it obfuscates the first QUIC Initial or the uTP key frame). A
Lugdunum-only target (`0x1000` without `0x100000`) gets the `0x37` alone and dials the requester itself, as on
Lugdunum; its requester gets nothing.

**Three different `0x100000`.** The same value is `SRVCAP_HOLEPUNCH_EXTENSION` in the client's `CT_SERVER_FLAGS`,
`SRV_TCPFLG_HOLEPUNCH_EXTENSION` in `OP_IDCHANGE`'s TCP flags, and `SRV_UDPFLG_FOREIGN_NAT_CALLBACK` in the server's
UDP flags (aMule master, "Wire values"). JEmuleServer sets only the UDP flag: it does not implement ed2k-server's
`0x60`/`0x61`/`0x62` hole punch, so it never announces the TCP flags `0x20000` or `0x100000`.

### Ambiguities and choices

- **What an aMule target does here.** aMule treats a requester-ID-0 wake as a cross-server wake (`0x9F` burst,
  punch, wait to be dialled) only when its server's hole-punch extension is active, which needs
  `SRV_TCPFLG_HOLEPUNCH_EXTENSION` in `OP_IDCHANGE`; elsewhere it keeps Lugdunum's meaning and dials the requester
  (`ClientUDPSocket.cpp`, `CServerConnect::IsHolePunchExtensionActive()`). And aMule sends `0x9F` only to a server
  that sets `SRV_TCPFLG_LOWID_HOLEPUNCH` (`0x20000`, `NatServerHolePunch::ShouldSendKeepalive()`). Since JEmuleServer
  sets neither, an aMule target here dials the requester, Lugdunum's way, and an aMule `0x9F` only arrives if the
  server sets `0x20000` one day. The answer path is still implemented to the contract: it works for any client that
  sends `0x9F` on a wake (eMuleQt, or aMule if the TCP flags change), and with the 3 s fallback it uses the last
  `0x9F` the target sent. This should be checked live with aMule master, as offered in #112.
- **Lugdunum's callback must be active on the target.** aMule acts on `0x37` only when its server answered its
  `0x1000` opt-in with the 24-byte `OP_IDCHANGE` and the `0x1000` TCP flag (`IsLugdunumNatCallbackActive()`), which
  `natCallback` gives (#115).
- **Wake by UDP `0xA7`.** ed2k-server and eNode-go wake the target by UDP `0xA7` instead of `0x37` when it sent its
  own `0xA6` less than 20 s before (it then has a mapping towards the server), and aMule reads a requester-ID-0
  `0xA7` as a wake then. JEmuleServer does the same, with the endpoint and socket the same-server callback keeps for
  that target's last `0xA6`; the body is the 11-byte wake, which aMule reads from 10 bytes on.
- **A sender with a client logged in here.** eNode-go ignores a foreign `0xA6` from an address that has a TCP
  connection to it. ed2k-server does not, and forwarded such requests on ed2k-rust (two clients behind one NAT on two
  servers). JEmuleServer follows ed2k-server: the requester ID decides, not the address. A requester ID other than 0
  is the same-server path (#115).
- **The 3 s fallback endpoint.** ed2k-server falls back to the last `0x9F` port, then the last UDP port seen from
  the address, then the announced `0xF9` port. eNode-go uses the target's recent `0xA6`, then its last `0x9F`.
  JEmuleServer uses only the last `0x9F` of the target's session (at most 10 minutes old): the endpoint it hands out
  is always one the target reported itself. Without one, nothing is sent.

## Abuse analysis

The cross-server path is the first NAT-T path open to a host with no session here: without a session there is
nothing to kick, so the limits sit on the source address and on the target (ed2k-server#40, andrey23127).

1. **Locating a client.** Anyone who knows a LowID could learn that client's public UDP endpoint, or sweep LowIDs
   to collect endpoints. LowIDs are small numbers, and JEmuleServer hands them out in sequence, so guessing is
   cheap. Limits:
   - only a LowID that opted in (`0x1000` in `CT_SERVER_FLAGS`) is ever woken, and only one that also announced the
     extension has its endpoint sent; a stock client never opts in;
   - the endpoint sent is the one the target itself reported by `0x9F`, the same it would give a requester of its
     own server;
   - no oracle: an unknown, HighID, gone, stock or Lugdunum-only target, a plain request, a crypt mismatch or a
     limit reached all get no answer at all;
   - at most 60 different target IDs per source IP in 10 minutes, counted whether they exist or not: a sweep hits
     the limit within a minute, while aMule asks at most 30 targets per server in 10 minutes.
2. **Spoofed sources.** A forged source address would aim the wake, and the answers, at a third party. The request
   must decode with the server UDP key of its source address, which only that address gets in its status reply: a
   request that decodes proves its address. A plain foreign `0xA6` is dropped. An IPv6 source is dropped too, since
   neither `0x37` nor `0xA7` can carry its address.
3. **Notice spam.** Any host could make the server send a target many `0x37`. At most 6 foreign wakes per target per
   minute, one wake per requester endpoint and target while its answer is in flight, and at most 4 requesters waiting
   for one target (the oldest is dropped).
4. **Amplification.** One request (about 30 bytes obfuscated) brings at most three `0xA7` of 37 bytes, only to the
   proven source, and only for an extension target. Per source IP: a burst of 10 requests, then one a second. For
   the whole server: a burst of 100 wakes, then 20 a second, and at most 1024 requests in flight.
5. **Memory.** The per-address and per-target state is bounded (8192 entries each, pruned every 10 s); a new
   address beyond it is refused until older entries expire, so a flood from many addresses fails closed.
6. **IP filter.** An address refused by the IP filter is refused here too.

How ed2k-server limits it (README, issue #40): per source IP a burst of 10 then 1/s; per source IP at most 60 target
IDs in 10 minutes, known or not; per target at most 6 foreign wakes a minute; one answer in flight per requester
address and target; IP-filtered addresses refused; bot detector fed. eNode-go uses the same numbers
(`natForeignPerTargetPerMinute`, `natForeignMaxTargetsPerAddress`, `natForeignTargetWindow`), plus at most 4
requesters waiting per target. aMule's requester stays below them: a burst of 5 then one every 2 s per server, at
most 30 targets in 10 minutes, a target that never answered not asked again for 10 minutes
(`NatServerHolePunch::ForeignCallbackRequests`).

## Conclusion

The design holds up: it gives a requester nothing a client of this server could not already get, it needs no state
for idle clients, and the agreed limits bound both the endpoint disclosure and the traffic. It is implemented, off by
default:

- `natCallbackCrossServer=true` (needs `natCallback=true`, hot): the server takes foreign `0xA6`, wakes the target
  with `0x37` (or `0xA7` after its own recent `0xA6`), and answers an extension target's requester with `0xA7` on its next `0x9F` or after 3 s, then at
  +1.2 s and +3 s; it announces `SRV_UDPFLG_FOREIGN_NAT_CALLBACK` only while it does;
- mitigations: server-key obfuscation required (proves the source), IPv4 only, IP filter, opted-in LowIDs only,
  answers only for extension targets and only with the target's own `0x9F` endpoint, crypt rule, the per-IP,
  per-target and server-wide limits above, bounded state, silent refusals;
- crypt rule (ed2k-server 0.9.87, eNode-go): with an answer, refused only when one side requires the crypt layer and
  the other does not support it; for a Lugdunum-only target, which dials without the requester's hash, refused when
  either side requires it.

Code: `org.jemule.network.natt`. `NatCallbackService` (#115: sessions of the clients that opted in, keepalive
table, same-server callback) hands a request with requester ID 0 to `CrossServerCallback` from
`handleForeignRequest`, and each accepted `0x9F` from `onKeepalive`; the maintenance scheduler calls its `tick()`
every 250 ms for the 3 s waits and the resends. A foreign wake also takes a token from the target's same-server
notice rate (20, then one a second). Tests: `CrossServerCallbackTest`, `NatCallbackCrossServerTest`.
