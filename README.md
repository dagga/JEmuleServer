# JEmuleServer

JEmuleServer is an eMule server written in Java (version 1.2.26).

Downloads (installers and JAR) are on the [releases page](https://github.com/dagga/JEmuleServer/releases); the changes
of each version are described in `doc/release-notes-v<version>.md` (latest: [1.2.9](doc/release-notes-v1.2.9.md)).

## Why this project?

JEmuleServer aims to provide a modern, open-source (LGPLv3) eMule server using current Java features such as Virtual
Threads. The historical **Lugdunum/eServer** remains a point of comparison. The protocol references included in this
repository describe several of its historical extensions; the [comparison notes](doc/feature-comparison.md) separate
those documented attributions from features that cannot be verified.

Fighting the distribution of child sexual abuse material is also one of the goals of the project: a server must not
help anyone find it. JEmuleServer refuses such files, bans their hashes for good and removes them from its database,
with a filter that cannot be disabled (see [Child Abuse Filter](#child-abuse-filter)).

Since version 1.2, JEmuleServer embeds a Kademlia node: the searches of the ed2k clients also reach the Kad network,
whose content is filtered by the server before any client sees it (see [Kademlia Search Bridge](#kademlia-search-bridge)).

## Compilation

Building requires a JDK 21 (Gradle toolchain). To compile the project and generate the executable JAR file (Fat JAR):

```bash
./gradlew build
```

The JAR file will be generated in `build/libs/JEmuleServer-1.2.26.jar`.

## Installation from the release packages

Each release on GitHub provides installers bundling their own Java runtime:
`jemuleserver_<version>_amd64.deb` (Debian/Ubuntu), `jemuleserver-<version>-1.x86_64.rpm` (Fedora/RHEL),
`JEmuleServer-<version>.exe` (Windows) and `JEmuleServer-<version>-macos-arm64.dmg` (macOS on Apple Silicon), plus the
plain JAR and a systemd unit (`packaging/jemuleserver.service`).

- The application is installed in `/opt/jemuleserver` (Linux), `Program Files\JEmuleServer` (Windows) or
  `/Applications/JEmuleServer.app` (macOS, by dragging it from the disk image).
- Started without arguments, the installed program keeps its configuration (`server.properties`, created on first
  start) and its H2 database in `~/.local/share/jemuleserver` (Linux), `%LOCALAPPDATA%\JEmuleServer` (Windows) or
  `~/Library/Application Support/JEmuleServer` (macOS).
- On macOS, the server is a console program: start it from a terminal with
  `/Applications/JEmuleServer.app/Contents/MacOS/JEmuleServer`. The package is not signed: after copying the
  application, run `xattr -dr com.apple.quarantine /Applications/JEmuleServer.app` once, or macOS refuses to open it.
  Intel Macs can run the JAR with Java 21 or later.
- Started with a configuration file argument (as the systemd unit does), relative paths are relative to the
  working directory.

The packages are built by `.github/workflows/release.yml` and published automatically when a `v*` tag is pushed.

## Running

### Run Mode (via Gradle)

You can start the server directly via Gradle:

```bash
./gradlew run
```

This task starts the JVM with `-Djava.net.preferIPv4Stack=true` (IPv4 only) and `-XX:ThreadPriorityPolicy=1`; use
the JAR for IPv6.

### Manual JAR Launch

Once the project is compiled, you can start the server with the following command:

```bash
java -XX:MaxRAMPercentage=75 -XX:ThreadPriorityPolicy=1 --add-modules=jdk.httpserver -jar build/libs/JEmuleServer-1.2.26.jar [config_file] [port]
```

- `-XX:ThreadPriorityPolicy=1`: on Linux, the JVM ignores thread priorities without it. The server runs its threads
  in five tiers, highest first: admin interfaces, client connections, searches, ingestion (indexing and database
  writes), regular operations; ingestion and regular operations never hold back a client or a search. Lowering a
  priority works for any user; raising the admin tier above the clients needs root or `CAP_SYS_NICE`. The startup log
  says whether the priorities are applied. The .deb and .rpm launchers already pass the option.
- `-XX:MaxRAMPercentage=75`: lets the heap grow to 75% of the RAM instead of 25%; the .deb and .rpm launchers
  already pass it. On a 1 GB server, prefer the explicit heap options of the reference server (see Memory sizing
  below).

JVM options used by the server:

| Option | Effect | When |
|---|---|---|
| `--add-modules=jdk.httpserver` | Loads the HTTP server module of the admin web panel and its API. | Always (the launchers pass it). |
| `-XX:ThreadPriorityPolicy=1` | Applies the thread priority tiers on Linux. | Always on Linux (the launchers pass it). |
| `-XX:MaxRAMPercentage=75` | Heap up to 75% of the RAM instead of 25%. | Dedicated server with enough RAM; the .deb and .rpm launchers pass it. |
| `-Xmx<size>` | Maximum heap, replaces `MaxRAMPercentage`. | Small server: sized from `maxFiles` (see Memory sizing). |
| `-Xmn48m` | Young generation of 48 MB instead of a third of the heap. | With `-Xmx` on a small server. |
| `-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30` | After a full collection, the JVM gives back to the system the heap beyond 30% above what is in use, and grows it again when needed. | Small server whose RAM is short. |
| `-Djava.net.preferIPv4Stack=true` | Do not use: it disables the IPv6 listener. | Never. |

- Note: The server binds to the IPv6 wildcard when available, so IPv6 clients can connect. Avoid forcing the JVM to
  prefer IPv4 (i.e. do not use `-Djava.net.preferIPv4Stack=true`) if you expect IPv6 connectivity. The ed2k protocol
  only carries IPv4 addresses: IPv6 clients get a LowID and cannot request callbacks (see [IPv6](doc/ipv6.md)).
- `[config_file]`: Optional, path to a custom configuration file (defaults to `server.properties` if present).
- `[port]`: Optional, overrides the port defined in the configuration.

### Memory sizing

Every known file is kept in memory for the searches: its MD4 hash, name, size, type, source count and the media tags
searches filter on, plus its entries in the inverted index of the name words. The files are stored by number in
arrays of primitive values (the MD4 hash as two 64-bit values) rather than as one object each; only the files with
online sources or media tags get an object of their own; the names follow each other in one byte array and the
inverted index keeps its file numbers as variable-length gaps. This comes to about 270 bytes of heap per file since
1.2.24 (350 before). Only the tags searches filter on are kept (source count, artist, album, title, length,
bitrate, codec, rating).

#### Reference server on 1 GB of RAM

The production server runs with this calibration:

| | |
|---|---|
| CPU | 1 vCPU, AMD EPYC at 2.1 GHz (KVM virtual machine, model and boost hidden by the hypervisor) |
| Memory | 947 MB of RAM, 512 MB swap file |
| Disk | 9.8 GB |
| System | Fedora Linux 43 Server, OpenJDK 25 |
| Launch | `java --add-modules=jdk.httpserver -Xmx600m -Xmn48m -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -XX:ThreadPriorityPolicy=1 -jar JEmuleServer-<version>.jar server.properties` |
| `server.properties` | `maxFiles=3000000` (the heap guard stops the index first, see below) |

Measured with 1.2.19, 706,165 files and 196,532 banned hashes:

| | |
|---|---|
| Live heap after a full collection | 252 MB (about 360 bytes per file) |
| Java process | about 490 MB resident |
| Database file (`jemule_db.mv.db`) | 168 MB (about 240 bytes per file) |
| Startup (database load) | about 3 minutes |

Measured with 1.2.22, 825,542 files and 232,355 banned hashes, after a full collection:

| | |
|---|---|
| Live heap | 293 MB (about 350 bytes per file) |
| Old generation kept by the JVM | 419 MB with the heap free ratios, 503 MB without them |
| Java process | 549 MB resident with the heap free ratios, 616 MB without them |
| Database file (`jemule_db.mv.db`) | 250 MB |
| Startup (database load) | about 4 minutes 30 |

Measured with 1.2.23 at 1,521,140 files (Kad and server searches then ignored `maxFiles`): 528 MB of live heap, the old
generation 95% full, with long full collections. Loaded by 1.2.24 from the database copy of 1.2.22 (828,413 files),
the index takes 224 MB of heap, 24% less than the 293 MB above.

The same server needed 422 MB of live heap for 686,506 files with 1.2.16 (about 615 bytes per file) and swapped:
1.2.17 shares and compacts the banned hashes, 1.2.18 stores the files in arrays, 1.2.19 shrinks the H2 cache.

Why these settings:

- `-Xmn48m`: on one CPU the JVM uses the serial collector, which gives a third of the heap to the young generation
  (218 MB out of 688 MB). That memory stays committed although it is almost empty after each collection; 48 MB is
  enough for the allocation rate of the server and leaves the rest to the old generation, where the index lives.
- `-Xmx600m`: 552 MB of old generation for about 430 MB of index at 1,200,000 files and the room the index arrays
  keep to grow, with about 100 MB of margin for the connections, the searches and the garbage of the startup load. The
  process then takes at most about 700 MB and the system and kernel about 150 MB: on 947 MB of RAM, the swap file
  takes the cold pages. `./gradlew benchmark -Pfiles=1200000 -Pheap=600m -Pcpus=1` loads 1,200,000 files in this
  heap. For 1,000,000 files, `-Xmx520m` is enough.
- `-XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30`: the serial collector keeps the old generation at the largest size
  it ever reached, i.e. after the garbage of the startup load. With these ratios, each full collection shrinks it to
  the live heap plus 30% (503 MB to 419 MB at 825,542 files) and gives about 70 MB back to the system; the heap still
  grows up to `-Xmx` as the index grows. Full collections are rare with a small young generation, so the gain shows
  after the first one.
- `maxFiles=3000000`: bounds the index. Beyond it, new files are refused, whatever brings them (clients, Kad, other
  servers); files already known still get their new sources. It applies at once from the Settings view of the web
  panel, without a restart.
- **Heap guard** (always on): new files are also refused while the live data, measured after the last collection of
  the old generation, fills 80% of it: 441 MB of the 552 MB here, which 1.2.24 reaches at about 1,600,000 files. The
  index then stops growing instead of running out of memory; the Overview's index capacity shows the fuller of the two
  limits and the files refused. More RAM and a larger `-Xmx` move the guard, not `maxFiles`.

To size another server: old generation (`-Xmx` minus `-Xmn`) of at least the files wanted × 340 bytes plus 60 MB (the
heap guard leaves 20% of it free), and RAM
of at least `-Xmx` plus 250 MB. The Overview of the web panel shows the heap against its maximum and the index against
`maxFiles`. Without these options the JVM takes a quarter of the RAM; on a dedicated server
`-XX:MaxRAMPercentage=75` (passed by the .deb and .rpm launchers) is a simpler alternative, which leaves a larger
young generation.

The startup log gives the maximum heap and warns when its old generation is too small for the indexed files (about
250 bytes of live heap per file, which the heap guard lets fill 80% of it), with the number of files the guard will
let in; the Status view of the web panel shows `heapUsedMb` and `heapMaxMb`. An OutOfMemoryError makes H2
close the database: the server then reopens it at the next database operation and logs it, so that writes resume
without a restart (`databaseReopens` in the Status view), but the heap must be enlarged.

`./gradlew benchmark -Pfiles=1000000 -Pheap=768m -Pcpus=1` fills a database with the given number of files, then
reports the startup time, the heap used by the index and the search latency under these limits.

#### Disk

The database takes about 240 bytes per file. Keep free at least three times its size: a migration
or a compaction writes the file again before releasing the old one, and `deploy.sh`-style upgrades keep a copy of it.
The system logs (journald) also grow on a small disk: `journalctl --vacuum-size=50M` bounds them.

A database written by version 1.2.14 or older is migrated at the first start: the source counts move to the
`file_sources` table, the other tags are deleted, then the file is compacted. The progress is logged on the console
(about 75 seconds for 3.3 million tag rows on the reference server); a migration interrupted halfway starts again at
the next start.

H2 does not compact the file while the startup load reads it (a compaction running during that load made the file
grow until the disk was full): the file is compacted at startup, before it is read, when less than 80% of it is used.
Once the load is over, the server checks every 10 minutes and compacts the file again when it is larger than 64 MB and
less than 50% of it is used, the same way: the database is closed for the few seconds of the rewrite, the writes wait
meanwhile, and the sizes are logged. Without it, the writes of a running server made the file grow from 200 MB to
1.4 GB in 15 hours; H2's own background compaction neither prevents that growth nor shrinks the file.

## Configuration

JEmuleServer uses `server.properties`; if it is not found, internal defaults are used. The root
[`server.properties`](server.properties) file is the complete, commented reference for all options. See the
[configuration guide](doc/configuration.md) for option groups and administration security notes.

### IP filter updates

Set `ipFilterPath` to enable the IPv4 filter and its automatic daily refresh from the HTTPS source bundled with the
server. A downloaded filter is installed only when fewer than 10% of its normalized blocked ranges differ from the
current filter. Larger changes and invalid downloads leave the current filter untouched and are recorded in the
adjacent `<ipFilterPath>.trace` file. If no filter path is configured, automatic updates are disabled.

## Child Abuse Filter

The server refuses files whose name advertises child sexual abuse material, and announces it in its name
(`JEmuleServer vX (no pedo)`). A file is rejected if its name contains a term specific to such content, or a term
designating a minor (or an age up to 18, such as `6yo` or `9 ans`) together with a sexual term. A rejected file:

- is never indexed, and the publishing client is warned that some of its files were refused;
- is never sent to a client, whatever brought it: the local search skips it (even indexed before the keywords were
  reloaded) and every batch of search results is checked once more before it leaves;
- is refused again each time it comes back, from a client, Kad or another server (1.2.23 and before also banned its
  hash; 1.2.24 bans file names only, see [Banned file names](#banned-file-names));
- is deleted from the database at startup if an older version had stored it.

A search whose terms match the filter (client search, or UDP global search from another server's client) gets no
result and is sent neither to Kad nor to the other eD2K servers; the Overview counts these searches. The names of the
files the filter rejects are recorded in `banned_names` (see [Banned file names](#banned-file-names)), never sent to a
client.

Unlike `fakeFileDetectionEnabled`, this filter cannot be disabled. The keywords are read from
[`child-abuse-keywords.properties`](src/main/resources/child-abuse-keywords.properties), embedded in the jar; the
file documents its syntax. To use your own list, copy it and set its path in `server.properties`:

```properties
childAbuseKeywordsPath=/etc/jemuleserver/child-abuse-keywords.properties
```

Your file replaces the embedded list. If it cannot be read, the server logs an error and uses the embedded list.

## Banned file names

The administrator bans file names, not hashes: a file published by a client, or found on Kad or another server, under
a banned name is refused, whatever its hash and even with `fakeFileDetectionEnabled=false`. Names are compared in
lower case, without the spaces around them. Banning a name removes the files indexed under it.

The names are kept in the `banned_names` table with their reason and date. The **Blocks** view of the web panel lists
the latest ones, bans and unbans a name, and its **Export** button downloads `banned-names.txt`, one name per line
(`GET /api/blocks/export`). The CLI has `ban`, `unban` and `blocks`.

Up to 1.2.23 the server banned hashes (`banned_hashes`), nearly all written by the child abuse filter. 1.2.24 drops
that table at its first start (database schema version 3): its hashes cannot be turned into names, the banned files
having been deleted, and the child abuse filter rejects the same names each time they come back.

## HighID / LowID

At login, the server connects back to the TCP port announced by the client and sends an `OP_HELLO`. The client gets
a **HighID** (its IP address) only if it answers with an `OP_HELLOANSWER` carrying the user hash of its login
request; otherwise it gets a **LowID** and is reached through callbacks (`OP_CALLBACKREQUEST`). Checking the user
hash matters when several clients share the same public IP (NAT): the announced port may be forwarded to another
client, which must not obtain the HighID in its place.

The server therefore needs to open outgoing TCP connections to the clients.

## Kademlia Search Bridge

The Kad network holds far more files than a single server, but nothing moderates it. JEmuleServer embeds a Kad node
that brings this content to its clients without exposing them to illicit files:

- **Bootstrap from a nodes.dat list and the clients**: at startup the node downloads a contact list from the
  `kadNodesUrls` sources (refreshed daily, cached next to the database) and sends bootstrap requests to its nodes.
  When a HighID client logs in, the node also asks its Kad ports (`listenPort + 10` and `4672`) for contacts.
  `kad bootstrap <ip> <port>` adds a node by hand.
- **Forked search**: each `OP_SEARCHREQUEST` is answered from the local index and sent at the same time to the 8 Kad
  nodes closest to its longest required word (`KADEMLIA2_SEARCH_KEY_REQ`, keyword hashed with MD4), with the whole
  query so that the nodes only return matching files. A node that returns a full page of 300 files is asked for the
  next ones, up to 900.
- **Flood limits**: the server never sends a Kad node more requests than the node accepts per minute from one IP, so
  that the nodes neither drop its requests nor ban it.
- **Filtering**: every file returned by Kad goes through the [child abuse filter](#child-abuse-filter), the banned
  file names and the fake file detector. An illicit file is never sent to a client nor stored.
- **Cache warming**: clean Kad files with at least one source are stored in the H2 database and indexed. The next
  searches on the same terms find them locally, in the first batch of results.
- **Global searches**: the UDP global searches that clients of other servers send to this server are answered from the
  local index and, with `kadForkGlobalSearches=true`, also searched on Kad in the background to populate the
  database: once per query per 30 minutes, at most 20 per minute (3 per source IP), never for queries matching the
  child abuse filter, and never forwarded to other eD2K servers, which could send them back.
- **Kad and server sources** (`kadSourceSearchEnabled`, opt-in): when a client asks for the sources of a file
  (`OP_GETSOURCES`) and the server knows fewer than `kadSourceSearchMinLocal` (10), it answers at once with them, then
  searches Kad for the file (`KADEMLIA2_SEARCH_SOURCE_REQ` to the nodes closest to its hash) and asks the verified eD2K
  servers (`OP_GLOBGETSOURCES2`, as eMule clients do), and sends the new sources in a second `OP_FOUNDSOURCES`, which
  eMule and aMule accept at any time for a file they download. A Kad source that accepts connections is sent with its
  IP as HighID; a firewalled one is useless to an eD2K client unless it is also connected to this server: it is then
  sent with its ID here, LowID included, and reached through this server's callback. Of the other servers' sources,
  only the HighIDs are kept. The files asked within 3 seconds go in one datagram per server (35 at most). The sources
  found for a file are reused for 20 minutes (1,000 files, 80 sources each at most); a client starts at most one
  search per file every 15 minutes and 20 an hour.
- **Adaptive quota**: all clients together start at most `kadSourceSearchesPerMinute` (60) searches a minute. When
  the flood limits keep a source request from a Kad node, the quota is halved (once a minute at most, never below 5);
  after 10 minutes without such a refusal it climbs back by a tenth of the maximum.

The client waits for Kad only when nothing matches locally (cold search), at most `kadSearchTimeoutSeconds`; the files
found are then paginated by 300 with the "More" button (`OP_QUERY_MORE_RESULT`), up to the 1,800 files an eMule
client accepts. When local results exist, they are sent at once and the Kad files follow with the "More" button. A
server that cannot reach Kad answers as fast as without it.

Searches are also forked to the verified eD2K servers (UDP global search). A server answers it with its first 10 to
30 matches only, and UDP has no "More" request: when a server returns a full answer, the search is repeated on it once
per file type (at most 4 times per minute and per server), which returns about five times as many files.

The node listens on UDP port `kadPort` (default: TCP port + 11, i.e. `4672`). Its settings, with their defaults:

```properties
kadEnabled=true
# kadPort=4672
kadBootstrapFromClients=true
kadMaxRoutingContacts=500
kadForkSearchEnabled=true
kadSearchTimeoutSeconds=4
kadAutoIngestToH2=true
kadMinSourcesForIngest=1
kadNodesUrls=https://upd.emule-security.org/nodes.dat,http://www.alldivx.de/nodes/nodes.dat
kadForkGlobalSearches=true
kadSourceSearchEnabled=false
kadSourceSearchMinLocal=10
kadSourceSearchesPerMinute=60
```

The bridge stays compatible with the historical eMule clients: the ed2k client-server protocol is unchanged and the
Kad files reach them as ordinary search results (`OP_SEARCHRESULT`), so clients without Kad support benefit from it
too; the Kad sources reach them as ordinary server sources (`OP_FOUNDSOURCES`). The server never publishes its files on
Kad, answers no request from other Kad nodes and never relays file data.
The packet formats, flows and tests are described in [Kademlia](doc/kademlia.md); interoperability with the eMule
and aMule Kad nodes has not been validated yet.

## Administration Interface

JEmuleServer includes a built-in web panel and a command-line interface (CLI). The web panel is enabled by default at
`http://127.0.0.1:8080/` and is bound to loopback only.

It opens on an **Overview**: the number of indexed files with its change over 24 hours and 7 days, the index capacity
against `maxFiles` and the heap guard, and tiles for the connected clients (with their LowID share), the heap, the verified eD2K servers,
the searches sent to Kad and to the servers, the files they found, the new files published by [OFFERFILES
v1](doc/offerfiles-v1.md) clients next to those of legacy clients, the Kad and server sources sent to the clients with
the adaptive quota, the banned file names, the database (size, and whether it had to be reopened after a fatal error) and the uptime; a meter or a status
(icon and label) flags what nears its limit. Two curves follow, over 24 hours, 7 days or 30 days: the indexed files
and the connected clients, sampled every 5 minutes and kept for a month in the `metrics_history` table, so that they
survive a restart. Hovering a curve, or the arrow keys once it has the focus, reads the samples; each curve can also
be shown as a table of daily values. The figures refresh every 30 seconds, the curves every 5 minutes. The page
follows the light or dark theme of the system.

The other views show server status, searchable clients, verified named
server peers, a monitoring view of every known eD2K server and its state, active filters, potential bot-name matches,
the banned file names, an ingestion breakdown of new database files by channel, Kad and eD2K server search counters
(queries sent and results returned), and runtime settings. The bot list is
only a nickname heuristic; it does not verify or automatically block clients. From the panel you can disconnect a
client, ban or unban a file name, export the banned names, reload the IP filter, and edit any `server.properties` parameter. Settings whose scope is
`hot` (limits, timeouts, flood protection, fake-file detection) take effect immediately; `admin` settings rebuild the
admin interfaces; `restart` settings are written to the file and shown as pending until the next restart, the running server keeps
its current value. Saving updates the
file in place (comments and key order are preserved) and **Reload from file** applies external edits without a restart.
Nothing is applied when a value is invalid.

The web panel has no password: any local user who can reach loopback can administer the server. Do not expose it through
a reverse proxy, tunnel, or port forward. Set `adminWebPort=0` in `server.properties` to disable it, or change the port
with `adminWebPort=<port>`.

When running interactively, CLI commands can be typed into the server terminal. For a service installation without a
terminal, set `adminPort` in `server.properties` to expose the same CLI on a TCP port bound to `127.0.0.1`, then connect
from the server machine:

```bash
nc 127.0.0.1 4670
```

The admin port has no authentication: anyone with a shell on the machine can use it. It is disabled by default
(`adminPort=0`). In such a session, `exit` and `quit` close the session; `stop` still shuts the server down.

Available commands:
- `status`: Displays server performance, uptime, and core statistics.
- `clients [text]`: Lists connected clients or searches by nickname, IP, or client ID.
- `files`: Shows file indexing statistics and fake file detection status.
- `events`: Shows the number of events of each type (connections, logins, publications, searches...) since startup
  and during the last minute.
- `ban <file name>`: Bans a file name for good, whatever its case and hash (see [Banned file names](#banned-file-names));
  the files indexed under it are removed.
- `unban <file name>`: Removes a banned file name.
- `kick <client ID | nickname | ip>`: Disconnects one client, or every client using that IP, without stopping the server.
- `peers`: Lists verified server peers and their names.
- `servers`: Monitors every known eD2K server (configured, server.met, client-reported or gossip-discovered) with its
  state (candidate, probing, verified, rejected), source, reported users/files/limits, probe latency and missed probes.
- `ingestion`: Shows the number and share of new database files by ingestion channel (client push, Kad search,
  eD2K server search) since the server started.
- `searches`: Shows, for Kad and eD2K servers, the search queries forked, requests sent, results returned, files
  ingested and active searches since startup, and the UDP global searches received, searched on Kad, skipped and
  refused.
- `filters`: Shows active IP and fake-file filters.
- `filters reload`: Reloads the IP filter from the configured `ipFilterPath` and the child abuse keywords.
- `bots`: Lists potential bot matches based on nicknames only.
- `blocks`: Lists the number of banned file names and the 10 most recent.
- `settings`: Shows runtime settings.
- `settings <key> <value>`: Changes a configuration parameter while the server keeps running. Hot values take effect
  immediately, admin values rebuild the admin interfaces, and restart values are written to the file and listed as
  "after restart" until the server is restarted. For example: `settings maxUsers 200000`.
- `settings reload`: Reloads `server.properties` from disk and applies its changes without restarting.
- `settings fakeFileDetectionEnabled <on|off>`: Toggles fake-file detection (persisted to the configuration file).
- `kad status`: Shows the status of the embedded Kademlia node (`DISCONNECTED`, `BOOTSTRAPPING`, `CONNECTED`), its
  node ID, UDP port and number of contacts (`kad` alone does the same).
- `kad contacts`: Lists the 20 most recently seen Kademlia contacts (ID, address, version, liveness).
- `kad bootstrap <ip> <port>`: Sends a bootstrap request to a given Kademlia node.
- `kad stats`: Shows the Kademlia counters (searches forked, results received, files ingested, illicit files blocked,
  bootstrap and maintenance activity).
- `help`: Shows the list of available commands.
- `stop`: Shuts down the server. `exit` and `quit` close a remote admin session; in the server terminal they also stop
  the server.

## Firewall Configuration (Fedora)

To allow clients to connect to your server, you must ensure your firewall is running and open the ports: **TCP 4661**
and **UDP 4661, 4665, 4672 and 4673** (with the default port 4661: UDP on port + 4, Kademlia on port + 11,
obfuscated UDP on port + 12). On Fedora, use the following commands:

### 1. Start and enable firewalld
```bash
sudo systemctl start firewalld
sudo systemctl enable firewalld
```

### 2. Open ports
```bash
sudo firewall-cmd --add-port=4661/tcp --permanent
sudo firewall-cmd --add-port=4661/udp --permanent
sudo firewall-cmd --add-port=4665/udp --permanent
sudo firewall-cmd --add-port=4672/udp --permanent
sudo firewall-cmd --add-port=4673/udp --permanent
sudo firewall-cmd --reload
```

### 3. Verify configuration
```bash
sudo firewall-cmd --list-ports
```

*Note: Don't forget to also configure Port Forwarding on your router/internet box if necessary.*

## Features Comparison

The [comparison notes](doc/feature-comparison.md) summarize Lugdunum features attributed by the protocol references in
this repository, alongside the modern JEmuleServer features. They distinguish historically documented protocol
extensions from release-specific capabilities that cannot be verified.

## License

This project is distributed under the **GNU Lesser General Public License v3.0 (LGPLv3)**. See the `LICENSE.LGPL` and
`LICENSE.GPL` files for more details.

## Author

**Nicolas Hernandez** (hernicatgmail.com)

## Documentation

More information can be found in the `doc/` directory:
- [Configuration guide](doc/configuration.md)
- [JEmuleServer and Lugdunum feature comparison](doc/feature-comparison.md)
- [Release notes 1.2.6](doc/release-notes-v1.2.6.md) / [1.2.5](doc/release-notes-v1.2.5.md) / [1.2.3](doc/release-notes-v1.2.3.md) / [1.2.2](doc/release-notes-v1.2.2.md) / [1.2.1](doc/release-notes-v1.2.1.md) / [1.2](doc/release-notes-v1.2.md) / [1.1](doc/release-notes-v1.1.md) / [1.0.1](doc/release-notes-v1.0.1.md) / [1.0](doc/release-notes-v1.0.md)
- [Protocols Description](doc/protocols.md)
- [Kademlia Search Bridge](doc/kademlia.md)
- [OFFERFILES v1: negotiated fast publishing](doc/offerfiles-v1.md)
- [Technical References](doc/references.md)
- [Class Diagram](doc/class_diagram.puml)
- [Connection Sequence Diagram](doc/sequence_diagram.puml)
- [Kademlia Forked Search Sequence Diagram](doc/kad_search_sequence.puml)
- [Credits](doc/credits.md)