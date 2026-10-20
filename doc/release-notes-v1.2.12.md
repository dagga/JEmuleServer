## JEmuleServer 1.2.12

- **Global searches populate the database through Kad.** Clients of other servers send their global searches
  (`OP_GLOBSEARCHREQ`) to every server of their list. They were only answered from the local index; with the new
  `kadForkGlobalSearches` setting (default `true`) they are also searched on Kad in the background, and the clean
  files found are ingested like those of the server's own clients. The requester still gets the local answer only, at
  once: a UDP source address can be forged, so no late answer is sent.
- **Kad only, to avoid loops.** These searches are never forwarded to other eD2K servers: a server forwarding global
  searches the same way could send them back, and the two servers would loop.
- **Limits.** A UDP request costs nothing to forge, so an identical query is searched once per 30 minutes, at most 20
  global searches are searched per minute in all and 3 per source IP, and queries matching the child abuse filter are
  never searched. The Kad flood guard applies as for any search.
- **Monitoring.** Each global search is logged with its source and outcome
  (`UDP global search from 203.0.113.5: Name:linux (12 local results, Kad: forwarded)`), and the `searches` admin
  command and the Searches view of the web panel count the global searches received, searched on Kad, skipped
  (duplicate, no keyword, Kad off) and refused (rate limits, child abuse filter).
