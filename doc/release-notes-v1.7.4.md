## JEmuleServer 1.7.4

### Fixes

- **Relay mode: the fallback index dictionary leaves the heap (#147).** A server in relay mode keeps its own index
  only as a fallback, searched when the relay server does not answer in time. Its word dictionary and its media tag
  values were still read into the heap at startup: about 1.2 million words for an index of 3.4 million files, a third
  of the old generation of a small relay. With the online sources of its clients on top, such a relay ran out of
  memory at the busy hours (`OutOfMemoryError: Java heap space`), after minutes of back-to-back full GCs. They are now
  released once the relay mode starts, and read again from the index before the first local search, or before a
  file is written when the relay mode ends.

### Upgrade notes

- No database change, no new parameter. The server version announced to clients stays 17.18.
- Only servers in relay mode (`relayServer`) are concerned. Their first fallback search after a start takes a few
  seconds more, the time to read the dictionary again.
- On a small relay, a smaller young generation also leaves more room to the old one: with `-Xmx560m`, `-Xmn96m`
  instead of `-Xmn160m` gives 464 MB to the old generation instead of 400 MB.
