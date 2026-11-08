## JEmuleServer 1.5.4

### Fixes

- **A database whose H2 file channel was closed is reopened.** A thread interrupted during an H2 read or write closes
  the file channel of the whole database (`ClosedByInterruptException`), after which every operation fails with a
  `ClosedChannelException`: on 2026-11-08 a relay lost all its database writes (metrics history, banned names, stats)
  until it was restarted. Such an error is now recognized as a closed database, which is reopened as after a fatal
  error, and the operation is retried on it.
