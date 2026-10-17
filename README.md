# JEmuleServer

JEmuleServer is an eMule server written in Java (version 1.2).

Downloads (installers and JAR) are on the [releases page](https://github.com/dagga/JEmuleServer/releases); the changes
of each version are described in `doc/release-notes-v<version>.md` (latest: [1.2](doc/release-notes-v1.2.md)).

## Why this project?

This project was initiated because the historical source code of the **Lugdunum** eMule server was not available.
Lugdunum was the reference for eDonkey2000 (ed2k) servers for years, but its code remained closed and the binaries
became obsolete or difficult to run on modern systems. JEmuleServer aims to provide a modern, open-source (LGPLv3), and
high-performance alternative using the latest Java features (such as Virtual Threads).

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
`jemuleserver_<version>_amd64.deb` (Debian/Ubuntu), `jemuleserver-<version>-1.x86_64.rpm` (Fedora/RHEL) and
`JEmuleServer-<version>.exe` (Windows), plus the plain JAR and a systemd unit (`packaging/jemuleserver.service`).

- The application is installed in `/opt/jemuleserver` (Linux) or `Program Files\JEmuleServer` (Windows).
- Started without arguments, the installed program keeps its configuration (`server.properties`, created on first
  start) and its H2 database in `~/.local/share/jemuleserver` (Linux) or `%LOCALAPPDATA%\JEmuleServer` (Windows).
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

## Configuration

JEmuleServer uses a `server.properties` file for its configuration. If the file is not found, the server uses its internal default values.

Example `server.properties`:
```properties
port=4661
maxUsers=1000000
databasePath=./jemule_db
fakeFileDetectionEnabled=true
# ipFilterPath=ipfilter.dat
# Timeout of the HighID test (0 = every client gets a LowID)
lowIdCheckTimeoutSeconds=5
```

All parameters can be tuned in this file, including quotas, flood protection thresholds, and circuit breaker settings.
The commented [`server.properties`](server.properties) at the root of the repository lists all of them.

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

JEmuleServer includes a command-line interface (CLI) for administration. When running the server, you can type commands directly into the terminal.

A server running as a service (systemd, Windows service) has no terminal. Set `adminPort` in `server.properties` to
open the same console on a TCP port bound to `127.0.0.1` only, then connect from the server machine:

```bash
nc 127.0.0.1 4670
```

The admin port has no authentication: anyone with a shell on the machine can use it. It is disabled by default
(`adminPort=0`). In such a session, `exit` and `quit` close the session; `stop` still shuts the server down.

Available commands:
- `status`: Displays server performance, uptime, and core statistics.
- `clients`: Lists connected clients with their IP, ID, type (HighID/LowID), and published files count.
- `files`: Shows file indexing statistics and fake file detection status.
- `events`: Shows the number of events of each type (connections, logins, publications, searches...) since startup
  and during the last minute.
- `ban <hash> [reason]`: Bans a file hash (32-char hex) for good: it is stored in the database with its reason and the
  file is removed from the index.
- `kad status`: Shows the status of the embedded Kademlia node (`DISCONNECTED`, `BOOTSTRAPPING`, `CONNECTED`), its
  node ID, UDP port and number of contacts (`kad` alone does the same).
- `kad contacts`: Lists the 20 most recently seen Kademlia contacts (ID, address, version, liveness).
- `kad bootstrap <ip> <port>`: Sends a bootstrap request to a given Kademlia node.
- `kad stats`: Shows the Kademlia counters (searches forked, results received, files ingested, illicit files blocked,
  hashes banned, bootstrap and maintenance activity).
- `help`: Shows the list of available commands.
- `stop` / `exit` / `quit`: Safely shuts down the server.

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

Here is a comparison between JEmuleServer and the historical **eServer (Lugdunum)**:

| Feature                             |       JEmuleServer        | eServer (Lugdunum)  |
|:------------------------------------|:-------------------------:|:-------------------:|
| **Language / Runtime**              | Java 21 (Virtual Threads) |  C / C++ (Native)   |
| **Protocol Obfuscation (RC4)**      |        ✅ Supported        |     ✅ Supported     |
| **ZLIB Compression**                |        ✅ Supported        |     ✅ Supported     |
| **Advanced Search (AND/OR/NOT)**    |        ✅ Supported        |     ✅ Supported     |
| **Large Files (>4GB) Support**      |        ✅ Supported        |     ✅ Supported     |
| **Lugdunum Extensions (0x40-0x42)** |        ✅ Supported        |     ✅ Supported     |
| **Embedded Database**               |     ✅ Supported (H2)      | ❌ No (Memory/Files) |
| **User Quotas**                     |        ✅ Supported        |     ✅ Supported     |
| **Event-Driven Monitoring**         |        ✅ Supported        |        ❌ No         |
| **IP Filtering (ipfilter.dat)**     |        ✅ Supported        |     ✅ Supported     |
| **LowID Management (Callback)**     |        ✅ Supported        |     ✅ Supported     |
| **Fake File Detection**             |        ✅ Supported        |     ✅ Supported     |
| **Child Abuse Filter**              |        ✅ Supported        |        ❌ No         |
| **Kademlia Search Bridge**          |        ✅ Supported        |        ❌ No         |
| **External Config File**            |        ✅ Supported        |     ✅ Supported     |
| **Admin Interface**                 |        ✅ Supported        |     ✅ Supported     |
| **IPv6 Connections**                | ⚠️ Partial ([limits](doc/ipv6.md)) |        ❌ No         |

## License

This project is distributed under the **GNU Lesser General Public License v3.0 (LGPLv3)**. See the `LICENSE.LGPL` and
`LICENSE.GPL` files for more details.

## Author

**Nicolas Hernandez** (hernicatgmail.com)

## Documentation

More information can be found in the `doc/` directory:
- [Release notes 1.2](doc/release-notes-v1.2.md) / [1.1](doc/release-notes-v1.1.md) / [1.0.1](doc/release-notes-v1.0.1.md) / [1.0](doc/release-notes-v1.0.md)
- [Protocols Description](doc/protocols.md)
- [Kademlia Search Bridge](doc/kademlia.md)
- [Technical References](doc/references.md)
- [Class Diagram](doc/class_diagram.puml)
- [Connection Sequence Diagram](doc/sequence_diagram.puml)
- [Kademlia Forked Search Sequence Diagram](doc/kad_search_sequence.puml)
- [Credits](doc/credits.md)