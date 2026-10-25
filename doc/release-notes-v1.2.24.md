## JEmuleServer 1.2.24

- **Index capacity for every channel, and a heap guard.** `maxFiles` only applied to the files published by clients:
  Kad and server search results kept being indexed, and the production server reached 1,521,140 files for a
  `maxFiles` of 1,200,000, its old generation 95% full of live data. New files are now refused, whatever brings them,
  at `maxFiles` or while the live heap (measured after the last collection of the old generation) fills 80% of the old
  generation; known files still get their new sources. The Overview's index capacity shows the fuller of the two
  limits, the live heap and the files refused.
- **Smaller index** (merged in #47). File names are kept one after the other in one byte array and the inverted index
  stores its file numbers as variable-length gaps: the production database copy of 828,413 files loads in 224 MB of
  heap instead of about 293 MB.
- **Peer eD2K servers verified again.** All the servers of the server.met were rejected in production although each
  answers correctly: after a long collection pause, the answers to expired probes were read against new challenges and
  rejected the servers until the next restart. A late answer is now ignored, and a rejected server is probed again
  after six hours. Client searches are forked to these servers again.
- **Sources from the other servers.** When a client asks for the sources of a file the server knows few of, the
  verified eD2K servers are asked beside Kad (`OP_GLOBGETSOURCES2`, up to 35 files per datagram, one datagram every
  3 seconds per server at most), and their HighID sources are added to the second `OP_FOUNDSOURCES`. Tested from
  production on 20 popular files: the 16 servers answered all of them, with 1,468 HighID sources.
- **Adaptive source search quota.** `kadSourceSearchesPerMinute` now defaults to 60 and is a maximum: the quota is
  halved when the flood limits keep a source request from a Kad node (once a minute at most, never below 5), and climbs
  back by a tenth of the maximum after ten minutes without refusal. The Overview shows the quota and the refusals.
- **Banned file names instead of banned hashes.** The administrator bans file names, compared in lower case,
  whatever the hash; banning a name removes the files indexed under it. The web panel's Blocks view lists, bans and
  unbans names and exports them as `banned-names.txt`; the CLI has `ban <name>`, `unban <name>` and `blocks`. Files
  rejected by the child abuse filter are no longer remembered by hash: the filter rejects them each time they come
  back.

**Database change:** schema version 3. At the first start, the `banned_hashes` table is dropped (376,929 rows in
production) and `banned_names` is created; the migration takes under a second. The hashes cannot be turned into names,
the banned files having been deleted. An older version started on this database recreates an empty `banned_hashes`.
