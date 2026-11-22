## JEmuleServer 1.7.2

### Fixes

- **Obfuscated logins no longer fail one time in 16 (#137).** eMule and aMule open the obfuscated client → server
  handshake with `<marker><g^a 96><padLen 1><padding padLen>`. The server did not read the length byte and looked for
  the client's encrypted answer one byte too early: with 15 bytes of padding, which a client draws one time in 16, the
  login failed with "Obfuscation handshake failed: magic value not received" and the client had to log in again. On
  a live server, 6.3% of the obfuscated logins failed this way. The server now reads the length and skips the padding.

### Changes

- **Relay feed: real source count and channel (#84, #138).** A relay now posts each file with its real source count,
  0 included, and the channel that found it (`jemuleOrigin` tag). The relay server applies its own threshold for that
  channel: its `kadMinSourcesForIngest` to the Kad files, its `serverIndexUpdateMinSources` to the index update files,
  one source to the other servers' search results, none to the files the relay's clients published. Before, a relay
  with `kadMinSourcesForIngest=0` brought Kad files without any source into the relay server's index, presented as
  having one. The dashboard tile "Files from relay servers" counts the files below the thresholds.

### New features

- **NAT traversal counters (#140).** The new `nat` console command and the dashboard's "NAT traversal" tile show,
  since the start, the NAT options on, the LowID sessions, the `0x9F` keepalives accepted and ignored with their
  proofs (accepted, from another IP, bad, replayed), the `0xA6` callback requests by outcome, the hole punch (`0x61`
  pairs, `0x62` failures, `0x37` bridges) and the cross-server callback. They are also in the overview API
  (`natTraversal`).

### Upgrade notes

- No database change, no new parameter. The server version announced to clients stays 17.18.
- Relay mode: the relay and the relay server can be upgraded in either order. A relay server before 1.7.2 drops the
  files without a source that a 1.7.2 relay posts; a 1.7.2 relay server keeps the files of an older relay as before.
