# Kademlia Search Bridge

JEmuleServer embeds a Kademlia (Kad) node that extends the searches of its ed2k clients to the Kad network, while
filtering everything it receives. This document describes the module delivered for version 1.2 (package
`org.jemule.kad`).

## 1. Goals

- **Coverage**: an `OP_SEARCHREQUEST` sent to the server is answered from the local index and, at the same time,
  projected onto the Kad network.
- **Protection**: every file coming from Kad goes through the same filters as published files (child abuse filter,
  fake file detector, banned hashes). An illicit file never reaches a client and its hash is banned for good.
- **Cache warming**: clean Kad files with at least one source are stored in H2 and indexed, so that the next searches
  on the same terms find them locally, in the first batch of results, with Kad's retained source count available to
  the client.

The ed2k client-server protocol is unchanged: the Kad files reach the clients as ordinary `OP_SEARCHRESULT` answers,
so the historical eMule clients (eMule 0.4x / 0.50a, aMule) need no change, nor Kad support of their own.

The server only consumes Kad content: it does not publish its files on Kad, does not answer the requests of other
Kad nodes and never relays file data.

## 2. Components

| Class | Role |
| :--- | :--- |
| `KadNode` | Orchestrator created by `Server` when `kadEnabled=true`: assembles the components below, persists the node ID and closes everything on shutdown. |
| `KadId` / `MD4` | 128-bit identifiers, XOR distance, keyword hashing (MD4 of the lower-case keyword, RFC 1320). |
| `KadContact`, `KadBucket`, `KadRoutingTable` | Routing table: 128 buckets of k = 20 contacts with a replacement cache, closest-contact lookup, capped by `kadMaxRoutingContacts`. |
| `KadOpCode`, `KadPacket` | Encoding and decoding of the Kad UDP messages (section 4). |
| `KadUdpService` | UDP socket of the node, receive loop on a virtual thread, dispatch of the packets by opcode. |
| `KadBootstrapManager` | Fills the routing table from connected HighID clients or from `kad bootstrap`; status `DISCONNECTED` → `BOOTSTRAPPING` → `CONNECTED` (from 5 contacts). |
| `KadMaintenanceService` | Every minute: liveness pings (`HELLO_REQ`) of contacts idle for 15 minutes, eviction after 3 unanswered pings, refresh (`REQ`) of buckets idle for 1 hour. |
| `KadSearchBridge` | Forks the searches (`SEARCH_KEY_REQ` to the α = 3 closest contacts), filters the answers, ingests the clean files and returns them to `SearchHandler`. |
| `KadHashBanService` | Persists the hashes of illicit Kad files in `banned_hashes`, propagates them to `FakeFileDetector` and reloads them at startup. |

## 3. Flows

### 3.1 Node identity and startup

On first start, the node draws a random 128-bit ID and stores it in the H2 table `stats` as two 64-bit halves
(`kad_node_id_hi`, `kad_node_id_lo`); later starts reuse it. The node binds `kadPort` (default: TCP port + 11, i.e.
`4672`). If the port is unavailable, Kad is disabled and the server keeps working on its local index.

### 3.2 Bootstrap from connected clients

When a client logs in with a HighID (and a non-loopback address), the node sends `BOOTSTRAP_REQ` to its likely Kad
ports: `listenPort + 10` and `4672`. The contacts returned in `BOOTSTRAP_RES` are added to the routing table. The admin
command `kad bootstrap <ip> <port>` sends the same request to a given node.

### 3.3 Forked search

```
Client ──OP_SEARCHREQUEST──▶ SearchHandler ──▶ FileIndex (H2 + memory)
                                  │
                                  └──▶ KadSearchBridge ──SEARCH_KEY_REQ──▶ 3 closest Kad contacts
                                              ◀──SEARCH_RES── (filtered, ingested)
```

1. The query is answered from `FileIndex`.
2. The first significant keyword (3 characters or more) is hashed and sent in `SEARCH_KEY_REQ` to the 3 closest
   contacts. Identical searches running at the same time share one Kad session.
3. A `SEARCH_RES` is only accepted from a contact the search was sent to: an unsolicited answer, from any other
   address, is ignored, so that nobody can fill the database with files of their choice. Each file of an accepted
   `SEARCH_RES` is checked, in this order: banned hashes, `ChildAbuseFilter`, `FakeFileDetector`.
   A file rejected by the child abuse filter has its hash banned in memory and in `banned_hashes`.
4. A clean file with at least `kadMinSourcesForIngest` sources (`FT_SOURCES`) is added to `FileIndex`, which stores it
   in the H2 tables `files` and `file_tags` (when `kadAutoIngestToH2=true`), then becomes a result of the session.
   Its `FT_SOURCES` count is retained and returned in the client's `OP_SEARCHRESULT`; when the same hash is already
   indexed from connected clients or an earlier Kad result, the highest known count is preserved. Files without
   sources are dropped.

How long the client waits depends on the local results:

| Local results | Answer |
| :--- | :--- |
| None (cold search) | The server waits for the Kad answers, at most `kadSearchTimeoutSeconds`, then sends the deduplicated Kad files. Beyond 50 files, the first `OP_SEARCHRESULT` has `more = 1` and `OP_QUERY_MORE_RESULT` returns the next batches. |
| 1 to 49 (warm search) | The local results are sent at once. The Kad search keeps running in the background: its files are ingested and will be found locally by the next searches. |
| 50 or more | The first 50 local results are sent at once with `more = 1`; the Kad files join the results left for `OP_QUERY_MORE_RESULT`. |

A search that cannot send any request (empty routing table, closed socket) ends at once: a server isolated from Kad
answers as fast as without Kad. Only TCP searches are forked; global UDP searches (`OP_GLOBSEARCHREQ`) are answered
from the local index.

## 4. UDP messages

Kad packets have no length header: `<protocol 1><opcode 1><payload>`, integers in little-endian.

| Message | Protocol / opcode | Payload | Use |
| :--- | :---: | :--- | :--- |
| `BOOTSTRAP_REQ` | `0x3A` / `0x01` | empty | sent |
| `BOOTSTRAP_RES` | `0x3A` / `0x02` | `<count 2>(<id 16><ip 4><udp 2><tcp 2><version 1>)*` | received |
| `HELLO_REQ` | `0x3A` / `0x11` | `<id 16><udp port 2><version 1><tag count 1>` | sent (liveness ping) |
| `HELLO_RES` | `0x3A` / `0x12` | same as `HELLO_REQ` | received |
| `REQ` | `0x3A` / `0x21` | `<type 1><target 16>` | sent (bucket refresh) |
| `RES` | `0x3A` / `0x22` | `<target 16><count 1><contacts>` | decoded |
| `SEARCH_KEY_REQ` | `0x3A` / `0x33` | `<keyword hash 16><start position 2>` | sent |
| `SEARCH_RES` | `0x3A` / `0x3B` | `<target 16><count 2>(<file hash 16><tag list>)*` | received |

> **Interoperability**: these values follow the design of SFD 1.2 and are consistent between the server and its
> tests, but they differ from the Kad2 framing of eMule 0.50a (header `OP_KADEMLIAHEADER` `0xE4`, `BOOTSTRAP_RES`
> `0x09`, `HELLO_RES` `0x19`, `RES` `0x29`, see [`eMule_Protocol_RetroSpecification.md`](eMule_Protocol_RetroSpecification.md)).
> Exchanges with real eMule / aMule Kad nodes have not been validated yet.

## 5. Configuration

The keys live in [`server.properties`](../server.properties):

| Key | Default | Description |
| :--- | :---: | :--- |
| `kadEnabled` | `true` | Creates and starts the embedded Kad node. |
| `kadPort` | `0` | Kad UDP port; `0` = TCP port + 11 (`4672` with port `4661`). |
| `kadBootstrapFromClients` | `true` | Bootstraps from connected HighID clients. |
| `kadMaxRoutingContacts` | `500` | Maximum number of contacts in the routing table. |
| `kadForkSearchEnabled` | `true` | Forks the client searches to Kad. |
| `kadSearchTimeoutSeconds` | `4` | Maximum wait for the Kad answers of a cold search. |
| `kadAutoIngestToH2` | `true` | Stores and indexes the clean Kad files. |
| `kadMinSourcesForIngest` | `1` | Minimum number of sources for a Kad file to be delivered and stored. |

Open the Kad UDP port in the firewall (`4672/udp` by default) so that the answers of the Kad nodes reach the server.

## 6. Administration

| Command | Output |
| :--- | :--- |
| `kad status` (or `kad`) | Status (`DISCONNECTED`, `BOOTSTRAPPING`, `CONNECTED`), node ID, UDP port, contacts / maximum, bootstrap, forked search and ingestion settings. |
| `kad contacts` | The 20 most recently seen contacts: ID, address, Kad version, last seen, liveness (`ALIVE`, `UNANSWERED (n)`, `DEAD`). |
| `kad bootstrap <ip> <port>` | Sends `BOOTSTRAP_REQ` to the given node. |
| `kad stats` | Searches forked, active searches, results received, files ingested, illicit files blocked, hashes banned, bootstrap and maintenance counters. |

With `kadEnabled=false`, every `kad` command answers `Kademlia is disabled (kadEnabled=false).`

## 7. Tests

`KadAcceptanceTest` runs the acceptance cases end to end: a started `KadNode` backed by H2 and `FileIndex`, eMule
clients going through `SearchHandler`, and a Kad peer answering on the loopback.

| Case | Test |
| :--- | :--- |
| Bootstrap from a HighID client | `tc01_highIdClientLoginBootstrapsTheRoutingTable` |
| Pure local search | `tc02_pureLocalSearchIsAnsweredFromH2` |
| Forked search and conditional ingestion | `tc03_tc04_forkedSearchDeliversKadFilesAndIngestsOnlyThoseWithSources` |
| Illicit Kad file | `tc05_illicitKadFileIsNeverSentAndItsHashIsBannedForGood` |
| Pagination of a cold search | `tc06_coldSearchOverFiftyResultsIsPaginatedWithQueryMore` |
| Kad socket closed / DHT unreachable | `tc07_closedKadSocketDoesNotDelayAnySearch`, `tc07_unreachableDhtBoundsColdSearchesByTheKadTimeout` |
| Warm search | `tc08_warmSearchIsServedAtOnceFromH2WithoutQueryMore` |
| 95 % of the searches under 10 ms | `searchesAnswerUnderTenMillisecondsWhenResultsAreKnownLocally` |

Each component also has its unit tests in `src/test/java/org/jemule/kad`, and the admin commands are covered by
`AdminInterfaceTest`.
