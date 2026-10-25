# Kademlia Search Bridge

JEmuleServer embeds a Kademlia (Kad) node that extends the searches of its ed2k clients to the Kad network, while
filtering everything it receives. This document describes the module delivered for version 1.2 (package
`org.jemule.kad`).

## 1. Goals

- **Coverage**: an `OP_SEARCHREQUEST` sent to the server is answered from the local index and, at the same time,
  projected onto the Kad network.
- **Protection**: every file coming from Kad goes through the same filters as published files (child abuse filter,
  fake file detector, banned file names). An illicit file never reaches a client nor the database.
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
                                  └──▶ KadSearchBridge ──SEARCH_KEY_REQ──▶ 8 nodes closest to the keyword
                                              ◀──SEARCH_RES── (filtered, ingested, next pages requested)
```

1. The query is answered from `FileIndex`.
2. The keyword whose index is queried is the longest word every result must contain (a word inside an `OR`, or on
   the right of a `NOT`, is not required), as eMule does when it rearranges Kad keywords: a longer word is rarer, so
   its index is smaller. Words are split like Kad indexes them (eMule's `INV_KAD_KEYWORD_CHARS`, at least 3 UTF-8
   bytes). The DHT is walked towards the keyword hash, and the nodes found are reused for 2 minutes by the next
   searches of that keyword. `SEARCH_KEY_REQ` is sent to the 8 closest nodes, with the whole client query as a
   search expression (restrictive search, start position flag `0x8000`) unless the keyword alone is searched: the
   nodes then only return entries matching the query. Identical searches running at the same time share one Kad
   session.
3. A `SEARCH_RES` is only accepted from a contact the search was sent to: an unsolicited answer, from any other
   address, is ignored, so that nobody can fill the database with files of their choice. Each file of an accepted
   `SEARCH_RES` is checked, in this order: `ChildAbuseFilter`, then `FakeFileDetector` (banned file names, then its
   heuristics). Rejected files are counted, not remembered: the filters reject them again if they come back.
4. A clean file with at least `kadMinSourcesForIngest` sources (`FT_SOURCES`) is added to `FileIndex`, which stores it
   in the H2 tables `files` and `file_tags` (when `kadAutoIngestToH2=true`), then becomes a result of the session.
   Its `FT_SOURCES` count is retained and returned in the client's `OP_SEARCHRESULT`; when the same hash is already
   indexed from connected clients or an earlier Kad result, the highest known count is preserved. Files without
   sources are dropped.
5. A node returns at most 300 entries per request (`CIndexed::SendValidKeywordResult`) and skips the first
   `start position` ones. Once a node returned 270 entries or more for the last page requested, the next page is
   requested from it (start position 300, then 600), up to 3 pages per node and per search. eMule clients never
   use the start position.

How long the client waits depends on the local results:

| Local results | Answer |
| :--- | :--- |
| None (cold search) | The server waits for the Kad answers, at most `kadSearchTimeoutSeconds`, then sends the deduplicated Kad files. Beyond 300 files, the first `OP_SEARCHRESULT` has `more = 1` and `OP_QUERY_MORE_RESULT` returns the next batches. |
| 1 or more (warm search) | The first 300 local results are sent at once with `more = 1`; the Kad files not already sent join the results left for `OP_QUERY_MORE_RESULT`. A request for more results arriving before the Kad answers waits for them, at most `kadSearchTimeoutSeconds`, rather than using up one of the client's requests with an empty answer. |

Each `OP_SEARCHRESULT` carries up to 300 files: eMule clients send `OP_QUERY_MORE_RESULT` at most 5 times per search
(`MAX_MORE_SEARCH_REQ`), so a client can receive up to 1,800 files. The eD2K servers the search is also forked to
(`ServerSearchBridge`) contribute in the same way.

### 3.4 Flood limits of the Kad nodes

An eMule node counts the requests it receives from each IP (`CPacketTracking::InTrackListIsAllowedPacket`): above a
per-minute limit it drops them, and above five times the limit it bans the IP. All the clients of the server search
from its single IP, so `KadUdpService` replays that count for every node and refuses to send a request the node would
drop (`FloodLimitException`): the search goes to the next closest node instead, and a maintenance ping is postponed.

| Request | Per minute and per node IP |
| :--- | :---: |
| `SEARCH_KEY_REQ`, `SEARCH_SOURCE_REQ`, `SEARCH_NOTES_REQ`, `PUBLISH_KEY_REQ`, `HELLO_REQ` | 3 |
| `BOOTSTRAP_REQ`, `PUBLISH_SOURCE_REQ`, `PING` | 2 |
| `REQ` | 10 |

A node forgets one request every 60/limit seconds; the server waits 10% longer. Answers are not limited.

A search that cannot send any request (empty routing table, closed socket) ends at once: a server isolated from Kad
answers as fast as without Kad.

### 3.5 Global UDP searches

Clients of other servers send their global searches (`OP_GLOBSEARCHREQ`, `OP_GLOBSEARCHREQ2`, `OP_GLOBSEARCHREQ3`)
to every server of their list. They are answered from the local index only, and with `kadForkGlobalSearches=true`
(the default) they are also searched on Kad in the background, so that the files they look for are ingested. The
requester gets no late answer: a UDP source address can be forged.

These searches go to Kad only, never to the eD2K servers: another server forwarding global searches the same way
could send them back, and the two servers would loop. As a UDP request costs nothing to forge, each one is limited:

- an identical query is searched once per 30 minutes;
- at most 20 searches per minute in all, and 3 per minute per source IP;
- a query matching the child abuse filter is never searched;
- the Kad flood limits of section 3.4 apply as for any search.

Each global search is logged with its outcome, e.g.
`UDP global search from 203.0.113.5: Name:linux (12 local results, Kad: forwarded)`; the outcomes are `forwarded`,
`duplicate`, `rate limited`, `child abuse`, `no keyword` and `disabled`. The `searches` admin command and the
Searches view of the web panel count them, with the distinct clean files their Kad searches found; their Kad traffic
is also part of the Kad row.

### 3.6 Threads

Kad searches run on threads of the search tier, the Kad UDP receiver too, and the maintenance on the lowest tier
(see `ServerThreads`). The files found are delivered to the search at once and indexed and stored afterwards by the
ingestion thread, so that ingestion never holds back a search or a client connection.

## 4. UDP messages

Kad packets have no length header: `<protocol 1><opcode 1><payload>`, integers in little-endian.

| Message | Protocol / opcode | Payload | Use |
| :--- | :---: | :--- | :--- |
| `BOOTSTRAP_REQ` | `0xE4` / `0x01` | empty | sent |
| `BOOTSTRAP_RES` | `0xE4` / `0x09` | `<id 16><tcp port 2><version 1><count 2>(<id 16><ip 4><udp 2><tcp 2><version 1>)*` | received |
| `HELLO_REQ` | `0xE4` / `0x11` | `<id 16><tcp port 2><version 1><tag count 1>` | sent (liveness ping) |
| `HELLO_RES` | `0xE4` / `0x19` | same as `HELLO_REQ` | received |
| `HELLO_RES_ACK` | `0xE4` / `0x22` | `<id 16>` | decoded |
| `REQ` | `0xE4` / `0x21` | `<type 1><target 16><check id 16>` | sent (bucket refresh) |
| `RES` | `0xE4` / `0x29` | `<target 16><count 1><contacts>` | decoded |
| `SEARCH_KEY_REQ` | `0xE4` / `0x33` | `<keyword hash 16><start position 2>[<search expression>]`; the top bit of the start position announces the expression (ed2k search tree format) | sent |
| `SEARCH_RES` | `0xE4` / `0x3B` | `<target 16><count 2>(<file hash 16><tag list>)*` | received |

> **Interoperability**: the framing follows the Kad2 protocol of eMule / mule (`OP_KADEMLIAHEADER` `0xE4`,
> `BOOTSTRAP_RES` `0x09`, `HELLO_RES` `0x19`, `RES` `0x29`, see
> [`eMule_Protocol_RetroSpecification.md`](eMule_Protocol_RetroSpecification.md)). Contact IPv4 addresses are
> serialized least-significant byte first, like in `nodes.dat`. The payloads stay minimal (no UDP verification
> key, no obfuscation). Bootstrap and hello responses announce the responder's local TCP port; a bootstrap
> answer inserts both the responder and the returned contacts into the routing table.

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
| `kadNodesUrls` | eMule Security + alldivx | Comma-separated `nodes.dat` URLs; downloaded at startup, refreshed daily and cached next to the database (empty = disabled). |
| `kadForkGlobalSearches` | `true` | Also searches on Kad, in the background, the UDP global searches of other servers' clients (section 3.5). |

Open the Kad UDP port in the firewall (`4672/udp` by default) so that the answers of the Kad nodes reach the server.

## 6. Administration

| Command | Output |
| :--- | :--- |
| `kad status` (or `kad`) | Status (`DISCONNECTED`, `BOOTSTRAPPING`, `CONNECTED`), node ID, UDP port, contacts / maximum, nodes.dat sources, bootstrap, forked search and ingestion settings. |
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
