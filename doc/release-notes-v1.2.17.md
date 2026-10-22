## JEmuleServer 1.2.17

Less memory for the same index, for servers with 1 GB of RAM.

- **Banned hashes kept once, in a compact set.** The banned hashes (about 200,000 in production) were held three
  times, as strings in concurrent sets: by the fake file detector, by the Kad search bridge and, for the child abuse
  bans, by the file index; about 22 MB per copy. They are now stored as 128-bit values in open addressing arrays
  (21 to 43 bytes per hash instead of about 110), and the detector and the Kad bridge share the same set.
- **Inverted index trimmed after loading.** The hash arrays of the index words grow by half at a time; the free
  slots left by the load are released once it is done.
- **Lighter source counts.** The files whose only tag is their source count share an empty tag array, and the
  source count tag is no longer cached on each file.

Expected in production: about 75 MB less live heap (about 350 MB instead of 422 MB for 686,000 files and 196,000
banned hashes). On a 1 GB server, `-Xmx480m -Xmn48m` then leaves room for the index to grow.
