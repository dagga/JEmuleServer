TODO tracks implementation work, not configuration reference. Configuration defaults and option descriptions live in
[`server.properties`](server.properties); see the [configuration guide](doc/configuration.md).

## Completed

- [x] Inverted keyword index with boolean search trees and numeric size filters. Results are ranked by source count, the result cap is configurable, and a query word no indexed file contains no longer empties the search.
- [x] Regularly update `ipfilter.dat`; apply the update only when fewer than 10% of normalized ranges differ. Otherwise, keep the existing filter and record the rejection in the adjacent `.trace` file.
- [x] Java memory-optimized index: store database-loaded `FT_SOURCES` tags compactly and reconstruct them lazily; share one source map among files without online sources. The index measured 363 MB of heap for 999,913 files. Rust-specific slabs, intrusive indexes, and jemalloc tuning do not apply to this Java server.
- [x] Client-server TCP/UDP obfuscation, fake-file heuristics and persistent hash bans; added a Lugdunum-compatible server-to-server UDP obfuscation codec with per-peer key derivation, randomized salt and padding, and RC4 framing.
- [x] Server-to-server gossip uses configurable IPv4 seed peers, challenge-matched status and tagged-description checks, and rejects MLDonkey identities or malformed/implausible peers. Verified peers exchange discovered server lists using the obfuscation codec. These checks verify protocol behavior, not server identity cryptographically.
- [x] Collect client-reported server lists and gossip discoveries into the verified server pool; periodically refresh server status and remove peers after repeated missed probes. Client searches are also sent to verified servers, and matching, filtered results are persisted in the file index/H2 like Kad results. Client-reported lists are accepted only when explicitly sent as server-list packets; standard clients do not automatically upload their local list.
- [x] NAT traversal (NAT-T) hole-punch coordination for LowID↔LowID transfers: callback requests now notify both clients of each other's observed public IP, listening port, crypt options, and user hash so they can initiate TCP connections simultaneously.
- [x] Built-in admin web panel on configurable loopback-only HTTP (`adminWebPort`, default 8080), with server-side client search, named verified server peers, IP/fake-file filters, persistent hash blocks, runtime settings, and potential-bot matches labeled as nickname heuristics only. The CLI includes client search plus `peers`, `filters`, `bots`, `blocks`, and `settings` commands.

## Remaining

- [ ] IPv6 (opt-in): clients can connect over IPv6, and a client that is LowID on IPv4 but has a public IPv6 is published as an IPv6 source to peers that can parse it.
- [ ] OFFERFILES v1 (opt-in): tells capable clients how many files per batch and how often they may publish, and holds them to it, so a large library is published in minutes instead of hours.
- [ ] HighID verified by a client-to-client hello, not only a TCP connect: a port forwarded to a different client behind the same NAT no longer earns HighID.
- [ ] IP filtering in eMule guarding.p2p format, with per-range hit statistics.
- [ ] GeoIP country and provider for IPv4 and IPv6 (MaxMind DB, with the ip-to-country.csv fallback), bot/scanner detection, CSAM-publisher banning.
- [ ] Work admission: bounded sockets, logins, probes, searches and UDP, sized for 1 000 000 clients and the reconnect storm after a restart.
- [ ] Hot-reloadable filter lists and config without a restart.