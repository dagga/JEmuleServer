# Compatibility audit: eMule client ↔ JEmuleServer

- **Date**: 2026-10-12
- **Reference client**: eMule **0.50a** sources (`/home/dagga/IdeaProjects/eMule`, `Version.h`: 0.50.1)
- **Audited server**: JEmuleServer, `main` branch, commit `5cc2106` ("Fix obfuscation")
- **Method**: reading of the client code (`ServerSocket.cpp`, `sockets.cpp`, `EncryptedStreamSocket.cpp`, `UDPSocket.cpp`, `packets.cpp`, `SharedFileList.cpp`, `SearchResultsWnd.cpp`, `SearchList.cpp`, `SearchFile.cpp`, `DownloadQueue.cpp`, `PartFile.cpp`, `BaseClient.cpp`, `opcodes.h`, `server.h`, `Preferences.h`), then byte-by-byte comparison with what JEmuleServer sends and expects. The other documents in `doc/` were **not** used as a reference: everything written here comes solely from the client and server code.

> All integers of the ed2k protocol are **little-endian**. Client-side paths are relative to the root of the eMule sources; server-side paths are relative to `src/main/java/org/jemule/`.

---

## 1. Summary

With an eMule 0.50a client in its **default configuration**, JEmuleServer **accepts the connection** and the client shows as "connected" with a HighID. However, **the three essential services of an ed2k server do not work correctly**:

| Service | Status | Main cause |
|---|---|---|
| Connection / login (plain TCP) | ✅ Works | — |
| Obfuscated connection (TCP) | ❌ Always fails (the client then falls back to plain) | DH magic written in big-endian, client padding not consumed, bogus "0x97 mode" |
| Publishing of shared files | ❌ Very partial | "NEWTAGS" tag types not handled, integers read as signed, `data[0] < 32` heuristic |
| Search (TCP) | ⚠️ Degraded | Result count in big-endian, integer file type, non-ASCII keywords that return the whole index |
| Source lookup (TCP) | ❌ Ignored by default | `0x23` (GETSOURCES_OBFU) is interpreted as an ID_CHANGE; the answer is wrong in every case |
| Source lookup (UDP) | ❌ Not working | The client encrypts all its UDP packets to the server, which does not decrypt them; IP/port in big-endian |
| Returned sources | ❌ Unusable | Ephemeral port instead of the listening port, a single source per file, sources never purged |
| LowID / callback | ❌ Missing | HighID given to everyone without a reachability test; wrong callback opcode |
| Global UDP search, "more results" | ❌ / ⚠️ | No handling of `OP_GLOBSEARCHREQ*`, no "more" byte |

**Verdict**: JEmuleServer **does not** currently provide the minimal service of a Lugdunum server. A client can connect, but it cannot publish reliably, it finds incomplete or wrong results and, above all, **it cannot get any usable source**. File transfers between the server's users therefore do not work.

Most of the defects are **localized and easy to fix** (endianness, opcode table, tag types). The fix plan is given in section 4.

### Findings table

| # | Sev. | Area | Finding |
|---|---|---|---|
| C1 | 🔴 Blocking | TCP sources | `OP_GETSOURCES_OBFU` (0xE3/0x23) is routed to `ID_CHANGE_V2`: the request is ignored |
| C2 | 🔴 Blocking | TCP sources | The answer is sent with protocol 0xC5, which the client rejects on the server socket; the encryption options byte is also missing |
| C3 | 🔴 Blocking | TCP/UDP sources | Source IP and port written in big-endian (`DataOutputStream`) |
| C4 | 🔴 Blocking | Sources | The source port is the ephemeral TCP port of the connection to the server, not the listening port announced at login |
| C5 | 🔴 Blocking | Index | `byHash.put()` overwrites the file on each publication: a single source per file |
| C6 | 🔴 Blocking | Index | Sources and files never removed on disconnection |
| C7 | 🔴 Blocking | Tags | `TAGTYPE_STR1..STR16` types (0x11–0x20) not handled; `TYPE_UINT64 = 0x14` instead of `0x0B` |
| C8 | 🔴 Blocking | Publishing | `data[0] < 32` heuristic: a client sharing 32 to 127 files has its whole publication ignored |
| C9 | 🔴 Blocking | Publishing | `uint8/uint16/uint32` integers read as signed: files of 2 to 4 GB, and some short sizes, rejected |
| C10 | 🔴 Blocking | UDP | `SRV_UDPFLG_UDPOBFUSCATION` is announced although encrypted UDP is not implemented: all of the client's UDP is lost after the first ping |
| C11 | 🔴 Blocking | TCP obfuscation | The DH handshake always fails (magic in big-endian, padding not consumed, bogus "0x97" mode) |
| C12 | 🟠 Major | Search | Result count written in big-endian |
| C13 | 🟠 Major | Publishing/Search | `FT_FILETYPE` is received as an integer (TYPETAGINTEGER), stored as the string `"2"` and sent back as is: the type filter never works |
| C14 | 🟠 Major | Search | Non-ASCII keywords: all tokens become empty, and the query matches the **whole** index |
| C15 | 🟠 Major | Search | 64-bit numeric parameter (type 8) not handled: the query falls back to a "text search" on the raw bytes |
| C16 | 🟠 Major | Login | Always a HighID, without a reachability test: NATed clients are unreachable |
| C17 | 🟠 Major | Callback | The server answers with `0x99` instead of `OP_CALLBACKREQUESTED` (0x35), with a byte-swapped and wrong port, and without `OP_CALLBACK_FAIL` |
| C18 | 🟠 Major | UDP | `OP_GLOBSERVSTATRES` puts `port+4` where the obfuscation UDP port belongs |
| C19 | 🟠 Major | UDP | Invented `0x9C` "GLOBFOUNDSOURCES_V6" packet: 0x9C is actually `OP_GLOBCALLBACKREQ` |
| C20 | 🟡 Minor | Search | No trailing "more" byte; `OP_QUERY_MORE_RESULT` therefore cannot be triggered from the UI |
| C21 | 🟡 Minor | Search | No global UDP search (`OP_GLOBSEARCHREQ`/`2`/`3`) |
| C22 | 🟡 Minor | Search | Size bounds are off by 1 (`>=`/`<=` applied to `val-1`/`val+1`) |
| C23 | 🟡 Minor | Identity | `ST_VERSION` = 17 is displayed as "0.17" (the expected format is `maj<<16 | min`) |
| C24 | 🟡 Minor | Login | Useless packets, or packets unknown to the client: `0x1B`, `0x4A` (in 0xC5 and in 0xE3), `0x22`, and a UDP datagram sent to the client's **TCP** port |
| C25 | 🟡 Minor | Robustness | `Packet.decompress` loops forever on a truncated zlib stream |
| C26 | 🟡 Minor | Login | The login tags (`CT_NAME`, `CT_VERSION`, `CT_SERVER_FLAGS`, `CT_EMULE_VERSION`) are not read |

---

## 2. What the eMule client does (reference)

### 2.1 TCP connection sequence

1. `CServerConnect::ConnectToServer` → `CServerSocket::ConnectTo` (`ServerSocket.cpp`).
   If `IsServerCryptLayerTCPRequested()` is true (option "prefer obfuscated connections", **disabled by default**) and the server announces an obfuscation port and the TCPOBFUSCATION flag, the client connects to the obfuscation port in DH mode. Otherwise, it connects in plain.
2. If an obfuscated connection fails, the client **retries in plain** unless encryption is "required" (`sockets.cpp`, `CServerConnect::ConnectionFailed` / `TryAnotherConnectionRequest`).
3. `CServerConnect::ConnectionEstablished` (`sockets.cpp`) sends **`OP_LOGINREQUEST` (0xE3/0x01), uncompressed**:
   `<userhash 16><clientID 4 = 0><listening TCP port 2><tagcount 4 = 4>` followed by 4 tags in the **old** format (`WriteTagToFile`: `type`, `uint16 namelen = 1`, `nameID`):
   `CT_NAME` (string), `CT_VERSION` = 0x3C, `CT_SERVER_FLAGS` = `SRVCAP_ZLIB|NEWTAGS|LARGEFILES|UNICODE` + encryption bits, `CT_EMULE_VERSION`.
4. The client switches to `CS_CONNECTED` **only** when it receives `OP_IDCHANGE` (0x40). It then reads (`CServerSocket::ProcessPacket`):
   `<clientID 4>[<TCP flags 4>][<unused 4>][<reported IP 4>][<obfuscation TCP port 4>]`.
5. As soon as it is connected, it calls `SendListToServer()` (publishing), then `OP_GETSERVERLIST` if the option is enabled.
6. Opcodes handled on the server socket: `0x38, 0x40, 0x33, 0x42, 0x44, 0x34, 0x41, 0x32, 0x35, 0x36, 0x05`. Any packet with a protocol other than 0xE3 (or decompressed 0xD4) is **ignored** (`CServerSocket::PacketReceived`).
7. Keep-alive: an empty `OP_OFFERFILES` (`<count 4 = 0>`) if `ServerKeepAliveTimeout` > 0 (default value: 0, i.e. disabled).

### 2.2 Effects of the flags announced by the server

The flags announced by JEmuleServer **change what the client sends**. Each of them must therefore be honored:

| Announced flag | Consequence on the client side |
|---|---|
| `SRV_TCPFLG_COMPRESSION` (0x01) | Compressed `OP_OFFERFILES` (0xD4); in the offer, ID/port replaced with `0xFBFBFBFB/0xFBFB` (complete file) or `0xFCFCFCFC/0xFCFC` (partial file) |
| `SRV_TCPFLG_NEWTAGS` (0x08) | Offer tags written with `WriteNewEd2kTag`: strings of 1 to 16 bytes as `TAGTYPE_STR1..STR16` (0x11–0x20), integers packed into `UINT8`/`UINT16`/`UINT32` |
| `SRV_TCPFLG_UNICODE` (0x10) | Strings in raw UTF-8 |
| `SRV_TCPFLG_TYPETAGINTEGER` (0x80) | `FT_FILETYPE` sent **as an integer** (`ED2KFT_AUDIO=1 … CDIMAGE=7`) |
| `SRV_TCPFLG_LARGEFILES` (0x100) | Publishing of files > 4 GB (`FT_FILESIZE` + `FT_FILESIZE_HI`); searches with 64-bit parameters (type 8); `GETSOURCES` with `<0 4><size 8>` |
| `SRV_TCPFLG_TCPOBFUSCATION` (0x400) | `SupportsGetSourcesObfuscation()`: the client sends **`OP_GETSOURCES_OBFU` (0xE3/0x23)** instead of `OP_GETSOURCES` as soon as `CryptLayerSupported` is true (**true by default**) |
| `SRV_UDPFLG_EXT_GETSOURCES` (0x01) | `OP_GLOBGETSOURCES` contains up to 35 hashes |
| `SRV_UDPFLG_UDPOBFUSCATION` (0x200) | If the server's UDP key ≠ 0, **all** UDP packets to the server are encrypted and sent to the obfuscation UDP port (`CUDPSocket::SendBuffer`) |

---

## 3. Detailed findings

### 3.1 Source lookup (TCP): C1, C2, C3

**Client** (`DownloadQueue.cpp`, `ProcessLocalRequests`):
```
<hash 16><size 4>            (or <hash 16><0 4><size 8> for a large file)
protocol 0xE3, opcode 0x23 (OP_GETSOURCES_OBFU) if CryptLayerSupported && server TCPOBFUSCATION
                   otherwise 0x19 (OP_GETSOURCES)
```
Accepted answers (`ServerSocket.cpp`, in 0xE3 only):
- `OP_FOUNDSOURCES` 0x42: `<hash 16><count 1>(<ID 4><port 2>)*`
- `OP_FOUNDSOURCES_OBFU` 0x44: `<hash 16><count 1>(<ID 4><port 2><cryptOptions 1>[<userhash 16> if cryptOptions & 0x80])*` (`CPartFile::AddSources`)

**JEmuleServer**:
- `protocol/OpCode.java:30` and `:71`: in 0xE3, `GET_SOURCES_OBFU` is explicitly skipped and `0x23` maps to `ID_CHANGE_V2`. `PacketProcessor.handleIdChange` only writes a log line. **With the client's default configuration, no TCP source request succeeds.** (`OP_IDCHG 0x23` does not exist in the client-server protocol.)
- `network/handler/SourceHandler.java:76`: for a request in 0xC5, the answer is sent with **protocol 0xC5**, which the client rejects ("unknown protocol"). The client never sends 0xC5 to the server anyway.
- `SourceHandler.java:68-69`: `DataOutputStream.writeInt/writeShort` write in **big-endian**. Since `ipToLong()` already returns the little-endian value of the IP, the received address is **reversed** (1.2.3.4 becomes 4.3.2.1), and so is the port.
- The `_OBFU` format (a `cryptOptions` byte per source) is not produced.

**Fix**:
1. Remove `ID_CHANGE_V2` and map `0xE3/0x23` to `GET_SOURCES_OBFU`.
2. Always answer in 0xE3: `0x44` with `cryptOptions` (bit 0x01 supported, 0x02 requested, 0x04 required, 0x80 hash present, taken from the `CT_SERVER_FLAGS` of the source's login) if the request was `0x23`, otherwise `0x42`.
3. Replace `DataOutputStream` with a `LITTLE_ENDIAN` `ByteBuffer`.

### 3.2 Contents of the source index: C4, C5, C6

- **C4**: `PublishHandler.java:115` stores `context.getState()`, whose `port()` is `socket.getPort()` (`LoginHandler.java:34`), i.e. the **ephemeral source port** of the client's connection to the server. The real listening port is the one from the login (`<port 2>` after the ID): `LoginHandler` reads it (`:95`) but only under the misleading name `clientUdpPort`, then discards it. Clients therefore receive sources they cannot connect to.
- **C5**: `core/FileIndex.java:92` `byHash.put(meta.hash(), meta)` replaces the existing entry with a new `FileMetadata` that contains **only** the last publisher. A file therefore never has more than one source, and `FT_SOURCES` is always 1.
- **C6**: `ClientHandler.cleanup()` removes the client from the registry but not from the files' `sources()`. The files of a disconnected client stay indexed and are returned as dead sources, including after a restart through the database.

**Fix**: store in `ClientState` the announced listening port (as well as the encryption flags and the userhash); merge (`computeIfAbsent` + adding the source) in `addFile`; on disconnection, remove the source from each file published by that client, then remove the files that have no source left. The `FT_SOURCES` and `FT_COMPLETE_SOURCES` (0x30, see the `0xFB…`/`0xFC…` markers) counters can then be accurate.

### 3.3 Tag decoding: C7, C9

**Client** (`packets.cpp`):
- Types: `HASH 0x01, STRING 0x02, UINT32 0x03, FLOAT32 0x04, BOOL 0x05, BOOLARRAY 0x06, BLOB 0x07, UINT16 0x08, UINT8 0x09, BSOB 0x0A, UINT64 0x0B, STR1..STR16 0x11..0x20`.
- `WriteNewEd2kTag` (used for the file offer as soon as NEWTAGS is announced): a string of 1 to 16 bytes is written as `type=0x10+len` **without** a length field; an integer is written in the smallest possible `UINT8/16/32/64` type.

**JEmuleServer** (`protocol/Tag.java`):
- `TYPE_UINT64 = 0x14` (`:23`): 0x14 is actually **`TAGTYPE_STR4`**. A 4-byte string (codec "xvid", "h264", "divx", extension…) is read as an 8-byte `long`, which silently misaligns the rest of the packet.
- `0x0B` (the real UINT64) and `0x11–0x20` (STR1..STR16) throw `Unknown tag type` (`:231`). `readList` then stops and `PublishHandler` drops **all the following files** of the packet.
- `TYPE_BOOL_ALT = 0x06` is handled as 1 byte, whereas BOOLARRAY has the format `<len 2><bits>`.
- **C9**: `UINT32` is read with `getInt()` (signed, `:190`), `UINT16` with `getShort()`, `UINT8` with `get()`. A size ≥ 2 GiB (< 4 GiB), or a short size of 128–255 or 32,768–65,535 bytes, gives a negative value, and `PublishHandler.java:110` rejects the file (`size < 0`).

**Fix**: align the constants with `opcodes.h`; add STR1..STR16, UINT64 = 0x0B, BOOLARRAY and BSOB; read the integers as unsigned (`Integer.toUnsignedLong`, `& 0xFFFF`, `& 0xFF`); never stop reading a tag list because of an unknown type whose size is known.

### 3.4 Publishing: C8, C13

**Client** (`SharedFileList.cpp`, `SendListToServer` / `CreateOfferedFilePacket`): `OP_OFFERFILES` (0x15) = `<count 4>` then, for each file, `<hash 16><ID 4><port 2><tagcount 4><tags…>`. At most `min(ST_SOFTFILES, 200)` files per packet, compressed as 0xD4. Tags sent: `FT_FILENAME`, `FT_FILESIZE` [+ `FT_FILESIZE_HI`], `FT_FILERATING` (uint8), `FT_FILETYPE` (an **integer** since TYPETAGINTEGER is announced, a string only for collections), `FT_MEDIA_LENGTH/BITRATE/CODEC`.

**JEmuleServer** (`network/handler/PublishHandler.java`):
- **C8** (`:32`): `if (data[0] < 32)` chooses between a "binary" format and a "text" format `hash|name|size|type` that does not exist in eMule. `data[0]` is the low-order byte of the count: **for 32 to 127 files, the whole offer goes into the text branch and is ignored**.
- `:54`: the ID and port of the offer are ignored. At the very least, `0xFBFBFBFB` (complete) and `0xFCFCFCFC` (partial) should be recognized for `FT_COMPLETE_SOURCES`, and the session's ID and port used for the other values.
- **C13**: `FT_FILETYPE` arrives as `UINT8` and becomes `String.valueOf(Byte)`, i.e. `"2"`. It is then sent back as is in the results (`SearchHandler.java:94`). The client displays it as the file type, and `CSearchList::AddToList` **drops** the result when a search type is selected (`"Video" ≠ "2"`). On the other side, the search request sends the type as a **string** (`"Audio"`, `"Video"`, `"Image"`, `"Pro"`, `"Doc"`, see `GetSearchPacket`) and can never match.
- `0x22` answer (`PUBLISH_ACK`): opcode unknown to the client, no effect. To be removed.
- `FakeFileDetector` can send one `OP_SERVERMESSAGE` per rejected file. With 200 files, that is potentially a flood of messages.

**Fix**: remove the heuristic and the text format; publishing is always binary. Normalize `FT_FILETYPE` on reception (integer 1..7 → `Audio/Video/Image/Pro/Doc/Pro/Pro`, see `GetED2KFileTypeSearchTerm`) and send back the string.

### 3.5 Search: C12, C14, C15, C20, C21, C22

**Client** (`SearchResultsWnd.cpp`, `CSearchExprTarget` / `GetSearchPacket`):
- Operators `00 00` (AND), `00 01` (OR), `00 02` (NOT), in prefix notation.
- Keyword: `01 <len 2><utf8>`.
- String meta: `02 <len 2><value><0x0001><id 1>` (FT_FILETYPE, FT_FILEFORMAT, FT_MEDIA_CODEC…).
- Numeric: `03 <uint32><op 1><0x0001><id 1>`, **or `08 <uint64><op 1>…` if the value exceeds 4 GiB and the server announces LARGEFILES**. The client turns `>=v` into `> v-1` (op 1) and `<=v` into `< v+1` (op 2). The identifiers used are FT_FILESIZE, FT_SOURCES, FT_COMPLETE_SOURCES, FT_MEDIA_BITRATE and FT_MEDIA_LENGTH.
- Expected answer `OP_SEARCHRESULT` (0x33): `<count 4>(<hash 16><ID 4><port 2><tags>)*[<more 1>]` (`CSearchList::ProcessSearchAnswer`). The trailing `0x01` byte enables the "More" button, which sends `OP_QUERY_MORE_RESULT` (0x21).

**JEmuleServer**:
- **C12** (`SearchHandler.java:77`): `dos.writeInt(results.size())` is big-endian. For 1 result, the client reads 16,777,216. It adds the real results then throws an end-of-stream exception, which is caught for `OP_SEARCHRESULT`; but `LocalEd2kSearchEnd()` is never called and the search stays "in progress" until the timeout. The "more or less working" behavior observed is only a side effect.
- **C14** (`core/SearchQuery.java:69`): `split("[^a-zA-Z0-9]+")` removes all non-ASCII letters. For "été", "Привет" or "日本", all tokens are empty and `TermQuery` returns `true`: **the search returns any file of the index**. A Unicode tokenization (`\p{L}\p{N}`) is needed, consistent with `FileIndex.TOKENIZER`.
- **C15**: type `0x08` (64-bit numeric) is not handled, which causes an exception. The server then falls back to `FileIndex.search(new String(data))`, a search on the **binary bytes of the request**, whose result is meaningless. Type 8 must be handled, and when parsing fails the server should answer with 0 results rather than apply this fallback.
- **C22**: `MODE_MIN=1` is applied as `>=` on `v-1` and `MODE_MAX=2` as `<=` on `v+1`. Operators 0..5 (`ED2K_SEARCH_OP_*`) must be implemented with their real semantics (strict `>` and `<`).
- The `FT_SOURCES`, `FT_COMPLETE_SOURCES`, `BITRATE`, `LENGTH` and `CODEC` filters are ignored (always true).
- **C20**: the "more" byte is never sent, even though the server keeps pending results in batches of 50. `0x01`/`0x00` must be added at the end of the packet.
- **C21**: `Server.handleUdp` does not handle `OP_GLOBSEARCHREQ` (0x98), `REQ2` (0x92) or `REQ3` (0x90). The "global" search returns nothing from JEmuleServer.

### 3.6 Login, ID assignment and callback: C16, C17, C24, C26

- **C16** (`LoginHandler.java:32`): the ID is always the socket's IP, hence a HighID. Lugdunum first opens a connection to `<IP>:<listening port>` (the client sends `CT_EMULE_VERSION` for this test, see the comment in `sockets.cpp`) and gives a HighID only if it succeeds; otherwise it gives a LowID (< 16,777,216). Without this test, a client behind a NAT gets a HighID, its sources are unreachable and the callback mechanism is never used. Side effect: an IP whose last byte is 0 (`a.b.c.0`) produces an ID < 2^24, which the client considers a LowID.
- **C17** (`SourceHandler.handleCallback`): the client (`BaseClient.cpp`) sends `OP_CALLBACKREQUEST` 0x1C `<ID 4>`. The server must send the target **`OP_CALLBACKREQUESTED` (0x35)** `<IP 4><port 2>[<cryptOptions 1><userhash 16>]` with the requester's IP and **listening port**, or else answer `OP_CALLBACK_FAIL` (0x36) to the requester. JEmuleServer sends `0x99` (the client-client opcode `OP_CALLBACK`, unknown on the server socket), with `Short.reverseBytes(port)` (`:103`) applied to the ephemeral port, and sends nothing back on failure.
- **C24**: `OP_IDCHANGE` (0x40) and `OP_SERVERIDENT` (0x41) are correct. However:
  - `0x1B` (`LOGIN_ACCEPTED`) is not a server → client opcode; it is ignored;
  - `0x4A` is sent in 0xC5 (rejected) **and** in 0xE3 (unknown opcode); it is useless;
  - an unsolicited `GLOBSERVSTATRES`/`SERVER_DESC_RES` datagram is sent to the client's **TCP port** (`:95` then `sendUdpStatusToClient`);
  - 31 `OP_SERVERSTATUS` are sent during the first 5 minutes by a thread dedicated to each client, on top of the heartbeat.
  All of this should be removed: the client does not use it.
- **C26**: the login tags are not used. Yet `CT_SERVER_FLAGS` indicates zlib support (the server only enables compression if the login is in 0xD4, which eMule never does), Unicode support and the encryption options needed for `OP_FOUNDSOURCES_OBFU` and `OP_CALLBACKREQUESTED`. The `CT_NAME` nickname is not kept either.

### 3.7 TCP obfuscation: C11

**Client** (`EncryptedStreamSocket.cpp`, header and `StartNegotiation`/`Negotiate`, state `ECS_PENDING_SERVER`):
```
Client : <marker 1 ≠ E3/C5/D4><g^a 96><random padding 0-15>        (in plain)
Server : <g^b 96>(in plain) RC4[ <MAGICVALUE_SYNC 4 LE><methods 1><preferred 1><padLen 1><pad> ]
Client : RC4[ <MAGICVALUE_SYNC 4 LE><method 1><padLen 1><pad> ]      (sent with the 1st packet)
Keys   : S = (g^a)^b mod p on 96 bytes; server send = MD5(S‖203); receive = MD5(S‖34); 1024 RC4 bytes discarded
```

**JEmuleServer** (`network/handler/ObfuscationHandler.java`):
1. `:66`: reading stops at the first `0x97` byte found in `g^a`, which is random, so this happens in **about 30%** of connections. The server then switches to a "simplified 0x97 mode" that **does not exist** in eMule (key `MD5(0x97‖4 bytes)`), and the handshake fails.
2. `:212-213`: `ByteBuffer.allocate(…)` is big-endian by default, so `MAGICVALUE_SYNC` is sent as `83 5E 6F C4` whereas the client expects `C4 6F 5E 83` (little-endian `ReadUInt32`). The client answers "wrong magic value after DH-Agreement" and disconnects: **the handshake fails in 100% of the remaining cases**.
3. `:70`: the client's plain padding (0–15 bytes) is not consumed. It then goes into the RC4 decryptor and shifts the key stream.
4. `:252`, `:272`: the magic search on the receiving side compares the **high-order** byte (0x83) first, whereas the first byte received is the low-order one (0xC4).
5. `handleObfuscatedHandshake()` and `Obfuscation.isReplay()` implement a protocol that does not exist and are not used by the real flow.

**Impact**: clients with "prefer obfuscation" make a useless attempt and then fall back to plain (a delay on every connection). Clients with "obfuscation required" **cannot connect**.

**Fix**:
- Detection: 1st byte ∉ {E3, C5, D4} → read exactly 1 + 96 bytes, then **everything available without blocking** (the padding, sent in the same segment) before answering.
- Magic in little-endian.
- On the receiving side, look for `C4 6F 5E 83` after decryption, starting at byte 0 of the RC4 stream.
- Remove the "0x97 mode" entirely.
- Write a cross test with a vector produced by the C++ code (or a Java reimplementation of the client side in the test).

### 3.8 Server UDP: C10, C18, C19, C23

**Client** (`UDPSocket.cpp`):
- Ping: `OP_GLOBSERVSTATREQ` (0x96) `<challenge 4>` sent to `port+4`.
- The `OP_GLOBSERVSTATRES` answer is read as follows: `+0 challenge`, `+4 users`, `+8 files`, `+12 maxusers`, `+16 soft`, `+20 hard`, `+24 UDPflags`, `+28 lowid`, **`+32 obfuscation UDP port`, `+34 obfuscation TCP port`, `+36 UDP key`**.
- As soon as `key ≠ 0` and `UDPflags & 0x200`, and since `CryptLayerSupported` is true by default, `CUDPSocket::SendBuffer` **encrypts every packet** (`EncryptSendServer`) and sends it to `GetObfuscationPortUDP()`.

**JEmuleServer** (`network/Server.java`):
- **C10**: `UDPFLG_UDPOBFUSCATION` is announced with a random key (`:368-373`), but `handleUdp` drops any datagram whose 1st byte is neither E3 nor C5 (`:341`, "not fully implemented"). After the first successful ping, **all the client's pings, UDP source requests and global searches are lost**. The server ends up counted as "not responding" over UDP. **Immediate fix**: stop announcing `0x200` (and the key) until `EncryptedDatagramSocket` (client→server protocol: key `MD5(<server key 4><0x6B><random 2>)`, answer with `0xA5`, magic `0x13EF24D5`; see the header of `EncryptedDatagramSocket.cpp`) is implemented.
- **C18** (`:371`): `port+4` is placed at offset +32 (the **obfuscation** UDP port). If the 0x200 flag is kept, the real obfuscation UDP port must go there; otherwise, 0.
- **C3 (UDP)** (`:438`): `OP_GLOBFOUNDSOURCES` (0x9B) also has the IP and port in big-endian. Moreover, only the **first** of the 35 hashes of an `OP_GLOBGETSOURCES` is handled; the client accepts several concatenated `<hash><count>(…)` blocks in a single answer.
- **C19** (`:450`): the "V6" `0x9C` packet is an invention. `0x9C` is `OP_GLOBCALLBACKREQ` in `opcodes.h`. To be removed.
- `OP_GLOBGETSOURCES2` (0x94, `<hash 16><size 4>[…]`) is not implemented. This is consistent as long as `SRV_UDPFLG_EXT_GETSOURCES2` is not announced (it is not).
- `OP_SERVER_DESC_RES` in the new format (challenge `xxxxF0FF`) is correct. **C23**: the integer `ST_VERSION` = 17 is displayed as "0.17" (`strVersion.Format("%u.%02u", v>>16, v&0xFFFF)`). Either `(17<<16)|15` or a `"17.15"` string must be sent.

### 3.9 Robustness: C25

- `network/Packet.java:167`: `while (!i.finished())`: if the zlib stream is truncated, `inflate()` returns 0 with `needsInput()` true and the loop runs **forever** (denial of service with a single 0xD4 packet). Add `if (count == 0 && (i.needsInput() || i.needsDictionary())) throw …` and a bound on the decompressed size (the client uses 250,000 bytes for what comes from the server).
- A debug file `build/packet-debug.log` is written on every packet in DEBUG mode (synchronous I/O per packet).

---

## 4. Recommended fix plan

By order of impact, each step being testable on its own with an eMule 0.50a client:

1. **Endianness**: ban `DataOutputStream` from protocol answers (`SearchHandler`, `SourceHandler`, `Server.handleUdp`); DH magic in LE. *(C3, C11 in part, C12)*
2. **Opcode table**: `0xE3/0x23` → `GET_SOURCES_OBFU`; source answers always in 0xE3 (0x42, or 0x44 with `cryptOptions`); remove `ID_CHANGE_V2`, `0x9C` V6, `0x1B`, `0x4A`, `0x22`. *(C1, C2, C19, C24)*
3. **Tags**: constants aligned with `opcodes.h`, STR1..16, UINT64 = 0x0B, unsigned integers. *(C7, C9)*
4. **Publishing**: removal of the text format, normalization of `FT_FILETYPE`, handling of the `0xFB…`/`0xFC…` markers. *(C8, C13)*
5. **Source model**: listening port taken from the login, sources merged by hash, purge on disconnection. *(C4, C5, C6)*
6. **UDP**: stop announcing `SRV_UDPFLG_UDPOBFUSCATION` and a key (or implement encryption), multiple hashes in `GLOBGETSOURCES`, correct `GLOBSERVSTATRES` offsets. *(C10, C18)*
7. **Search**: Unicode tokenization, numeric type 8, operators 0..5, "more" byte, 0 results on a parsing error. *(C14, C15, C20, C22)*
8. **LowID and callback**: reachability test at login, `OP_CALLBACKREQUESTED`/`OP_CALLBACK_FAIL`. *(C16, C17)*
9. **TCP obfuscation**: rewrite of the DH handshake (or, until then, stop announcing TCPOBFUSCATION and an obfuscation port). *(C11)*
10. **Finishing touches**: `ST_VERSION`, global UDP search, zlib safeguard, parsing of the login tags. *(C21, C23, C25, C26)*

**Immediate conservative measure**: until steps 6 and 9 are done, remove `TCPFLG_TCPOBFUSCATION`, `UDPFLG_TCPOBFUSCATION`, `UDPFLG_UDPOBFUSCATION`, the UDP key and the obfuscation ports from the `OP_IDCHANGE`, `OP_SERVERIDENT`, `GLOBSERVSTATRES` and `SERVER_DESC_RES` answers. The client will then send `OP_GETSOURCES` (0x19) and plain UDP, two paths that are much closer to working.

### Suggested regression tests

The existing tests (`EmuleSpecTest`, `ServerTagsTest`) check JEmuleServer against its own understanding of the protocol. Tests that **replay frames built exactly like the client does** should be added:
- a compressed `OP_OFFERFILES` written with `WriteNewEd2kTag` (short name as STR*, size as UINT16, type as UINT8, codec "xvid") and 50 files;
- an `OP_GETSOURCES_OBFU` `<hash><size>` and a byte-by-byte check of the 0x44 answer;
- an `OP_SEARCHRESULT` read back with the logic of `CSearchList::ProcessSearchAnswer` (LE count + more byte);
- a full DH handshake, with the client side simulated in Java after `EncryptedStreamSocket.cpp`.

---

## 5. Compliant points

- TCP framing `<proto 1><len 4 LE><opcode 1><payload>` and 0xD4 decompression.
- `OP_SERVERMESSAGE`: `<len 2><text>`, with "server version x.y" recognized by the client.
- 20-byte `OP_IDCHANGE`: ID, TCP flags, reported IP, obfuscation port at the right offsets.
- `OP_SERVERIDENT`: `<hash><IP><port><tags>`; the `ST_SERVERNAME`/`ST_DESCRIPTION` tags are correctly read by the client (unknown tags are ignored without error).
- `OP_SERVERSTATUS` `<users 4><files 4>`.
- Empty `OP_SERVERLIST` (`count = 0`), valid.
- `OP_GLOBSERVSTATREQ`/`RES`: challenge echoed, and offsets 0 to 31 correct.
- `OP_SERVER_DESC_REQ`/`RES`: correct detection of the new format through `0xF0FF`.
- Parsing of the search tree (AND/OR/NOT, keywords, string metas with a numeric ID, 32-bit numeric).
- Client keep-alive (empty `OP_OFFERFILES`) accepted without side effects.
- DH constants (`dh768_p`, g = 2, 128-bit a/b, magic values 34/203, discarding of the first 1024 RC4 bytes) identical to the client.
