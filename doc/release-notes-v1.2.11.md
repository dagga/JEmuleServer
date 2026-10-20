## JEmuleServer 1.2.11

- **eD2K server search results are ingested again.** A result is only kept when it matches the client's query, and
  eMule clients usually restrict the file type. Servers send the type either as an `ED2KFT_*` integer (`2` for video),
  which was ignored, or not at all: the type filter rejected every server result, and none was ever ingested. The
  integer type is now read, and a file announced without type gets the type of its extension, as eMule does
  (`g_aED2KFileTypes`; archives and CD images count as "Pro"). On the live network, a `matrix` search restricted to
  videos over 100 MB now keeps 88 of the 90 server results, against none before.
- **eD2K server status kept in the Servers view.** After a server's status and description, the server gossip asks
  for its server list, which most servers never answer. The timeout of that request erased the status the server had
  just returned, so the users, files, limits and latency of verified servers were empty. Only unanswered status
  probes now count.
