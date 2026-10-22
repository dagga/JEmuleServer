## JEmuleServer 1.2.19

- **Overview in the admin web panel.** The panel opens on an Overview: the number of indexed files with its change
  over 24 hours and 7 days, the index capacity against `maxFiles`, and tiles for the connected clients and their
  LowID share, the heap, the verified eD2K servers, the Kad and eD2K server searches and the files they found, the
  banned hashes, the database and the uptime. A meter or a status, with an icon and a label, flags what nears its
  limit. The figures refresh every 30 seconds.
- **Curves over a rolling month.** The indexed files and the connected clients are sampled every 5 minutes and kept
  for 31 days in a new `metrics_history` table, so that the curves survive a restart; the Overview draws them over
  24 hours, 7 days or 30 days, with a crosshair and a tooltip (also reachable with the arrow keys) and a table of
  daily values. Over long ranges the samples are averaged per point of the curve; a stop of the server shows as a
  gap. New endpoints: `/api/overview` and `/api/history?days=1..31`.
- **Redesigned panel.** Tabs, cards, tables with aligned numbers, and the light or dark theme of the system.
- **Smaller H2 cache, one HTTP client.** H2 keeps 4 MB of database pages in the heap instead of 16 MB (the database
  is read once at startup, then only written to), and the server.met, IP filter and nodes.dat downloads share one
  HTTP client instead of keeping a thread each.

The new table is created at startup if missing; nothing else changes in the database, and 1.2.18 can still open it.
