## JEmuleServer 1.2.8

- Download a Kad `nodes.dat` contact list at startup from the two configured `kadNodesUrls` sources (eMule Security
  and alldivx). The list is refreshed daily like the IP filter, cached as `<databasePath>.nodes.dat`, and used as a
  fallback when every source is unreachable. Downloaded contacts are used as bootstrap targets only: the routing
  table is filled by their answers, in wire byte order.
- Parse the `nodes.dat` versions 1, 2 and 3 (bootstrap edition), reject the obsolete version 0, ignore Kad1
  contacts and non-public addresses, and drain the bootstrap queue as answers arrive.
- Show the nodes.dat sources, loaded contact count and last refresh in `kad status` / `kad stats`.
- Edit every `server.properties` parameter from the admin web panel and the CLI (`settings <key> <value>`), with
  per-parameter validation and hot reload (`settings reload`) when the parameter allows it. Parameters that need a
  restart (ports, database, Kad) are written to the file and shown as pending; the running server keeps its current
  value until it is restarted. Relative paths are resolved against the data directory, as at startup.
- Kick a connected client from the admin interfaces (Kick button, `POST /api/kick`, `kick <id|nickname|ip>`).
- Reload the IP filter and the child-abuse keywords without restarting (`filters reload`, `POST /api/filters/reload`);
  `childAbuseKeywordsPath` is now applied immediately when it changes.
- Unban a hash from the admin interfaces (`unban <hash>`, `POST /api/unblock`).
- Align the embedded Kad node with the eMule Kad2 wire framing (`OP_KADEMLIAHEADER` `0xE4`, response opcodes
  `BOOTSTRAP_RES` 0x09 / `HELLO_RES` 0x19 / `HELLO_RES_ACK` 0x22 / `RES` 0x29), serialize contact IPv4 addresses
  least-significant byte first like `nodes.dat`, announce the local TCP port in bootstrap and hello, and carry the
  sanity-check ID in `REQ`. A bootstrap answer now inserts the responder as well as the returned contacts, so the
  routing table fills up and the node reaches the CONNECTED threshold.
- Merge the Kad contacts into the Peers view (`/api/peers` and the CLI `peers`), alongside the verified eD2K server
  peers, so the interface is no longer empty while Kad bootstraps.
