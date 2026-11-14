## JEmuleServer 1.6.1

### Improvements

- **Searches no longer hold back the clients.** The local index searches requested by clients and the UDP global
  searches now run on a pool of platform threads of the search tier (`max(2, CPUs)` threads), instead of on the
  clients' virtual threads, which are not preempted: on a one-CPU server a search of a few hundred milliseconds held
  the only carrier thread, and every login, ID change and keep-alive waited. The client's thread parks while its
  search runs. The queue is bounded: when it is full a client search gets an empty local answer at once, and a UDP
  global search is dropped (best effort). The UDP receive loops no longer evaluate searches, so status requests (by
  which clients measure the server ping) are answered at once (#98, #99).
- **Index refresh every 10 seconds instead of every second.** Each refresh rewrote the source counts of every touched
  index segment: on a large index this took a third of the server's CPU. The period is the new HOT parameter
  `indexRefreshSeconds` (1 to 600, default 10): new files and source counts reach the searches at most that late (#96).
- **Each search is evaluated once.** The index searches counted their hits before searching them, evaluating every
  query twice; the substring part of the name queries, the most expensive one, was rewritten twice (#97).
- **Kad searches without a thread each.** Kad keyword and source searches run on small bounded pools of their tier,
  their completion scheduled at their deadline, instead of starting a platform thread that slept until it (#101).
- **Kad word searches from 4 characters.** The other words of a client's Kad search are searched alone in the
  background, for the index, from 4 characters instead of 5.
- **Purge of the files not seen for 6 months.** A file published by a client or found by a Kad or server search is
  marked seen for the day (`last_seen`, at most one write per file and per day). Once a day, on the maintenance tier,
  the files not seen for `fileRetentionDays` (new HOT parameter, default 180, 0 = never) are deleted from the
  database, their tags and the index, in small batches; a file seen again or shared by a connected client is kept.
  Not in relay mode. The last run is in `/api/status`, the overview and the console `status` (#103).
- **Quieter logs.** The per-packet and per-request traces (`Processing packet: ...`, files published and searched)
  are logged at DEBUG: about 2,800 lines a minute on a busy server, which hid the warnings (#100).

### Fixes

- **Console `stop`.** The shutdown hook ran a second shutdown while the console one was still closing the index and
  the database: the server was closed twice ("object already closed" errors) and the JVM could halt before the
  pending ingestion was written. The second call now waits for the first one (#102).

### Upgrade notes

- Database schema: H2 3 → 4 and MySQL 4 → 5, at the first start. A nullable `last_seen` column is added to `files`,
  no row written: with MySQL 8 or MariaDB 10.3+ the change is instant (metadata only), older MySQL servers rewrite the
  table; H2 copies it once (about 6 seconds per million files). The files already stored count as seen on the upgrade
  day: nothing is purged before `fileRetentionDays` after the upgrade.
- New parameters `indexRefreshSeconds` (default 10) and `fileRetentionDays` (default 180), both applied without
  restart.
- One-CPU servers may add `-Djdk.virtualThreadScheduler.parallelism=2` (see the README).
