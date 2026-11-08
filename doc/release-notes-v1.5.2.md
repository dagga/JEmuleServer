## JEmuleServer 1.5.2

### Fixes

- **Relay mode: the clients are told the relay server's file count.** A server in relay mode announced the files of
  its local index, which is only a fallback there, in `OP_SERVERSTATUS` and `OP_GLOBSERVSTATRES`. It now announces
  those of the relay server's index, which the searches use, as the relay server's last status gave them (or its
  `/api/relay/index` before its first status).

### Improvements

- **The relay shows the relay server's index, never its own.** The relay server answers the relays on
  `/api/relay/index` (same `relayClients` and `relayToken` as the feed) with its file count, its `maxFiles` and its
  file history; the relay reads it at the start and every 5 minutes. On the relay:
  - the Overview headline shows the relay server's files, its 24 h and 7 days changes and the age of these figures,
    with the size of the local fallback index;
  - the file curve of the history is the relay server's (the client curve stays this server's own clients);
  - the index capacity gives way to the files sent to the relay server (sent, waiting, lost, last batch, failures);
  - the Ingestion view counts the files sent to the relay server by channel, instead of the files indexed here;
  - the "Relay server" tile shows the file count announced to the clients.

  On the relay server, the "Files from relay servers" tile leads with the new files the relays brought.
