# JEmuleServer

JEmuleServer is an eMule server written in Java (version 1.2).

Downloads (installers and JAR) are on the [releases page](https://github.com/dagga/JEmuleServer/releases); the changes
of each version are described in `doc/release-notes-v<version>.md` (latest: [1.2](doc/release-notes-v1.2.md)).

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

The JAR file will be generated in `build/libs/JEmuleServer-1.2.jar`.

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

This task starts the JVM with `-Djava.net.preferIPv4Stack=true` (IPv4 only); use the JAR for IPv6.

### Manual JAR Launch

Once the project is compiled, you can start the server with the following command:

```bash
java -jar build/libs/JEmuleServer-1.2.jar [config_file] [port]
```

- Note: The server binds to the IPv6 wildcard when available, so IPv6 clients can connect. Avoid forcing the JVM to
  prefer IPv4 (i.e. do not use `-Djava.net.preferIPv4Stack=true`) if you expect IPv6 connectivity. The ed2k protocol
  only carries IPv4 addresses: IPv6 clients get a LowID and cannot request callbacks (see [IPv6](doc/ipv6.md)).
- `[config_file]`: Optional, path to a custom configuration file (defaults to `server.properties` if present).
- `[port]`: Optional, overrides the port defined in the configuration.

### Memory sizing

Every known file is kept in memory for the searches: plan about 360 MB of index heap per 1,000,000 files (363 MB for
999,913 files in the latest `./gradlew benchmark -Pfiles=1000000 -Pheap=768m -Pcpus=1` run, see below). The JVM only
takes a quarter of the RAM by default; on a small dedicated server, let it use more, e.g.
`java -XX:MaxRAMPercentage=75 -jar ...` (the systemd unit of the packages already does). With 1,000,000 files, a 1 GB
server with one vCPU starts in about 30 s and answers searches in a few milliseconds.

`./gradlew benchmark -Pfiles=1000000 -Pheap=768m -Pcpus=1` fills a database with the given number of files, then
reports the startup time, the heap used by the index and the search latency under these limits.

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
- has its hash banned for good (table `banned_hashes`), so it stays refused under any other name;
- is deleted from the database at startup if an older version had stored it. Every deleted or banned file is
  logged with its hash and name.

Unlike `fakeFileDetectionEnabled`, this filter cannot be disabled. The keywords are read from
[`child-abuse-keywords.properties`](src/main/resources/child-abuse-keywords.properties), embedded in the jar; the
file documents its syntax. To use your own list, copy it and set its path in `server.properties`:

```properties
childAbuseKeywordsPath=/etc/jemuleserver/child-abuse-keywords.properties
```

Your file replaces the embedded list. If it cannot be read, the server logs an error and uses the embedded list.

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

- **Bootstrap from the clients**: when a HighID client logs in, the node asks its Kad ports (`listenPort + 10` and
  `4672`) for contacts. No static node list is needed; `kad bootstrap <ip> <port>` adds a node by hand.
- **Forked search**: each `OP_SEARCHREQUEST` is answered from the local index and sent at the same time to the 3
  closest Kad contacts (`KADEMLIA2_SEARCH_KEY_REQ`, keyword hashed with MD4).
- **Filtering**: every file returned by Kad goes through the banned hashes, the [child abuse filter](#child-abuse-filter)
  and the fake file detector. An illicit file is never sent to a client and its hash is banned for good.
- **Cache warming**: clean Kad files with at least one source are stored in the H2 database and indexed. The next
  searches on the same terms find them locally, in the first batch of results.

The client waits for Kad only when nothing matches locally (cold search), at most `kadSearchTimeoutSeconds`; the files
found are then paginated by 50 with the "More" button (`OP_QUERY_MORE_RESULT`). When local results exist, they are
sent at once and the Kad search keeps enriching the database in the background. A server that cannot reach Kad
answers as fast as without it.

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
```

The bridge stays compatible with the historical eMule clients: the ed2k client-server protocol is unchanged and the
Kad files reach them as ordinary search results (`OP_SEARCHRESULT`), so clients without Kad support benefit from it
too. The server never publishes its files on Kad, answers no request from other Kad nodes and never relays file data.
The packet formats, flows and tests are described in [Kademlia](doc/kademlia.md); interoperability with the eMule
and aMule Kad nodes has not been validated yet.

## Administration Interface

JEmuleServer includes a built-in web panel and a command-line interface (CLI). The web panel is enabled by default at
`http://127.0.0.1:8080/` and is bound to loopback only. It shows server status, searchable clients, verified named
server peers, active filters, potential bot-name matches, persistent hash blocks, and runtime settings. The bot list is
only a nickname heuristic; it does not verify or automatically block clients. The settings action only changes fake-file
detection in memory; it does not update `server.properties`.

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
- `ban <hash> [reason]`: Bans a file hash (32-char hex) for good: it is stored in the database with its reason and the
  file is removed from the index.
- `peers`: Lists verified server peers and their names.
- `filters`: Shows active IP and fake-file filters.
- `bots`: Lists potential bot matches based on nicknames only.
- `blocks`: Lists persistently blocked file hashes.
- `settings [fakeFileDetectionEnabled <on|off>]`: Shows runtime settings or toggles fake-file detection (not persisted).
- `kad status`: Shows the status of the embedded Kademlia node (`DISCONNECTED`, `BOOTSTRAPPING`, `CONNECTED`), its
  node ID, UDP port and number of contacts (`kad` alone does the same).
- `kad contacts`: Lists the 20 most recently seen Kademlia contacts (ID, address, version, liveness).
- `kad bootstrap <ip> <port>`: Sends a bootstrap request to a given Kademlia node.
- `kad stats`: Shows the Kademlia counters (searches forked, results received, files ingested, illicit files blocked,
  hashes banned, bootstrap and maintenance activity).
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
- [Release notes 1.2](doc/release-notes-v1.2.md) / [1.1](doc/release-notes-v1.1.md) / [1.0.1](doc/release-notes-v1.0.1.md) / [1.0](doc/release-notes-v1.0.md)
- [Protocols Description](doc/protocols.md)
- [Kademlia Search Bridge](doc/kademlia.md)
- [Technical References](doc/references.md)
- [Class Diagram](doc/class_diagram.puml)
- [Connection Sequence Diagram](doc/sequence_diagram.puml)
- [Kademlia Forked Search Sequence Diagram](doc/kad_search_sequence.puml)
- [Credits](doc/credits.md)