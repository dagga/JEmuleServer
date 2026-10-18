## JEmuleServer 1.2.1

This patch release builds on 1.2 with server federation, administration, search improvements and protocol documentation
updates. All code comments added since 1.2 and the associated commit messages are in English.

### New features and improvements

- **Verified server federation**: collect candidate servers from connected clients, verify their availability regularly,
  exchange server lists using obfuscated server-to-server UDP gossip, and search verified peers for matching files.
- **Loopback admin web panel**: inspect server status, search clients, review verified peers, manage filters and hash
  blocks, and adjust supported runtime settings through a local web interface.
- **Kad source counts in search results**: retain accepted Kad source counts when combining results with files already
  in the local index, and report the highest known count to clients.
- **Search and index improvements**: rank results by source count and reduce memory use for large indexes.
- **IP filter maintenance**: refresh configured IP-filter sources with guarded updates.
- **LowID callbacks**: coordinate TCP hole punching for clients that both have LowIDs.
- **Protocol references**: compare observed wire behavior against eMule 0.50.1 client source and record unresolved or
  contradictory protocol documentation separately.

### Compatibility

The eD2K client-server protocol remains unchanged for eMule clients. Kad-discovered files are returned as ordinary
`OP_SEARCHRESULT` entries, with their known source counts included in the existing `FT_SOURCES` tag. The admin web
panel is bound to loopback and can be disabled with `adminWebPort=0`.

The embedded Kad implementation still uses its documented project-specific framing (`0x3A`), which differs from
eMule's Kad2 framing (`0xE4`); interoperability with real eMule/aMule Kad nodes is not claimed.

### Downloads

Release installers bundle their Java runtime. The JAR requires Java 21 or later.
