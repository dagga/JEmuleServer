# Configuration

[`server.properties`](../server.properties) is the complete, commented configuration reference and its defaults.
Options not set in that file use the defaults built into the server. Keep runtime configuration separate from
[`TODO.md`](../TODO.md), which tracks implementation work.

## Common settings

| Area | Options | Purpose |
|---|---|---|
| Network | `port`, `publicIp`, `maxPacketSize`, `tcpKeepAliveTimeoutInSeconds`, `heartbeatIntervalSeconds`, `lowIdCheckTimeoutSeconds` | Listener, advertised address, packet size and connection/liveness behavior. |
| Capacity | `maxUsers`, `maxFiles`, `maxFilesPerUser`, `maxSourcesPerFile`, `maxSearchResults` | Limits for clients, indexed files, published files, source replies and search results. |
| Storage | `databasePath` | H2 database location. |
| Filtering | `ipFilterPath`, `fakeFileDetectionEnabled`, `childAbuseKeywordsPath` | Optional IP list, fake-file heuristics and the child-abuse keyword list. The child-abuse filter cannot be disabled. |
| Protection | `floodMaxRequestsPerSecond`, `cbFailureRateThreshold`, `cbMinimumNumberOfCalls`, `cbWaitDurationInSeconds` | Per-IP flood threshold and database circuit-breaker behavior. |
| Server federation | `serverPeers` | Comma-separated IPv4 seed endpoints (`host:TCP-port`). Candidates are verified before they are used for gossip or searches. |
| Kademlia | `kadEnabled`, `kadPort`, `kadBootstrapFromClients`, `kadMaxRoutingContacts`, `kadForkSearchEnabled`, `kadSearchTimeoutSeconds`, `kadAutoIngestToH2`, `kadMinSourcesForIngest` | Embedded Kad node, search forwarding, bootstrapping and result ingestion. |

Use `server.properties` for option defaults and additional details. Relative file paths are resolved from the process
working directory.

## Administration

The terminal CLI is available when the server has an interactive console. For service deployments:

- `adminPort` optionally exposes the CLI on a TCP port bound to `127.0.0.1`; it is disabled by default (`0`).
- `adminWebPort` enables the HTTP dashboard on loopback, defaulting to `8080`; set it to `0` to disable the panel.

Neither interface has password authentication. Any local user who can access loopback can administer the server. Do not
forward or proxy these ports to untrusted networks. The web panel supports status, client search, verified peers,
filters, heuristic potential-bot matches, persistent hash blocks, and a runtime fake-file-detection toggle. That toggle
is not written back to the configuration file.
