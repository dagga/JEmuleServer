# JEmuleServer TODO List

This document lists the missing features or improvements needed to achieve feature parity with historical eMule
servers (Lugdunum type).

## 1. Protocol & Network

- [x] **Full ZLIB Compression**: Compression is currently supported for writing but must be validated for all types of
  incoming/outgoing packets.
- [x] **Protocol Obfuscation**: Implement the RC4 encryption layer to support obfuscated connections. Added Anti-Replay protection.
- [x] **Full Tags Support**: Extend the tag reading/writing system to support all types (String, Integer, Float, Bool,
  Blob).
- [x] **eMule Packets Support (0xC5)**: Add support for specific eMule messages (e.g., extended source requests).

## 2. Indexing & Search

- [x] **Data Persistence**: Implement a H2 database to save the file index and statistics between
  restarts.
- [x] **Advanced Search**: Support boolean operators (AND, OR, NOT) and filters (size, type, availability) in search
  queries.
- [x] **Index Limitation**: Add per-user quotas for the number of published files.
- [x] **Source Management**: Improve the source return algorithm to favor diversity and proximity.
- [x] **Performance & Security**: Optimize `FileIndex` with `ConcurrentSkipListMap`, LRU cache and better concurrency management.

## 3. Security & Stability

- [x] **IP Filtering (IPFilter)**: Support loading `ipfilter.dat` files to block undesirable IP ranges.
- [x] **Input Validation & GDPR**: Added strict input validation, socket timeouts (30s) and IP masking for GDPR compliance.
- [x] **Fake File Detection**: Implement heuristics to detect and ban corrupted or malicious files (spam).
- [x] **Advanced Anti-Flood Protection**: Refine limits per opcode and per IP to prevent DoS attacks. Optimized with Lock-Free Token Bucket.
- [x] **Circuit Breaker**: Implement the Circuit Breaker pattern (Resilience4j) to prevent error propagation.
- [x] **LowID Management**: Improve support for clients behind a firewall (callback mechanism).

## 4. Administration & Monitoring

- [x] **External Configuration File**: Move all parameters (port, limits, names) to a `server.properties`.
- [x] **Administration Interface**: Add an interface (command line) to monitor performance and manage
  bans.
- [x] **Detailed Statistics**: Log transfer statistics, number of searches per minute, and client version distribution.
- [x] **Observer/Pub-Sub Event System**: Implement an event system for better monitoring and decoupled hooks.
- [x] **Factory Pattern**: Centralize `ClientState` creation via `ClientFactory` for validation.
- [x] **Builder Pattern**: Implement a builder for `SearchQuery` to simplify complex query construction and validation.

## 5. Miscellaneous

- [ ] **Internationalization (I18N)**: Allow translation of system messages sent to clients.
- [x] **IPv6 Support**: Ensure full compatibility with the IPv6 stack.
- [ ] **File Filtering**: Implementation of a blacklist to reject certain files.
