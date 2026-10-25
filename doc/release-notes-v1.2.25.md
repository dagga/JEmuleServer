## JEmuleServer 1.2.25

- **New files indexed again.** 1.2.24 kept the `maxFiles` read at startup: raised from 1,200,000 to 3,000,000 in
  production right after the start, with 1,521,140 files indexed, the index stayed full and refused every new file.
  A new `maxFiles` now applies at once.
- **Names rejected by the child abuse filter recorded.** Each file name the filter rejects (client publication, Kad or
  server search result, database purge at startup) is recorded once in `banned_names` with the reason "Child abuse
  material", and comes with the administrator's bans in the Blocks view and the `banned-names.txt` export. These names
  stay in the database, not in memory: the filter rejects them anyway. The export streams them from the database.
- **Startup heap warning recalibrated.** It still assumed 360 bytes of heap per file and warned on the production
  server (1,521,140 files in a 600 MB heap) although the index takes 365 MB there with 1.2.24. It now compares the
  old generation with about 250 bytes of live heap per file, of which the heap guard lets in 80%, and gives the number
  of files the guard will let in.
- **Index capacity tile.** It shows the fuller of its two limits and now says which: "limited by the heap" or
  "limited by maxFiles", with both percentages (in production: maxFiles 51%, heap guard 83%).

No database change.
