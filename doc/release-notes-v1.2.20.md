## JEmuleServer 1.2.20

- **OFFERFILES v1: negotiated fast publishing (opt-in).** Legacy eMule and aMule publish at most 200 files a minute,
  so a large library takes hours to become searchable. A client that asks for OFFERFILES v1 at login (the
  experimental option of aMule 3.2, eMule BB) is now told in `OP_SERVERIDENT` how many files it may offer per packet
  and how often, and is held to it: 200 files every 500 ms by default, so 55,000 files are published in under three
  minutes. An early batch waits instead of being dropped, and a server-wide ceiling slows a reconnect wave down. It is
  the contract agreed between aMule and ed2k-server, so a client works the same against both servers. Clients that
  do not ask are never affected. Off by default: `offerFilesV1Enabled`, `offerFilesBatchMax`,
  `offerFilesMinIntervalMs`, `offerFilesGlobalRecordsPerSec`; see [doc/offerfiles-v1.md](offerfiles-v1.md).
- **What it brings, in the Overview.** A new tile counts the new files published by OFFERFILES v1 clients since the
  last start, next to those of legacy clients; the Ingestion view lists them as their own channel. `/api/overview`
  gives the v1 counters (logins that asked, batches, waits, ignored batches).
- **Publication limits as on Lugdunum, for every client.** `ST_SOFTFILES` now advertises `maxFilesPerUser` (instead of
  1,000,000) and counts distinct files: re-offering a file already shared (renamed, download completed) refreshes it
  without using the quota, and a client over its quota gets Lugdunum's message once per connection. An offer
  declaring 2,000,000 files or more (`ST_HARDFILES`) closes the connection. eMule and aMule publish exactly as before.
- **Server version 17.16.** The version announced to clients moves from 17.15 to 17.16 with this protocol change.
- **Less memory while the index grows.** The large index arrays grow by an eighth instead of half, so adding files no
  longer reserves tens of megabytes of unused room.
- **Heap for 1,000,000 files.** The startup warning now expects about 160 MB plus 360 bytes per indexed file, as
  measured in production, instead of 256 MB plus 400 bytes, which flagged a correctly sized server. The reference
  server runs `maxFiles=1000000` with `-Xmx520m -Xmn48m` on 947 MB of RAM; a 1,000,000-file benchmark loads in that
  heap on one CPU.

No database change: 1.2.20 opens the database of 1.2.19 as is, and can be rolled back to it.
