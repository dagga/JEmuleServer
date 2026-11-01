## JEmuleServer 1.5

### Improvements

- **Server gossip as Lugdunum and ed2k-rust do it** (issues #73 to #76). Other servers list a server only after it
  registered with them, and Lugdunum only after an obfuscated handshake. Once a server is verified, JEmuleServer now:
  1. sends it a plain `OP_SERVER_LIST_REQ` of 12 bytes (`<ip><tcp port><challenge>`) on its TCP+4;
  2. when it advertised a UDP key, sends a crypt ping from its own TCP+14 to the server's TCP+12; the obfuscated
     status the server answers with gives its key;
  3. from the same socket, sends `OP_SERVER_LIST_REQ` and `OP_SERVER_LIST_REQ2` obfuscated with that key to the
     server's TCP+14, with an obfuscated `OP_GLOBSERVSTATRES` echoing the challenge of the server's last status
     request (Lugdunum only takes this server's key from an obfuscated status). Later status requests of that server
     are answered the same way.

  The server also listens on UDP **TCP port + 14**, where Lugdunum sends its obfuscated server traffic, and accepts
  the 12-byte registrations of Lugdunum and ed2k-rust: the registrant is probed and listed once verified.
- **Smaller MySQL database** (schema version 4). The hashes take their 16 bytes (`BINARY(16)`) instead of 32
  hexadecimal characters, and the source count of a file is a column of `files` instead of a table of its own
  (`file_sources` goes). On a copy of the reference server's database (2,750,631 files), files and tags take 483 MB
  instead of 723 MB, and the whole database loads in 8 s. H2 is unchanged.
- **H2 loaded page by page**, 5,000 files per query, as MySQL already was: no temporary file any more (413 MB for
  2.4 million files before, in `java.io.tmpdir`), and 38 s instead of 47 s on 2,398,695 files.

### Fixes

- **Server-to-server obfuscation readable by Lugdunum.** It used `0x00` where eMule's UDP obfuscation puts its
  direction byte (`0x6B` for a request, `0xA5` for an answer), so no other server could decode it.
- **An incomplete index is rebuilt.** When the database could not fill the file index at the start (on 2026-10-29, a
  full `/tmp` during the load), the server ran with an incomplete index, and a clean stop recorded its token: the next
  start reopened it. Such an index now gets no token, and the next start rebuilds it.

### Upgrade

- **Open UDP port + 14** (4675 with the default port 4661) in the firewall:
  `sudo firewall-cmd --add-port=4675/udp --permanent && sudo firewall-cmd --reload`.
- **MySQL:** a database of version 3 (1.3 to 1.4.2) is migrated at the first start (34 s for 2,750,631 files): files
  and tags are copied page by page into new tables, then swapped with the old ones. Stopped before the swap, the
  migration starts over at the next start. 1.4.x cannot use a version 4 database: back up the database before the
  upgrade to be able to go back. `OPTIMIZE TABLE files, file_tags` with the server stopped gives back the room the
  random hashes leave in InnoDB pages.
- The file index format is unchanged: after a clean stop of 1.4.2, the first start of 1.5 reopens it.
