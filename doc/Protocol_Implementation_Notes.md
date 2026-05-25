# Protocol Implementation Notes / Annex

This annex documents implementation-specific details discovered in the codebase and how they map to the retro-specification.

1) Tag list encoding (TLV list count)
- The implementation uses a 4-byte little-endian count prefix for tag lists in Tag.writeList / Tag.readList. That is, where the spec describes a sequence of tags the code currently encodes it as:
  [uint32 count] [tag1] [tag2] ...
- Recommendation: callers that build tag lists for packets must include the 4-byte count. The server's `sendServerIdent` and `SearchHandler.sendSearchResults` use `Tag.writeList`, so the count is present in packets where tags are transmitted.

2) Tag name encoding (ID vs string)
- Tag.write sets the MSB of the type byte if the name is a 1-byte ID. When MSB is set, the name follows as a single byte ID. Otherwise a uint16 length + UTF-8 name follows. This matches the spec's "Type | 0x80 indicates name is numeric ID" behavior.

3) ED2K vs eMule protocol variants
- The implementation distinguishes protocol bytes at packet level using `Packet.PROTOCOL_ED2K` (0xE3), `Packet.PROTOCOL_EMULE` (0xC5), `Packet.PROTOCOL_ZLIB` (0xD4), `Packet.PROTOCOL_KAD` (0xE4) and `Packet.PROTOCOL_KAD_ZLIB` (0xE5).
- Some opcodes have different meanings under different protocol identifiers (for example, EMULE_INFO is only considered when protocol==0xC5). `OpCode.fromByte(protocol, b)` implements special cases for 0xC5 and otherwise resolves by numeric value.

4) Compression and zlib
- Packet.write will compress payloads larger than 64 bytes when `useCompression` is true and set the protocol to the corresponding ZLIB protocol byte. Packet.read detects ZLIB protocol bytes and decompresses the payload before returning the Packet object.

5) UDP responses
- UDP packets use the compact format [Protocol][Opcode][Data...] (no header size field). Server.handleUdp implements legacy responses (OP_GLOBSERVSTATRES, OP_GLOBFOUNDSOURCES) and an IPv6 extension (opcode 0x9C).

If desired, this annex can be merged into `doc/eMule_Protocol_RetroSpecification.md` as an "Implementation notes" section. It is also referenced by `doc/Implementation_vs_Spec.md`.