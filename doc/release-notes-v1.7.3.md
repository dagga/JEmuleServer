## JEmuleServer 1.7.3

### Fixes

- **Source counts are no longer a high-water mark (#145).** A file kept the highest source count ever reported for
  it, by Kad, another server or the server's own clients, in the index and the database. Search results could then
  announce 99 sources for a file that nobody shared any more, and the client found none. Now:
  - the first count reported for a file on a given day replaces the stored one, even when lower; later reports of the
    same day only raise it;
  - the database keeps only the count reported by Kad, the other servers or the relay. The server's own clients still
    raise the count the searches sort on and announce, and it falls back to the reported one when they leave;
  - the daily file purge job resets to 0 the count of the files nobody reported for `sourceCountMaxAgeDays` days.

### Changes

- **Banned hash prefixes (#145).** The emule-qt.org servers ("eNode", "BiG BanG eNode", "Razorback 4") and the eMule-Qt
  clients share torrent, usenet and web downloads under made-up hashes that all start with `ED2B01`, announced with
  invented counts (99 sources, 99 complete). No eD2K client can download them. The new hot setting
  `bannedHashPrefixes` (default `ED2B01`) refuses those files on every channel (client publications, Kad, server
  searches, index update, relay) and leaves them out of the search results. Console `files purge-prefix <hex>` and
  `POST /api/files/purge-prefix` remove the files of a prefix already indexed.
- **Client bans (#145).**
  - `bannedClientNicks` (hot, empty by default) refuses the logins with one of the listed nicknames, matching the
    whole name and ignoring case.
  - `banClientsPublishingBannedHashes` (hot, default true) disconnects a client offering a file of a banned hash prefix
    and refuses its IP for 24 hours. None of the files of that offer is indexed. The ban is held in memory: a restart
    lifts it.
  - `/api/status` counts the refusals: `clientNickLoginsRefused`, `clientPublishersBanned`, `clientConnectionsRefused`,
    `clientIpsBanned` and `bannedHashFilesRefused`.

### Upgrade notes

- No database change. New parameters: `sourceCountMaxAgeDays` (default 30, 0 = off), `bannedHashPrefixes` (default
  `ED2B01`), `bannedClientNicks` (empty), `banClientsPublishingBannedHashes` (true). The server version announced to
  clients stays 17.18.
- The file purge job now also runs when `fileRetentionDays` is 0 but `sourceCountMaxAgeDays` is not.
- The files already indexed with a banned prefix are no longer returned by searches. To remove them from the index and
  the database, run `files purge-prefix ED2B01` in the console.
- To refuse the eMule-Qt clients by their default nickname: `bannedClientNicks=https://emule-qt.org`.
