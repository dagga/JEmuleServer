## JEmuleServer 1.2.29

- **Relay server.** A server whose index is full can now hand over to a larger one and only act as its proxy, with
  `relayServer=<host>:<TCP port>` (empty by default, applied at once). While the index is full (`maxFiles` reached,
  or the heap guard):
  - the clients stay connected and get the relay server in their server list at login, without any "server full"
    message;
  - their searches are played on the relay server through client sessions of this server there (TCP, 4 at the same
    time), with the "More" pages: up to 900 results, sent to the client first; the client waits for them at most
    8 seconds, later ones come with "More";
  - their source requests too: the relay server's HighID sources follow in a second `OP_FOUNDSOURCES`;
  - the UDP global searches of other servers' clients get the relay server's first page after the local results,
    except those sent by the relay server itself.
  The relayed files go through the child abuse filter, the banned names and the fake file detector. A relay server
  that cannot be reached is left alone for 30 seconds. The Overview has a "Relay server" tile.

No database change.
