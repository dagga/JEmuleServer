# OFFERFILES v1: negotiated fast publishing

## Problem

Legacy eMule and aMule publish their shared files to a server in `OP_OFFERFILES` packets of at most 200 files, about
one packet a minute, because a Lugdunum server may black out an IP that publishes faster. A client sharing 55,000
files needs four and a half hours to be fully published. `ST_SOFTFILES` and `ST_HARDFILES` do not help: they describe
how many files a client may have indexed and how many one packet may declare, not how fast it may publish.

OFFERFILES v1 lets a client and a server agree on a faster pace. JEmuleServer implements the contract agreed between
[ed2k-server](https://github.com/andrey23127/ed2k-server/issues/19) (0.9.80 and later) and the aMule client
([aMule #1699](https://github.com/amule-org/amule/issues/1699), [PR #1715](https://github.com/amule-org/amule/pull/1715),
targeting aMule 3.2.0, experimental and off by default there). The eMule BB Rust client implements it as well. A
client that works against one of these servers works against JEmuleServer unchanged.

## Wire contract

**The client asks.** A client implementing v1 adds to its `OP_LOGINREQUEST` tag list a string-named `uint32` tag
`offerfiles_v` carrying the highest version it implements (`1`). It is an old-format tag: type `0x03`, `uint16` name
length, name, `uint32` value. Any other shape (absent, another type, `0`) is a legacy client. A server that does not
know the tag skips it like any unknown login tag.

**The server answers**, only to a client that asked and only when `offerFilesV1Enabled=true`, in the
`OP_SERVERIDENT` (0x41) it sends right after login, before `OP_IDCHANGE`. It is the only `OP_SERVERIDENT` of the
connection. Each of these tags appears exactly once, all `uint32`:

| Tag | Name | Value |
|---|---|---|
| `ST_SOFTFILES` | numeric 0x88 | the connection's indexing budget, in distinct files: `maxFilesPerUser` |
| `ST_HARDFILES` | numeric 0x89 | per-packet boundary: every `OP_OFFERFILES` must declare **fewer** files (2,000,000) |
| `offerfiles_v` | string | `1` |
| `offerfiles_batch_max` | string | most files in one `OP_OFFERFILES`: `offerFilesBatchMax` |
| `offerfiles_min_interval_ms` | string | least time between two full batches: `offerFilesMinIntervalMs` |

The advertisement is all or nothing: soft > 0, batch_max > 0, interval > 0 and hard > batch_max, otherwise no
`offerfiles_*` tag is sent and the connection is legacy on both sides. There is no burst tag. The five values are a
snapshot taken at login and hold for the connection's whole life: a configuration change reaches new logins only, and
a reconnect starts afresh.

Every other client, including one that asked while v1 is disabled, gets exactly the legacy `OP_SERVERIDENT` and
publishing behavior.

## Client rule

A client that received a valid advertisement sends batches of at most
`min(remaining soft budget, offerfiles_batch_max, ST_HARDFILES - 1, its own cap)` files, at most one full batch every
`offerfiles_min_interval_ms`. With the defaults (200 files every 500 ms), 55,000 files are published in under three
minutes. The first valid advertisement binds the connection: a later `OP_SERVERIDENT` changes nothing.

## What the server does

On **every** connection, as Lugdunum:

| The offer… | Result |
|---|---|
| declares `>= ST_HARDFILES` files | refused before any file is read, connection closed |
| brings new files beyond `ST_SOFTFILES` distinct files | those files are not indexed; the client gets `WARNING : This server accepts N shares per client. Some of your shares are ignored.` once per connection; the connection stays up |
| re-offers a file the client already shares (rename, completed download) | the source is refreshed, including its complete/partial state; it takes no soft budget slot |

Files rejected by the content filters use no soft budget.

On a **v1** connection, also:

| The batch… | Result |
|---|---|
| declares more than `offerfiles_batch_max` files | not indexed (the whole batch), counted, logged; the client gets one server message per connection; the connection stays up |
| arrives before it is due | waits until it is due, then is processed; nothing is dropped |
| would push all v1 connections over `offerFilesGlobalRecordsPerSec` | waits its turn (arrival order), then is processed |

"Due" is a token bucket per connection, counted in files: it holds one batch, starts full (the first batch goes at
once) and refills at `offerfiles_batch_max` files per `offerfiles_min_interval_ms`. Smaller batches cost proportionally
less, and idle time is not banked beyond one batch. Batches that TCP delivers together are therefore not an offence,
only processed on schedule. While a batch waits, its connection reads nothing more, so the client is held back by TCP
rather than by a queue in the server. Re-offers count toward the batch size and the pace like any other file. An empty
offer (the keep-alive) costs nothing.

A legacy connection is held to none of this: the 200-file batch of eMule and aMule is their own policy, and a legacy
client may declare any count below `ST_HARDFILES`, including its whole share list in one packet.

There is no acknowledgement packet in v1: a client cannot tell which files were indexed and should not try to infer
it. Which name a re-offered file is searched under is this server's own behavior, not part of the contract.

## Configuration

| Option | Default | Range |
|---|---|---|
| `offerFilesV1Enabled` | `false` | Answer clients that ask and hold them to v1 (new logins). |
| `offerFilesBatchMax` | `200` | 1 to 100,000 |
| `offerFilesMinIntervalMs` | `500` | 1 to 3,600,000 |
| `offerFilesGlobalRecordsPerSec` | `5000` | 0 (no ceiling) to 10,000,000; applied live |

`ST_SOFTFILES` is `maxFilesPerUser`, advertised to every client in `OP_SERVERIDENT`, `OP_SERVER_DESC_RES` and
`OP_GLOBSERVSTATRES`. Legacy eMule and aMule use it only to shrink their 200-file batch when it is smaller.

## Counters

The admin web panel's `/api/overview` gives, under `offerFilesV1`: logins that asked, sessions advertised, batches and
files processed on v1 connections, waits for the connection's own pace (count, ms), waits for the server-wide ceiling
(count, total ms, longest, waiting now), batches above `offerfiles_batch_max`, offers refused at `ST_HARDFILES` and
files ignored over `ST_SOFTFILES` (the last two for every client). Production values of the pace should be chosen from
them.
