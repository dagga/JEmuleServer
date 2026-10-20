## JEmuleServer 1.2.14

- **The database is reopened after a fatal error.** An OutOfMemoryError makes H2 close the database; the server then
  kept running with it closed, so every file published or found afterwards was lost and the database circuit breaker
  opened ("CircuitBreaker 'database' is OPEN"). The server now recognizes a closed database (closed store, out of
  memory, broken connection), reopens it and runs the failed operation again, at most every 10 seconds, and logs it:
  writes resume without a restart. The Status view of the web panel counts the reopenings (`databaseReopens`).
- **Heap warning at startup.** Without options the JVM takes a quarter of the RAM: about 240 MB on a 1 GB server,
  too little for a few hundred thousand files plus the Kad and server searches. The startup log now gives the maximum
  heap and warns when it is below about 256 MB plus 400 bytes per indexed file, with the option to add. The Status
  view shows `heapMaxMb` next to `heapUsedMb`.
- **`-XX:MaxRAMPercentage=75` in the packages.** The .deb and .rpm launchers now pass it, as they pass
  `-XX:ThreadPriorityPolicy=1`; add both to a manual `java -jar` launch.
