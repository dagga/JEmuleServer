## JEmuleServer 1.2.13

- **Thread priorities.** Every thread of the server now belongs to one of five tiers, highest first: admin interfaces
  (console, admin port, web panel), client connections (TCP and UDP), searches (Kad and eD2K server forks), ingestion
  (indexing and database writes), regular operations (Kad maintenance, server gossip polling, IP filter and nodes.dat
  updates and loading). Before, almost everything ran on the same virtual threads: on a one-vCPU server they share a
  single carrier and are not preempted, so a long search, ingestion or update could hold back the clients and even
  the admin panel. Searches, ingestion and regular operations now run on platform threads of lower priority, the admin
  interfaces on platform threads of higher priority.
- **Ingestion never holds back a client or a search.** The files found by Kad and eD2K server searches are delivered
  to the search at once, and indexed and stored afterwards by a single ingestion thread, in order. The database writes
  of the files clients publish also run on that thread: a client offering thousands of files no longer waits for the
  database. If more than 500 ingestion tasks wait, new search results are not ingested (they are still delivered);
  published files are always stored. Pending writes are completed before the database closes at shutdown.
- **`-XX:ThreadPriorityPolicy=1`.** On Linux the JVM ignores thread priorities without this option. The .deb and .rpm
  launchers and `./gradlew run` now pass it; add it to a manual `java -jar` launch. Lowering priorities works for any
  user; raising the admin tier above the clients needs root or `CAP_SYS_NICE` (commented in the systemd unit). The
  startup log says whether priorities are applied.
- **Global searches in the Searches view.** The "UDP global searches (Kad)" row now counts the distinct clean files
  their Kad searches found, all handed over to ingestion; their Kad traffic is also part of the Kad row.
