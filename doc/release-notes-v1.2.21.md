## JEmuleServer 1.2.21

- **The database file no longer grows while the server runs.** Since 1.2.16 H2 did not compact the file in the
  background, because a compaction running during the startup load had filled the disk; the file was only compacted
  at the next start. Meanwhile the writes of the running server made it grow: on the reference server, from 200 MB to
  1.4 GB in 15 hours. Once the startup load is over, the file is now compacted online: in the background by H2, and
  every 10 minutes by the server when less than 80% of it is used, holding the writes for at most 5 seconds. The sizes
  before and after are logged. The startup compaction is unchanged.

No database change: 1.2.21 opens the database of 1.2.20 as is, and can be rolled back to it.
