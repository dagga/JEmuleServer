# JEmuleServer TODO List

This document lists the missing features or improvements needed to achieve feature parity with historical eMule
servers (Lugdunum type).

## 1. Protocol & Network

- [x] **Full ZLIB Compression**: Compression is currently supported for writing but must be validated for all types of
  incoming/outgoing packets.
- [ ] **Protocol Obfuscation**: Implement the RC4 encryption layer to support obfuscated connections.
- [x] **Full Tags Support**: Extend the tag reading/writing system to support all types (String, Integer, Float, Bool,
  Blob).
- [x] **eMule Packets Support (0xC5)**: Add support for specific eMule messages (e.g., extended source requests).

## 2. Indexing & Search

- [ ] **Data Persistence**: Implement a database (SQLite or other) to save the file index and statistics between
  restarts.
- [ ] **Advanced Search**: Support boolean operators (AND, OR, NOT) and filters (size, type, availability) in search
  queries.
- [ ] **Index Limitation**: Add per-user quotas for the number of published files.
- [ ] **Source Management**: Improve the source return algorithm to favor diversity and proximity.

## 3. Security & Stability

- [ ] **IP Filtering (IPFilter)**: Support loading `ipfilter.dat` files to block undesirable IP ranges.
- [ ] **Fake File Detection**: Implement heuristics to detect and ban corrupted or malicious files (spam).
- [ ] **Advanced Anti-Flood Protection**: Refine limits per opcode and per IP to prevent DoS attacks.
- [ ] **LowID Management**: Improve support for clients behind a firewall (callback mechanism).

## 4. Administration & Monitoring

- [ ] **External Configuration File**: Move all parameters (port, limits, names) to a `server.properties` or
  `config.yml` file.
- [ ] **Administration Interface**: Add an interface (command line or mini web server) to monitor performance and manage
  bans.
- [ ] **Detailed Statistics**: Log transfer statistics, number of searches per minute, and client version distribution.

## 5. Miscellaneous

- [ ] **Internationalization (I18N)**: Allow translation of system messages sent to clients.
- [ ] **IPv6 Support**: Ensure full compatibility with the IPv6 stack.
- [ ] **File Filtering**: Implementation of a blacklist to reject certain files.
