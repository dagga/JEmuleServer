## JEmuleServer 1.4.2

### Improvements

- **Faster searches.** The file index stores its files in 2 KB chunks compressed with LZ4 alone, instead of Lucene's
  80 KB chunks with a preset dictionary: a search returning 300 files took most of its time decompressing them, one by
  one, in source count order. On the 2,398,695 files of the reference server, such a search takes 3.8 ms instead of
  5.8 ms (median), for an index of 371 MB instead of 349 MB.

### Fixes

- **No more keyword loop between two servers.** The sessions another eD2K server opens here (its relay and index
  update sessions) were served as clients: their keywords were offered to the index update, so two servers updating
  their indexes from each other sent the same keywords back and forth for ever, and their searches were sent on to
  Kad, the other servers and the relay server a second time. A request from another eD2K server (a configured or
  verified peer, or the relay server) now gets the local answer only; so does a UDP global search it sends, which is
  no longer searched on Kad nor played on the relay server.
- An index this version cannot read (written by another version, or damaged) is rebuilt from the database instead
  of keeping the server from starting.

### Upgrade

The index format changes: the first start of 1.4.2 rebuilds the index from the database once (about the time 1.3 took
to load the files), then the next starts reopen it.
