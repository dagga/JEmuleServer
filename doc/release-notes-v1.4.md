## JEmuleServer 1.4

### New features

- **File index on disk (Lucene).** The known files and the words of their names move from the Java heap to a
  [Lucene](https://lucene.apache.org/) index in the directory `<databasePath>.index`, next to the H2 database (with
  MySQL too), read through mmap. On the 2,398,695 files of the reference server, the index takes 111 MB of heap
  instead of 562 MB, and 348 MB of disk. The searches give the same files as in 1.3, sorted the same way.
- **Restart without rebuilding the index.** A clean stop records the same token in the index and in the database: the
  next start reopens the index at once, instead of reading every file of the database. After a crash or a `kill -9`,
  a restored backup or other child abuse keywords, the start rebuilds the index from the database, in about the time
  1.3 took to load it.
- **Disk guard.** New files are refused, as at `maxFiles`, when the disk of the index has less than 512 MB free; with
  `relayServer` set, the relay server takes over.

### Improvements

- A file written to the index reaches the searches within a second; publishing, `contains` and the source requests
  see it at once.
- The source count of a file is raised to the number of clients sharing it online, so that the searches sort and
  filter on the count they return.
- The heap warning at startup counts about 80 bytes of heap per indexed file instead of 250.
- Lucene's merges and refreshes run at the priority of the ingestion, below the clients and the searches.

### Fixes

- The index lock could be lost: an `OutOfMemoryError` inside a read lock left it held for ever, and every search,
  source request and admin request then waited. The index now uses a lock that cannot be lost, and its file count is
  read without lock.
- Stopping the server no longer waits for ever for admin requests that never end: they are interrupted.
- On SIGTERM, H2 no longer closes its database before the server has written the files waiting for ingestion.

### Upgrade

- The first start of 1.4 builds the index from the database. Count about 150 bytes of disk per file for it, plus the
  512 MB the disk guard keeps free.
- The heap the index needed until 1.3 can be lowered: the RAM left free outside the heap holds the index files in the
  page cache, which speeds the searches up.
- After changing the database by other means than the server, delete `<databasePath>.index` while the server is
  stopped: the next start rebuilds it.
- `java -jar` no longer warns about Lucene's native access (`Enable-Native-Access` in the JAR manifest).
