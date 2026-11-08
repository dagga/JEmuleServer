## JEmuleServer 1.6

### Improvements

- **Admin web panel rewritten as a static Next.js app.** Every view and action of the former page is there, with the
  same API, and a new design: tables scroll inside their box with a sticky header, sortable columns, long values cut
  with an ellipsis (full text on hover), numbers right-aligned; light and dark themes follow the system; it fits a phone.
  The app is built by Gradle (the `com.github.node-gradle.node` plugin downloads Node and npm, no Node needed on the
  machine; the first build needs access to nodejs.org and the npm registry) and packaged in the JAR, served by the
  admin HTTP server with a script CSP made of the hashes of each page's inline scripts. `npm run dev` in `admin-ui/`
  runs it against a running server for UI work.
- **Kad word searches for the index.** The other words of a client's Kad search, of at least 5 characters (5 at most,
  longest first, never one the child abuse filter matches), are searched alone in the background, to bring more files
  to the index. The client's answer does not change: it never waits for them nor gets their files. At most 2 run at
  once and 20 start a minute, each word once per 10 minutes; the overview counts them (`kadWordSearches`).
- **15-minute cache of the remote searches.** A search played on Kad or on the other eD2K servers within the last
  `remoteSearchCacheMinutes` (default 15, 0 disables it, applied at once) is not played again: its clean results are
  returned, without network traffic nor new ingestion; the same search still running is joined. The key is the
  normalized query (case, word order of AND/OR), so two clients writing it differently share it. Bounded to 2,000
  searches and 30,000 files per channel. Hits, misses and joins are in the overview.
- **Banned eD2K servers.** The administrator bans a server by its IPv4 address (Servers view, `POST /api/servers/ban`,
  CLI `servers ban`): it is dropped at once, never probed, listed, searched or asked for sources again, its UDP server
  traffic is ignored and its TCP sessions are refused. A banned relay server turns relay mode off until it is unbanned.
  The bans are kept in the `banned_servers` table.
- **File name cleaning.** Rules (a text, without case) give altered files their original name back: every group
  between brackets holding the text is removed, then the spaces are tidied, the extension never changed. The rule
  `emule-qt.org` is written at the first start: it turns `[kad emule-qt.org] Song.mp3`, `[torrent emule-qt.org] ...`
  or `Book [kad emule-qt.org].epub` back into `Song.mp3` and `Book.epub`. The rules apply to every file coming in
  (all channels) and to every result sent to a client; an admin action renames the files already indexed, in the
  index and the database, in the background. New "Name cleaning" view and `/api/namecleaning` endpoints; the rules
  are kept in the `name_cleaning_rules` table.

### Upgrade notes

- Two tables are created at the first start (`banned_servers`, `name_cleaning_rules`), on H2 and MySQL alike.
- The files already indexed keep their altered names until the "Apply to existing files" action of the Name cleaning
  view is run.
