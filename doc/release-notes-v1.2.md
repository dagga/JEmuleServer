## JEmuleServer 1.2

### New features

- **Kademlia search bridge**: the server embeds a Kad node. Each client search (`OP_SEARCHREQUEST`) is answered from
  the local index and also sent to the Kad network, so clients find files no connected client shares. See the
  [README](https://github.com/dagga/JEmuleServer#kademlia-search-bridge) and
  [doc/kademlia.md](https://github.com/dagga/JEmuleServer/blob/main/doc/kademlia.md).
  - **Bootstrap from the clients**: when a HighID client logs in, the node asks its Kad ports (`listenPort + 10`,
    `4672`) for contacts; no static node list is needed. The node ID is kept in the database across restarts.
  - **Filtering**: every Kad file goes through the banned hashes, the child abuse filter and the fake file detector.
    An illicit file is never sent to a client and its hash is banned for good (`banned_hashes`).
  - **Cache warming**: clean Kad files with at least one source are stored in the database and indexed. Later
    searches on the same terms are answered at once from the local index, in the first batch of results.
  - **No added latency**: the client waits for Kad (at most `kadSearchTimeoutSeconds`) only when nothing matches
    locally; the files found are then paginated by 50 with the "More" button. A server that cannot reach Kad answers
    as fast as without it.
  - The server never publishes its files on Kad, answers no request from other Kad nodes and never relays file data.
- **Kademlia settings** in `server.properties`: `kadEnabled`, `kadPort` (default: TCP port + 11, i.e. `4672`),
  `kadBootstrapFromClients`, `kadMaxRoutingContacts`, `kadForkSearchEnabled`, `kadSearchTimeoutSeconds`,
  `kadAutoIngestToH2`, `kadMinSourcesForIngest`.
- **`kad` admin commands**: `kad status`, `kad contacts`, `kad bootstrap <ip> <port>` and `kad stats`, on the console
  and on the admin port.
- **Child abuse filter**: the keyword list is extended with the markers seen in production (studio and site names,
  Spanish and Russian terms...), and ages up to 18 are now treated as minors (`maxMinorAge = 18`).
- Name shown by clients: `JEmuleServer v1.2 (no pedo)`, description `eMule Server - version 1.2`.

### Performance

Measured with 1,000,000 files in the database (`./gradlew benchmark`), one vCPU and a 768 MB heap:

- **Searches use the inverted index**: a search used to test every file, 315 ms of CPU for a rare word; it now
  takes 0.3 to 7 ms, with the same results.
- **Memory**: the index takes about 470 MB instead of 800 MB (compact word lists, one shared source map for the files
  without source, shared strings), so a 1 GB server can hold 1,000,000 files.
- **Startup**: the files are read in one query instead of one per file, and indexed as they are read.
- **Kad answers** are only accepted from the contacts the search was sent to: unsolicited answers could fill the
  database.
- The systemd unit lets the JVM use 75 % of the RAM (`-XX:MaxRAMPercentage=75`) instead of 25 %.

### Compatibility

The changes of 1.2 remain compatible with the historical eMule clients (eMule 0.4x / 0.50a, aMule, eDonkey-based
clients): the ed2k client-server protocol is unchanged, and the files found on Kad reach the clients as ordinary
`OP_SEARCHRESULT` answers, paginated with the usual "More" button (`OP_QUERY_MORE_RESULT`). The only visible effect is
that a search matching nothing locally may take up to `kadSearchTimeoutSeconds` (4 s) to be answered. Clients do not
need Kad support: the server talks to the Kad network on their behalf.

### Known limitation

- The Kad packets follow the framing of the 1.2 specification (protocol byte `0x3A`), which differs from the Kad2
  framing of eMule and aMule (`0xE4`). Exchanges with real Kad nodes have not been validated yet: in practice the
  routing table may stay empty and the searches are then answered from the local index only.
  To turn the module off, set `kadEnabled=false`.

### Downloads

| File | Use |
|---|---|
| `jemuleserver_1.2_amd64.deb` | Debian / Ubuntu (Java included) |
| `jemuleserver-1.2-1.x86_64.rpm` | Fedora / RHEL / openSUSE (Java included) |
| `JEmuleServer-1.2.exe` | Windows installer (Java included) |
| `JEmuleServer-1.2.jar` | Any platform with Java 21 or later: `java -jar JEmuleServer-1.2.jar [server.properties] [port]` |
| `jemuleserver.service` | systemd unit for the Linux packages |
| `SHA256SUMS` | Checksums |

### Upgrading from 1.1

No database schema change: stop the server, back up the database (`jemule_db.mv.db`), replace the JAR or install the
new package, then start it again. Check before restarting:

- **Kademlia is on by default**, even with an existing `server.properties` that has no `kad*` key. Open UDP port
  `4672` (TCP port + 11) in the firewall, or set `kadEnabled=false` to keep the 1.1 behaviour.
- **The first start deletes** the files of the database matching the extended child abuse filter, and logs each of
  them (`Child abuse file banned: hash=... name=...`).
