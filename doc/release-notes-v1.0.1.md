## JEmuleServer 1.0.1

Bug-fix release of 1.0.

### Fixes

- **HighID behind a NAT**: when several clients share the same public IP, the reachability test
  (`OP_HELLO` → `OP_HELLOANSWER`) could be received and accepted by another client behind the same router, to which
  the announced port was forwarded. The connecting client then got an unusable HighID, identical to the other
  client's. The server now checks that the answer carries the user hash sent at login; otherwise the client gets a
  LowID.
- **Windows**: a configuration path starting with `/` or `\` (without a drive letter) is no longer resolved against
  the data directory.
- **README**: HighID / LowID section, UDP ports 4665 and 4673 in the firewall configuration,
  `lowIdCheckTimeoutSeconds` parameter, JDK 21 required to build, links to the releases and release notes.
- Name shown by clients: `JEmuleServer v1.0.1`, description `eMule Server - version 1.0.1`.

### Downloads

| File | Use |
|---|---|
| `jemuleserver_1.0.1_amd64.deb` | Debian / Ubuntu (Java included) |
| `jemuleserver-1.0.1-1.x86_64.rpm` | Fedora / RHEL / openSUSE (Java included) |
| `JEmuleServer-1.0.1.exe` | Windows installer (Java included) |
| `JEmuleServer-1.0.1.jar` | Any platform with Java 21 or later: `java -jar JEmuleServer-1.0.1.jar [server.properties] [port]` |
| `jemuleserver.service` | systemd unit for the Linux packages |
| `SHA256SUMS` | Checksums |

### Upgrading from 1.0

No configuration or database schema change: stop the server, replace the JAR or install the new package, then start
it again. Installing as a service and the ports to open are described in the
[1.0 release notes](https://github.com/dagga/JEmuleServer/blob/main/doc/release-notes-v1.0.md).
