## JEmuleServer 1.1

### New features

- **Search covers every known file**: server searches (TCP and global UDP) now return the files of the database and
  the files whose sources are offline (with 0 sources), not only the files shared by a connected client. A file stays
  searchable after its last source disconnects.
- **Child abuse filter**: files whose name advertises child sexual abuse material are refused and their hash is banned
  for good, under any name. Files already in the database are deleted and banned at startup. The keywords are read
  from `child-abuse-keywords.properties` (embedded); `childAbuseKeywordsPath` points to a replacement list. The filter
  cannot be disabled. See the [README](https://github.com/dagga/JEmuleServer#child-abuse-filter).
- **Admin port**: `adminPort` opens the admin console on a TCP port bound to `127.0.0.1`, for a server running as a
  service without a terminal (`nc 127.0.0.1 4670`). Disabled by default; no authentication.
- **`events` admin command**: number of events of each type (connections, logins, publications, searches...) since
  startup and during the last minute.
- **Quotas enforced**: `maxUsers` (now 1,000,000 by default) refuses logins with a "server full" message;
  `maxFiles` (10,000,000) refuses new files once the index is full (known files still get new sources).
- Name shown by clients: `JEmuleServer v1.1 (no pedo)`, description `eMule Server - version 1.1`.

### Fixes

- **IP filter**: like eMule, only ranges of level below 100 block; ranges of level 100 or more are now allowed.
  Overlapping ranges are merged, so an address inside a large range is found even when a smaller range is nested in it.
  Addresses are parsed without DNS lookups.
- **Admin `ban`**: the ban is stored in the database with its reason (it was lost on restart) and the file is removed
  from the index. Banned hashes are enforced even when `fakeFileDetectionEnabled=false`.
- **Fake file detection**: a file matching a heuristic (suspicious keyword, double extension...) is still refused, but
  its hash is no longer banned for good. Keywords match whole words: "crack" no longer matches "Crackerjack".
- **Database**: the single H2 connection is no longer used by several threads at once; the transaction storing a file
  could interleave with other statements.
- **IPv6**: the server always announces an IPv4 address in `OP_SERVERIDENT` (it announced `127.0.0.1` with an IPv6
  public address). [doc/ipv6.md](https://github.com/dagga/JEmuleServer/blob/main/doc/ipv6.md) now describes the real
  limits: IPv6 clients connect and publish, but get a LowID and cannot request callbacks.

### Downloads

| File | Use |
|---|---|
| `jemuleserver_1.1_amd64.deb` | Debian / Ubuntu (Java included) |
| `jemuleserver-1.1-1.x86_64.rpm` | Fedora / RHEL / openSUSE (Java included) |
| `JEmuleServer-1.1.exe` | Windows installer (Java included) |
| `JEmuleServer-1.1.jar` | Any platform with Java 21 or later: `java -jar JEmuleServer-1.1.jar [server.properties] [port]` |
| `jemuleserver.service` | systemd unit for the Linux packages |
| `SHA256SUMS` | Checksums |

### Upgrading from 1.0.x

No database schema change: stop the server, back up the database (`jemule_db.mv.db`), replace the JAR or install the
new package, then start it again. Check before restarting:

- **The first start deletes** the files of the database matching the child abuse filter, and logs each of them
  (`Child abuse file banned: hash=... name=...`).
- **`maxUsers`**: an existing `server.properties` keeps its own value (100000 in the 1.0 file).
- **`ipfilter.dat`**: ranges of level 100 or more no longer block.
- **Former automatic bans**: hashes banned by the fake file heuristics of 1.0 (reason `Auto-banned: ...`) stay banned,
  and are now enforced even with `fakeFileDetectionEnabled=false`. To lift them, stop the server and run
  `DELETE FROM banned_hashes WHERE reason LIKE 'Auto-banned:%'` with the H2 shell
  (`java -cp JEmuleServer-1.1.jar org.h2.tools.Shell -url jdbc:h2:./jemule_db -user sa -password ""`).
