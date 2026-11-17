## JEmuleServer 1.6.2

### Fixes

- **A fatal error is logged and ends the server with an error status.** An `Error` thrown in the loop that accepts the
  clients (an `OutOfMemoryError` for example) ended the main thread: the JVM stopped with only a stack trace on the
  console, lost when the console is gone. It is now logged ("The server stopped on a fatal error") and the JVM exits
  with status 1, after the shutdown hook has closed the index and the database. A startup failure also exits with
  status 1, and any error that ends a thread is logged. A supervisor can restart the server on these statuses (#113).
- **No more leaked connection after an online compaction.** The connection ended by `SHUTDOWN COMPACT` was not closed:
  H2 wrote a "connection was not closed by the application" error (90018) to `jemule_db.trace.db` at each compaction
  (#113).

### Upgrade notes

- No database change, no new parameter.
- Exit statuses: 0 after a wanted stop (console `stop`), 143 after SIGTERM, 1 when the server could not start or
  stopped on a fatal error. A supervising script or systemd unit (`Restart=on-failure`) can restart it on 1.
