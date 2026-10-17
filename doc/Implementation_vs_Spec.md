# Implementation vs eMule Protocol Retro-Specification

Summary
-------
This document compares the sequence diagrams described in `doc/eMule_Protocol_RetroSpecification.md` with the current implementation of the project, lists the responsible files and points out divergences and recommendations.

Checks performed
----------------
Inspected files (reference):
- src/main/java/org/jemule/network/Packet.java
- src/main/java/org/jemule/protocol/OpCode.java
- src/main/java/org/jemule/network/handler/LoginHandler.java
- src/main/java/org/jemule/network/handler/PacketProcessor.java
- src/main/java/org/jemule/network/handler/SourceHandler.java
- src/main/java/org/jemule/network/Server.java
- src/main/java/org/jemule/protocol/Tag.java

1) Diagram: Client-Server Connection (Login)
- Spec: Client -> OP_LOGINREQUEST, Server -> OP_SERVERIDENT, OP_IDCHANGE (if needed), OP_SERVERMESSAGE, OP_SERVERSTATUS, (optional) OP_SERVERLIST
- Implementation: `LoginHandler.handleLogin` sends `sendServerIdent` (0x41) as the first packet, then ID_CHANGE (0x40), LOGIN_ACCEPTED (0x1B), and finally `OP_SERVERMESSAGE` (0x38) containing the server version for eMule auto-detection.
- Conclusion: Matches the diagram and is optimized for eMule version detection.

2) Diagram: File Search (Search)
- Spec: OP_SEARCHREQUEST -> OP_SEARCHRESULT, possibly OP_QUERY_MORE_RESULT
- Implementation: `PacketProcessor` maps SEARCH_REQUEST to `SearchHandler.handleSearch` and QUERY_MORE_RESULT to `SearchHandler.handleQueryMoreResult`. `SearchHandler` builds SEARCH_RESULT and sends it back via `Packet`.
- Conclusion: Compliant. Code: src/main/java/org/jemule/network/handler/PacketProcessor.java and src/main/java/org/jemule/network/handler/SearchHandler.java

3) Diagram: Requesting and Obtaining Sources (GetSources)
- Spec (TCP): OP_GETSOURCES -> OP_FOUNDSOURCES
- Implementation (TCP): `SourceHandler.handleGetSources`, with the answer sent back via `Packet` as FOUND_SOURCES/SOURCES_RESULT_OBFU.
- Implementation (UDP): `Server.handleUdp` handles OP_GLOBGETSOURCES (0x9A) and answers with OP_GLOBFOUNDSOURCES (0x9B), as well as OP_GLOBSERVSTATRES (0x97) with a fixed 44-byte structure for eMule compliance.
- Conclusion: Compliant for TCP and UDP. Code: src/main/java/org/jemule/network/handler/SourceHandler.java and src/main/java/org/jemule/network/Server.java (handleUdp)

4) Diagram: File Transfer (Client-Client)
- Spec: OP_HELLO / OP_HELLOANSWER, OP_REQUESTPARTS, OP_SENDINGPART, OP_END_OF_DOWNLOAD
- Server implementation: The server provides relay and callback primitives (OpCode.CALLBACK) and accepts some opcodes (a COMPRESSED_PART handler exists), but direct client->client transfer is handled by the clients themselves. The server does not implement peer-to-peer file block exchange (this is expected).
- Conclusion: Matches the intent (the server is not supposed to act as a client for P2P transfers); the server supports the callbacks and responses needed to set the transfer up.

5) Kademlia (Bootstrap / Hello / Search)
- Spec: Kad2 opcodes under the `OP_KADEMLIAHEADER` (0xE4) header: `KADEMLIA2_BOOTSTRAP_REQ` (0x01) / `RES` (0x09), `KADEMLIA2_HELLO_REQ` (0x11) / `RES` (0x19), `KADEMLIA2_REQ` (0x21) / `RES` (0x29), `KADEMLIA2_SEARCH_KEY_REQ` (0x33), `KADEMLIA2_SEARCH_RES` (0x3B), `KADEMLIA2_PING` (0x60) / `PONG` (0x61).
- Implementation (version 1.2): the embedded node `KadNode` runs on its own UDP socket (`KadUdpService`, port `kadPort`, default TCP port + 11), separate from `Server.handleUdp`. It sends `BOOTSTRAP_REQ`, `HELLO_REQ`, `REQ` and `SEARCH_KEY_REQ`, and processes `BOOTSTRAP_RES`, `HELLO_RES` and `SEARCH_RES` (`KadOpCode`, `KadPacket`). It answers no request from other nodes and publishes nothing. See `doc/kademlia.md`.
- Divergence: `KadOpCode` uses the protocol byte `0x3A` instead of `0xE4`, and the response opcodes `BOOTSTRAP_RES` = 0x02, `HELLO_RES` = 0x12 and `RES` = 0x22 instead of 0x09, 0x19 and 0x29. The payloads are also simplified (no UDP verification key, no obfuscation). The server and its tests are consistent with each other, but exchanges with real eMule / aMule Kad nodes are not validated.
- Recommendation: align `KadOpCode` and `KadPacket` with the Kad2 framing of eMule 0.50a (header 0xE4, opcodes and payloads of `Kademlia/net/KademliaUDPListener.cpp`) and add a capture-based test against a real client.

Tags (TLV)
-----------
- Spec: Tag type, name-as-id when high bit set, else string; types include hash/string/uint32/etc.
- Implementation: `Tag.write` and `Tag.read` implement exactly the TLV mechanism (type, name-as-id via the high bit, or length + name string), with the matching types. `Tag.writeList`/`readList` use a 4-byte prefix to count the tags; this choice is internally consistent, but the presence of the count should be documented explicitly in the messages where it applies.

Divergences / Points of attention
---------------------------------
1. Opcode collision in `OpCode.java`:
   - `QUERY_MORE_RESULT` and `PUBLISH_ACK` shared the value `(byte)0x21`. This created an ambiguity if the code relied only on the byte value to tell client and server roles apart. The code avoided some collisions through `fromByte(protocol, b)`, but the recommendation was to fix the enum so that values are distinct and to add a unit test checking that values are unique per protocol. (Since resolved: `PUBLISH_ACK` no longer exists in `OpCode.java`.)

2. Tests / Build:
   - The project contains tests (src/test) covering packet parsing, server integration, etc. The project is built with Gradle: `./gradlew test` runs them (there is no Maven POM).

Recommended actions
-------------------
- Fix the opcode collision in `src/main/java/org/jemule/protocol/OpCode.java` (give PUBLISH_ACK a distinct value).
- Add a unit test file that explicitly validates the critical sequences (login sequence, search result + query_more, getsources TCP/UDP responses). These tests partly exist; complete them if needed.
- Document the presence of the tag counter (4 bytes) and the encoding variants (ED2K vs eMule) in `doc/eMule_Protocol_RetroSpecification.md` or in an annex.

Possible follow-ups
-------------------
- Apply the opcode fix and add a unit test.
- Add integration tests covering each sequence (login, search, getsources).
- Update `doc/eMule_Protocol_RetroSpecification.md` to include the implementation notes (tag counts, effective opcodes).

--
Note: This report is based on a reading of the code.
