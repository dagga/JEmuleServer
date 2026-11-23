# Configuration

[`server.properties`](../server.properties) is the complete, commented configuration reference and its defaults.
Options not set in that file use the defaults built into the server. Keep runtime configuration separate from
[`TODO.md`](../TODO.md), which tracks implementation work.

## Common settings

| Area | Options | Purpose |
|---|---|---|
| Identity | `serverName`, `serverDescription` | Name and description shown in the clients' server list (empty = the JEmuleServer defaults, with the version; at most 255 bytes in UTF-8). Applied without restart, also from the web panel settings: new logins and the clients refreshing their server list get them, the connected clients keep the name received at their login. |
| Login messages | `welcomeMessage`, `loginMessage` | Messages sent at login and shown in the client's server info. `welcomeMessage` replaces the default "Welcome to JEmuleServer version {version}\nYour ID is: {id}" (empty = that text); `loginMessage` follows it, for rules or announcements (empty = none). `{version}` and `{id}` are replaced, `\n` starts a new line; at most 2048 bytes in UTF-8, no line starting with "server version". Applied without restart, also from the web panel settings, to new logins. |
| Network | `port`, `publicIp`, `maxPacketSize`, `tcpKeepAliveTimeoutInSeconds`, `heartbeatIntervalSeconds`, `lowIdCheckTimeoutSeconds` | Listener, advertised address, packet size and connection/liveness behavior. |
| NAT traversal | `natCallback`, `natCallbackToken`, `natHolePunch`, `natCallbackCrossServer` | Opt-in Lugdunum NAT callback between two LowID clients of this server (default `false`, hot, new logins): odd LowIDs and a 24-byte `OP_IDCHANGE` for clients setting `0x1000` in `CT_SERVER_FLAGS`, UDP `0x9F` keepalives and `0xA6` requests, notice to the target by TCP `0x37` or UDP `0xA7`. With `natCallbackToken` (default `false`), a session token in `OP_SERVERIDENT` (`0xAC` with `0xA9`) lets a `0x9F` proven with HMAC-SHA256 and a rising counter count from any address (aMule master a6d6290be0, `docs/NAT_T_Protocol.md`). With `natHolePunch` (default `false`), ed2k-server's LowID hole punch: TCP flags `0x20000` and `0x100000` in `OP_IDCHANGE`, TCP `0x60` answered by `0x61` to both sides or `0x62`, the hole-punch extension's `0x37` wake between clients setting `0x100000` with `0x1000`, and a `0x37` bridge to a target with `0x1000` only that sent no `0x9F` (Lugdunum-only clients, which ignore `0x61`). `natCallbackCrossServer` (default `false`, hot, needs `natCallback`) also wakes such a client for a client of another server, announced with `0x100000` in the UDP flags and limited per source IP and per target; see [Cross-server NAT callback](nat-callback-cross-server.md). |
| IPv6 sources | `ipv6Sources` | Off by default, hot (new logins). Publishes the IPv6 address a LowID client proved reachable (dialled back, user hash checked) as a source to the clients that read IPv6 source records; see [IPv6](ipv6.md). Off when the JVM has no IPv6 (`java.net.preferIPv4Stack`). |
| Firewall check | `obfuscatedFirewallCheck` | On by default, hot (new logins). The HighID test and the IPv6 dial-back connect with eMule's client obfuscation first when the client announces the crypt layer, then plain if the handshake fails and the client does not require the layer. `false`: plain only, and a client that requires the layer is not reached over IPv6, nor at all with eMuleQt's strict option. See [IPv6](ipv6.md). |
| Capacity | `maxUsers`, `maxFiles`, `maxFilesPerUser`, `maxSourcesPerFile`, `maxSearchResults` | Limits for clients, indexed files, published files, source replies and search results. |
| Index | `indexRefreshSeconds` | Seconds between two refreshes of the file index (default `10`, 1 to 600, applied without restart): new files, removals and source counts reach the searches at most that late. Each refresh following source count changes rewrites the source counts of every index segment holding a changed file, so a shorter period costs more CPU on a large index. Lookups by hash see the writes at once. |
| Source counts | `sourceCountMaxAgeDays` | The source count reported for a file by Kad, another server, the index update or the relay feed is what the networks saw lately, not the most they ever saw: the first report of a day replaces the count reported before, the next ones of the day only raise it. The online sources of this server raise the count the searches sort on, but are never saved and leave with their clients. A count nobody reported for `sourceCountMaxAgeDays` days (default `30`, hot; `0` = kept until the file is purged) is reset to 0 by the daily file purge, in the database and the index; see [Source counts](../README.md#source-counts). |
| Synthetic hashes | `bannedHashPrefixes` | Hexadecimal hash prefixes (comma-separated, 6 to 32 digits) of files that exist on no eD2K network, made up by a gateway: refused by every ingestion channel (clients, Kad, servers, index update, relay feed) and never sent in a search result. Default `ED2B01`, the files of the emule-qt.org bridges (torrent `ED2B0111`/`0112`/`0113`, usenet `ED2B0133`, web `ED2B0153`), brought by its eNode server with made-up source counts and published by eMule-Qt clients; empty bans none. Hot. The files already indexed are removed with the console `files purge-prefix <hex>` or `POST /api/files/purge-prefix`. |
| Client bans | `bannedClientNicks`, `banClientsPublishingBannedHashes` | Clients refused besides the IP filter (which its daily update rewrites). `bannedClientNicks`: login nicknames refused, comma-separated, each compared whole and without case (default empty; e.g. `https://emule-qt.org`, the nickname of eMule-Qt clients): the connection is closed before anything is sent. `banClientsPublishingBannedHashes` (default `true`): a client offering a file of a banned hash prefix gets none of the files of that offer indexed, is disconnected and its IP refused for 24 hours, in memory (a restart lifts it); `false` only skips the file. Both hot; the refusals are counted in `/api/status` (`clientNickLoginsRefused`, `clientPublishersBanned`, `clientConnectionsRefused`, `clientIpsBanned`). |
| Retention | `fileRetentionDays` | Days a file nobody published nor found is kept (default 180, hot; `0` = never purged). Once a day, the files whose last-seen day is older are deleted from the database and the index, in small batches; see [File retention](../README.md#file-retention). |
| Publication | `offerFilesV1Enabled`, `offerFilesBatchMax`, `offerFilesMinIntervalMs`, `offerFilesGlobalRecordsPerSec` | Opt-in [OFFERFILES v1](offerfiles-v1.md): batch size and pace answered to clients that ask for them at login, and enforced on those clients only. |
| Storage | `databasePath`, `databaseType`, `databaseUrl`, `databaseUser`, `databasePassword`, `databaseImportFromH2` | H2 database location, or a MySQL database (local or remote) and the import of the H2 one at the first start. The password is never shown in the web panel; it can come from `JEMULE_DATABASE_PASSWORD`. |
| Filtering | `ipFilterPath`, `fakeFileDetectionEnabled`, `childAbuseKeywordsPath` | IP filter defaults to `./ipfilter.dat` and is refreshed from the HTTPS eMule Security ZIP source at startup and daily; fake-file heuristics and the child-abuse keyword list. The child-abuse filter cannot be disabled. |
| Protection | `floodMaxRequestsPerSecond`, `cbFailureRateThreshold`, `cbMinimumNumberOfCalls`, `cbWaitDurationInSeconds` | Per-IP flood threshold and database circuit-breaker behavior. |
| Server federation | `serverPeers`, `serverMetUrl` | Comma-separated IPv4 seeds and optional HTTPS `server.met` source loaded at startup. Downloaded endpoints are merged with existing candidates, not used to replace them; peers are verified before gossip or searches. |
| Index update | `serverIndexUpdateEnabled`, `serverIndexUpdateMaxPerHour`, `serverIndexUpdateMaxPerDay`, `serverIndexUpdateMinSources` | Searches on the eD2K index peers (the verified eD2K servers), logged in as a client, to index the files they know. |
| Kademlia | `kadEnabled`, `kadPort`, `kadBootstrapFromClients`, `kadMaxRoutingContacts`, `kadForkSearchEnabled`, `kadSearchTimeoutSeconds`, `kadAutoIngestToH2`, `kadMinSourcesForIngest`, `kadNodesUrls`, `kadForkGlobalSearches`, `kadSourceSearchEnabled`, `kadSourceSearchMinLocal`, `kadSourceSearchesPerMinute` | Embedded Kad node, search forwarding, bootstrapping (including nodes.dat downloads), result ingestion and source searches for the clients. |

Use `server.properties` for option defaults and additional details. Relative file paths are resolved from the process
working directory.

## Administration

The terminal CLI is available when the server has an interactive console. For service deployments:

- `adminPort` optionally exposes the CLI on a TCP port bound to `127.0.0.1`; it is disabled by default (`0`).
- `adminWebPort` enables the HTTP dashboard on loopback, defaulting to `8080`; set it to `0` to disable the panel.

Neither interface has password authentication. Any local user who can access loopback can administer the server. Do not
forward or proxy these ports to untrusted networks.

The web panel supports status, client search, verified peers, filters, heuristic potential-bot matches, persistent hash
blocks, client disconnection, and editing of `server.properties`. The settings page lists every parameter with its
scope: `hot` values are applied immediately, `admin` values rebuild the admin interfaces, and `restart` values are
written to the file and shown as "after restart" while the server keeps using its current value. Saving writes the changed keys back to the configuration file
without touching its comments or key order; **Reload from file** applies changes made by hand. Invalid values are
rejected and leave the running configuration unchanged. Changes to `adminAllowedIps` / `adminAllowedMacs` take effect
immediately.
