## JEmuleServer 1.2.15

- **Fewer database writes.** The in-memory index holds every MD4 hash of the database and now serves as a cache: a
  file whose hash is already known is no longer written again when a Kad or server search finds it, or when a client
  publishes it. Only unknown hashes are written whole; a higher source count is written alone.
- **Source counts in their own table.** `file_sources` holds the source count of each file: updating it writes one
  narrow row instead of rewriting the file and all its tags.
- **Less memory per file.** Only the tags searches filter on are kept, in memory and in the database (source count,
  artist, album, title, length, bitrate, codec, rating); the others (file name, size, type, format...) duplicated the
  fields of each file. Tag names are shared instead of one string per tag. On a 690,000-file database the heap used
  after startup drops from 481 MB to 374 MB.
- **Database migration at startup.** A database written by an older version is migrated at the first start, in
  committed batches, with its progress on the console; it is then compacted (861 MB to 53 MB on the test database).
  Every file is kept. Rolling back to an older version needs the database backup taken before the upgrade, since
  older versions do not read the source counts from `file_sources`.
- **Identical remote searches limited.** The same query is sent on Kad or to the eD2K servers at most 3 times in 10
  minutes; afterwards the search is answered from the database, which already holds the files found. The Searches
  and Kad views count the queries not sent.
