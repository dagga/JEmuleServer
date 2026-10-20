## JEmuleServer 1.2.10

- **More results from Kad.** A Kad node returns at most 300 files per `KADEMLIA2_SEARCH_KEY_REQ`, starting at the
  position given in the request; eMule clients always ask for the first 300. When a node fills a page, the server now
  asks it for the next ones (start positions 300 and 600, up to 900 files per node). On the live network, a search
  for `linux` went from 906 to 2,039 distinct files, `matrix` from 986 to 1,703.
- **Multi-word Kad searches.** The keyword whose index is queried is now the longest word every result must
  contain, as eMule does, instead of the first word of 3 characters or more. The whole client query is sent along
  (restrictive search), so the nodes only return matching files and their 300-file limit applies to those. A search
  for `ubuntu desktop` now returns 259 matching files, against 174 before, while receiving 2.5 times less data.
  Words are split as Kad indexes them (eMule's keyword separators, at least 3 UTF-8 bytes).
- **No more bans by Kad nodes.** eMule nodes drop the requests of an IP above a per-minute limit (3 keyword searches,
  10 node lookups, 3 hellos...) and ban it above five times that limit. All the clients of a server search from its
  single IP, so popular keywords could get the server banned by the nodes that index them. Every Kad request now goes
  through a guard that replays the node's count and does not send what the node would drop: a search moves on to the
  next closest node, and a maintenance ping is postponed. The nodes found for a keyword are also reused for 2 minutes,
  which saves the lookup requests of repeated searches.
- **More results from eD2K servers.** A server answers a UDP global search with its first 10 to 30 matches, and UDP
  has no "More" request. When a server returns a full answer, the search is now repeated on it once per file type
  (Video, Audio, Program, Archive, CD image, Document, Image, Collection), at most 4 times per minute and per server.
  On the live network this returns 3 to 8 times as many files per server (e.g. 241 instead of 30 for `mp3`). Searches
  already restricted to a file type are not split.
- **Up to 1,800 results per client search.** eMule clients ask for more results at most 5 times per search
  (`MAX_MORE_SEARCH_REQ`), so batches of 50 limited a client to 300 files. `OP_SEARCHRESULT` now carries up to 300
  files, as the eMule servers do.
- **Kad and server results reach warm searches.** When local results exist, they are still sent at once, now with the
  "More" button enabled while the Kad and eD2K server searches run; their files that were not already sent follow on
  `OP_QUERY_MORE_RESULT`. Before, they were only stored for the next searches. A request for more results that
  arrives before the remote answers waits for them, at most the search timeout, instead of using up one of the
  client's 5 requests with an empty answer, and a final empty answer is sent when nothing more was found.
