## JEmuleServer 1.2.18

- **Compact file index: a quarter less memory.** The indexed files were held as one object per file (its metadata,
  its hash as a 32-character string, its tag list, a map entry), about 200 bytes each besides the name. They are now
  stored by number in parallel arrays: the MD4 hash as two 64-bit values, the size, the type, the source count and
  the name, about 60 bytes per file. Only the files with online sources, media tags or an unusual hash get an object
  of their own. The inverted index refers to the files by number. On a 690,000-file database the heap used after
  startup drops from 363 MB (1.2.17) to 272 MB. Searches read the index in parallel; adding or removing a file locks
  it for that file only.
- **A failing Kad search no longer blocks the eD2K server search.** A search of a connected client is answered from
  the local index and sent to Kad and to the verified eD2K servers; the two are now started independently, so that
  an error while starting the Kad search cannot keep the search from the servers.

No database change: 1.2.18 opens the database of 1.2.16 and 1.2.17 as is, and can be rolled back to them.
