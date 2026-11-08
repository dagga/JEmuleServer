## JEmuleServer 1.5.1

### Improvements

- **Relay mode.** With `relayServer=<host>:<TCP port>` set, a server keeps its own Kad node, its own peer servers and
  its own clients, but uses the index of the relay server and feeds it:
  - the searches of its clients, and the UDP global searches of other servers' clients, are answered by the relay
    server's index (up to 900 results per client search); Kad and the other servers are searched meanwhile;
  - the source requests get the sources among its own clients, Kad's, then the relay server's HighID sources;
  - every file it finds (Kad, other servers, index update, its clients' `OP_OFFERFILES`) is posted to the relay
    server by batches of 2,000, gzip compressed, to `http://<relay server>:<relayFeedPort>/api/relay/files` with
    `Authorization: Bearer <relayToken>`. A file is sent again only when its source count rose. While the relay
    server cannot be reached, the files wait in memory (200,000 at most, the oldest are lost beyond);
  - the local index is no longer written: it only answers while the relay server cannot, and keeps the online
    sources of the clients connected here;
  - the relay server is no longer searched as a peer as well: it is asked over its TCP sessions already.

  On the relay server, `relayClients` lists the relays allowed to post their files (host names, IPs or CIDR blocks; empty:
  nobody) and `relayToken` holds the same secret. The files received go through the child abuse filter and the fake
  file detector and are indexed as search results. `/api/relay/files` is the only part of the admin panel open to
  them; `relayToken` is read from `server.properties` (or `JEMULE_RELAY_TOKEN`) only, and never shown.
  The Overview has a "Files sent to the relay server" tile on the relay, and a "Files from relay servers" tile on the
  relay server.

### Changes

- The relay of 1.2.29 only took over once the index was full; a configured `relayServer` now means relay mode at
  once, whatever the local index.
