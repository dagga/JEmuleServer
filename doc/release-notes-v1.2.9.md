## JEmuleServer 1.2.9

- Monitor every known eD2K server and its state from the admin interfaces. A new **Servers** view in the web panel
  (`GET /api/servers`) and a new `servers` CLI command list all known servers with:
  - their discovery source (`configured`, `server_met`, `client_reported`, `gossip`);
  - their state (`candidate`, `probing`, `verified`, `rejected`);
  - the name learned from the verified server description;
  - the users, files, soft/hard file limits, maximum users and LowID users reported by the last
    `OP_GLOBSERVSTATRES` status;
  - the probe round-trip latency, the age of the last status, the age of the last verification, whether the server
    exposes a UDP obfuscation key, and the number of consecutive missed status probes.
- The monitoring state is derived from the existing server gossip polling, so it stays current without any extra
  traffic: each status answer refreshes the metrics and the probe latency, and three consecutive misses remove a
  server from the verified set as before.
- Report the number and share of **new files inserted into the database** by ingestion channel in a new **Ingestion**
  web view (`GET /api/ingestion`) and the `ingestion` CLI command: client push (`OP_OFFERFILES`), Kad search results
  and searches fanned out to verified eD2K servers. A file counts once per new hash; re-publishing an already known
  file or refreshing its sources is not counted. The counters cover the current server run.
- Follow the search traffic in a new **Searches** web view (`GET /api/searches`) and the `searches` CLI command. For
  both Kad and eD2K servers it reports the searches forked, the requests actually sent, the results returned by
  peers, the files ingested, and the currently active searches. Kad rows also show the illicit files blocked.
- The **Blocks** view and the `blocks` CLI command now read only the 3 most recently banned hashes
  (`ORDER BY added_at DESC LIMIT 3`) instead of loading the whole `banned_hashes` table for display.
- Fix Kad keyword searches returning no results. The Kad2 `KADEMLIA2_SEARCH_RES` payload starts with the responder
  ID before the target ID, and Kad tag lists use a one-byte count; the parser treated the responder ID as the target
  and read the count as a four-byte integer, so every answer was discarded as an unknown search. It now parses the
  real eMule layout, and Kad tags are written with length-prefixed names as the network expects.
- Kad keyword searches now perform an iterative node lookup (`KADEMLIA2_REQ` with type `KADEMLIA_FIND_VALUE`) towards
  the keyword hash before querying the index holders. Previously the server only asked the handful of contacts in its
  bootstrap-sized routing table, which are almost never the nodes responsible for the keyword. The lookup converges
  over several rounds, feeds newly discovered contacts into the routing table, then sends `KADEMLIA2_SEARCH_KEY_REQ`
  to the closest nodes. `KADEMLIA2_REQ` now carries the contacted node's own Kad ID as the sanity check, as eMule
  requires; bucket refreshes use `KADEMLIA_FIND_NODE` for each contact.
- Fix the Kad ID byte order. eMule and aMule serialize a 128-bit Kad ID (`CUInt128`) as four 32-bit words, each
  little-endian, whereas keyword and file hashes are big-endian MD4 digests. The server sent the raw MD4 digest of the
  keyword, so it searched a target no node indexes, and computed XOR distances on the wrong bit order. Every ID is now
  converted at the packet boundary (Kad packets and `nodes.dat`), including the file hashes of search results.
- Accept packed Kad packets (`OP_KADEMLIAPACKEDPROT`, `0xE5`). eMule zlib-compresses every Kad packet larger than
  200 bytes, so search answers carrying results were silently dropped. They are now inflated, with eMule's size bound.
- The iterative lookup now counts towards the Kad search timeout (`kadSearchTimeoutSeconds`), leaving at least one
  second for the index holders to answer, so that results reach the client within the time it waits for them.
- Fix every eD2K server being rejected by the server gossip, which left no verified server to fan searches out to.
  Lugdunum servers send the extended `OP_GLOBSERVSTATRES` fields only to a challenge starting with `0x55AA`, as eMule
  sends it, and otherwise answer with 14 bytes; the extended answer is 34 or 46 bytes, never the 42 bytes the check
  required. The status is now parsed field by field according to its length, like eMule does. The check that the
  server's total file count stays below its hard file limit is removed: that limit applies to the files shared by
  one client, so it rejected nearly every real server.
