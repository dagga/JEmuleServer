## JEmuleServer 1.2.23

- **Kad sources for the clients (opt-in).** When a client asks for the sources of a file (`OP_GETSOURCES`) and the
  server knows fewer than `kadSourceSearchMinLocal` (10), it answers at once with them as before, then searches Kad for
  the sources of that file (`KADEMLIA2_SEARCH_SOURCE_REQ` to the nodes closest to its hash) and sends the new ones in a
  second `OP_FOUNDSOURCES`, which eMule and aMule accept at any time for a file they download. Clients without Kad get
  sources they could not get at all; clients with Kad get them every 15 minutes instead of every hour.
- **Firewalled Kad sources made usable.** A firewalled Kad source can only be reached through Kad (its buddy or a Kad
  callback), which an eD2K client cannot use from its server. When that source is also connected to this server, it
  is sent with its ID here, LowID included, and the requester reaches it through the server's callback.
- **Bounded.** The sources Kad gave for a file are reused for 20 minutes (1,000 files at most); a client starts at most
  one search per file every 15 minutes and 20 an hour, all clients together `kadSourceSearchesPerMinute` (30). Private,
  filtered and port-less sources are dropped.
- **Overview.** A tile counts the Kad sources sent to the clients, the answers, the searches and the cache hits;
  `/api/overview` gives the details under `kadSources`.

Off by default: `kadSourceSearchEnabled=true` turns it on, at once from the Settings view. No database change.
