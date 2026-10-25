## JEmuleServer 1.2.25

- **Startup heap warning recalibrated.** It still assumed 360 bytes of heap per file and warned on the production
  server (1,521,140 files in a 600 MB heap) although the index takes 365 MB there with 1.2.24. It now compares the
  old generation with about 250 bytes of live heap per file, of which the heap guard lets in 80%, and gives the number
  of files the guard will let in.
- **Index capacity tile.** It shows the fuller of its two limits and now says which: "limited by the heap" or
  "limited by maxFiles", with both percentages (in production: maxFiles 51%, heap guard 83%).

No database change.
