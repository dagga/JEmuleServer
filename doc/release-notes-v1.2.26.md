## JEmuleServer 1.2.26

- **Child abuse searches never leave the server.** A search whose terms match the child abuse filter, from a client
  (`OP_SEARCHREQUEST`) or a UDP global search, now gets no result and is sent neither to Kad nor to the other eD2K
  servers. Until 1.2.25 a client's search was forwarded to Kad and to the servers as is (only its results were
  screened). These searches are counted on the Overview.
- **Last check before the client.** Every batch of search results is checked against the filter before it is sent,
  whatever brought the files; the local search also skips a file indexed before the keywords were reloaded.

- **Java 25.** The code is compiled for Java 25 (Gradle toolchain, downloaded when missing; CI and release on
  Temurin 25), which the release packages already bundled. The JAR now needs Java 25: on Java 21, a client thread
  blocked in a `synchronized` block held its carrier thread, which on one CPU stalled every client (fixed in Java 24,
  JEP 491). Mockito 5.20.0 for the tests.
- **Reference server sizing.** `-Xmx650m`, `-XX:+UseCompactObjectHeaders` (341 MB instead of 355 MB of live heap for
  the 1,521,140 files of production) and `-XX:MarkSweepDeadRatio=0` (the heap guard no longer counts the dead space a
  full collection leaves), and `maxFiles=1850000`, the heap guard's estimated capacity.

The names the filter rejects are still recorded in `banned_names` (1.2.25) and never sent to a client. No database
change.
