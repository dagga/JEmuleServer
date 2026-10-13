## JEmuleServer 1.0

First stable release of JEmuleServer, an eD2K (eMule) server written in Java that aims to give eMule clients at
least the same service as the historical Lugdunum server.

This release fixes the 26 incompatibilities found by the [compatibility audit against the eMule 0.50a client](https://github.com/dagga/JEmuleServer/blob/main/doc/eMule_client_compatibility_audit.md),
carried out from the client's source code.

### Main changes and fixes

- **Sources**: source requests (`OP_GETSOURCES` / `OP_GETSOURCES_OBFU`, TCP and UDP) now succeed; IPs and ports in
  little-endian; the clients' real listening port; every client sharing a file is returned; sources are removed when
  their client disconnects.
- **Publishing**: full decoding of eMule tags (compact strings, unsigned integers, 64-bit values), no more lost
  publications depending on the number of shared files, normalized file type (`Video`, `Audio`…).
- **Search**: results counted correctly, working "More" button, Unicode keywords, filters on size / availability /
  complete sources / bitrate / length / codec, 64-bit parameters, UDP global search.
- **HighID / LowID**: reachability test at login (`OP_HELLO` → `OP_HELLOANSWER`), LowID otherwise, and callback
  relaying (`OP_CALLBACKREQUESTED` / `OP_CALLBACK_FAIL`).
- **Obfuscation**: TCP handshake (Diffie-Hellman + RC4) and server UDP obfuscation matching the client.
- **Robustness**: bounded zlib decompression, no more unknown packets sent to clients.
- Name shown by clients: `JEmuleServer v1.0`, description `eMule Server - version 1.0`.

### Downloads

| File | Use |
|---|---|
| `jemuleserver_1.0_amd64.deb` | Debian / Ubuntu (Java included) |
| `jemuleserver-1.0-1.x86_64.rpm` | Fedora / RHEL / openSUSE (Java included) |
| `JEmuleServer-1.0.exe` | Windows installer (Java included) |
| `JEmuleServer-1.0.jar` | Any platform with Java 21 or later: `java -jar JEmuleServer-1.0.jar [server.properties] [port]` |
| `jemuleserver.service` | systemd unit for the Linux packages |
| `SHA256SUMS` | Checksums |

The packages install the application in `/opt/jemuleserver` (Linux) or `Program Files\JEmuleServer` (Windows).
Started without arguments, the installed program keeps its configuration (`server.properties`, created on first
start) and its H2 database in `~/.local/share/jemuleserver` (Linux) or `%LOCALAPPDATA%\JEmuleServer` (Windows).
With a configuration file as argument, relative paths stay relative to the current directory.

### Installing as a service (Linux)

```bash
sudo dnf install ./jemuleserver-1.0-1.x86_64.rpm      # or: sudo apt install ./jemuleserver_1.0_amd64.deb
sudo useradd --system --home-dir /var/lib/jemuleserver --create-home --shell /usr/sbin/nologin jemule
sudo install -d /etc/jemuleserver
sudo curl -L -o /etc/jemuleserver/server.properties https://raw.githubusercontent.com/dagga/JEmuleServer/v1.0/server.properties
sudo cp jemuleserver.service /etc/systemd/system/ && sudo systemctl daemon-reload
sudo systemctl enable --now jemuleserver
```

Ports to open: **TCP 4661**, **UDP 4661, 4665 and 4673** (port + 4: UDP; port + 12: obfuscated UDP).
The server must also be able to open outgoing TCP connections to the clients (HighID test).

### Upgrading from a previous version

The existing H2 database is kept (unchanged schema): stop the server, back up `jemule_db.mv.db`, replace the JAR or
install the package, and start it again **from the same directory** (or with the same `databasePath`). Files stored
in the database show up in searches again as soon as their sources reconnect and publish them again.

New optional parameter: `lowIdCheckTimeoutSeconds` (5 by default, 0 = everyone gets a LowID).
