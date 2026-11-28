## JEmuleServer 1.7.6

### Relay mode

- **Bounded queue of the requests played on the relay server (#150).** The requests waiting for one of the 4 relay
  sessions were not bounded: each client OP_GETSOURCES queued a source request, and whenever the relay server
  answered slower than they came, the queue grew without limit (~790,000 requests, ~350 MB, before an
  `OutOfMemoryError` on a 560 MB heap every 4 to 15 hours). At most 64 requests now wait, of which 32 source
  requests, the room above them being kept for the searches; a request refused stays local (no relay sources, or the
  local index answers the search), and a warning `Relay server ... busy` is logged at most once a minute.
- **Stale requests dropped (#150).** A request that waited more than 10 s for a session is not played: its client has
  moved on.
- **No new session per queued request after a failure (#150).** While the relay server is left alone after a failure
  (30 s), the queued requests stay local instead of each opening a new session.
- **New relay counters (#150):** `queued`, `rejected` and `expired` in the relay statistics.
