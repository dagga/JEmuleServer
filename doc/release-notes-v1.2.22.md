## JEmuleServer 1.2.22

- **The database file no longer grows while the server runs.** Since 1.2.16 the file was only compacted at startup,
  because a compaction running during the startup load had filled the disk. Meanwhile the writes of the running
  server made it grow: on the reference server, from 200 MB to 1.4 GB in 15 hours. Once the startup load is over, the
  server now checks the file every 10 minutes and, when less than 50% of it is used, compacts it the way the startup
  does: the database is closed for the few seconds of the rewrite and the writes wait meanwhile. The sizes before and
  after are logged.
- **Reference server sized for 1,200,000 files.** With `maxFiles=1200000`, the README recommends `-Xmx600m -Xmn48m`
  on 1 GB of RAM; a 1,200,000-file benchmark loads in that heap on one CPU.

No database change: 1.2.22 opens the database of 1.2.20 as is, and can be rolled back to it.
