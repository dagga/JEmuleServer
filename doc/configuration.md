# Configuration

[`server.properties`](../server.properties) is the complete, commented configuration reference and its defaults.
Options not set in that file use the defaults built into the server. Keep runtime configuration separate from
[`TODO.md`](../TODO.md), which tracks implementation work.

## Common settings

| Area | Options | Purpose |
|---|---|---|
| Network | `port`, `publicIp`, `maxPacketSize`, `tcpKeepAliveTimeoutInSeconds`, `heartbeatIntervalSeconds`, `lowIdCheckTimeoutSeconds` | Listener, advertised address, packet size and connection/liveness behavior. |
| Capacity | `maxUsers`, `maxFiles`, `maxFilesPerUser`, `maxSourcesPerFile`, `maxSearchResults` | Limits for clients, indexed files, published files, source replies and search results. |
| Publication | `offerFilesV1Enabled`, `offerFilesBatchMax`, `offerFilesMinIntervalMs`, `offerFilesGlobalRecordsPerSec` | Opt-in [OFFERFILES v1](offerfiles-v1.md): batch size and pace answered to clients that ask for them at login, and enforced on those clients only. |
| Storage | `databasePath` | H2 database location. |
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
