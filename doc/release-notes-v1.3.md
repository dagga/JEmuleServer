## JEmuleServer 1.3

### New features

- **MySQL or MariaDB database.** Instead of its H2 file, the server can keep its database on a MySQL 8 or MariaDB
  10.11+ server, on the same machine or on another one:
  ```properties
  databaseType=mysql
  databaseUrl=jdbc:mysql://127.0.0.1:3306/jemule
  databaseUser=jemule
  # or empty, and the JEMULE_DATABASE_PASSWORD environment variable
  databasePassword=...
  ```
  The server creates its tables. The database cache moves out of the Java heap (into the database server's buffer
  pool, which stays warm across restarts of the eMule server), no compaction is needed any more, and a lost
  connection is reopened. H2 stays the default.
- **H2 import at the first start.** With `databaseImportFromH2=true` (the default), the first start on an empty
  MySQL database imports the H2 database found at `databasePath`: files, tags, source counts, banned names, statistics
  and metrics history, page by page, resumed if interrupted. A 2.4 million file database is imported in about a
  minute. The H2 file is left untouched, as a backup.
- **Database type in the Overview.** The Database tile shows H2 or MySQL.

### Improvements

- The files published one by one by the clients share a database transaction (up to 10,000), on H2 too; so do the
  source count updates of a batch.
- Ban dates are kept to the microsecond on every database, so that the most recent bans are listed in order.
- Reference and minimal configurations in `config/`.

### Upgrade

No change on an H2 database. To move to MySQL or MariaDB, see "Moving an H2 database to MySQL" in the README.
