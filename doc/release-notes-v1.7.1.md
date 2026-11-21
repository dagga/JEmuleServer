## JEmuleServer 1.7.1

### Changes

- **LowID sources only to the LowIDs that can use them (#126).** A stock eMule or aMule that is LowID drops the LowID
  sources it receives (`CPartFile::CanAddSource()`) and cannot have a client of the same server called back, so these
  records only took room in the 255 records of its answer, sometimes hiding the HighID sources of a file. The answer
  to `OP_GETSOURCES` (`_OBFU`) of a LowID now holds the HighID sources, the LowID sources it gets as IPv6 records, and
  the LowID sources it can reach by NAT traversal: the other `0x1000` clients for a `0x1000` client while
  `natCallback` is on, and every LowID registered for the callback or the hole punch for a client that asks with
  `0x60` while `natHolePunch` is on. The others are left out before the `maxSourcesPerFile` limit. HighID clients and
  other servers still get every source. The UDP answer to the clients of other servers (`OP_GLOBGETSOURCES`) keeps a
  LowID only while `natCallbackCrossServer` is on and the LowID opted in with `0x1000` (#135).

### New features

- **Hole punch to Lugdunum-only clients (#127).** A client that implements only Lugdunum's callback (`0x1000` without
  `0x100000`, e.g. NeoMule, NeoLoader) ignores `0x61`. When another LowID asks to punch it (`0x60`), it now also gets
  Lugdunum's `0x37` with the requester's endpoint, so that it dials the requester, as ed2k-server's bridge does: only
  when it sent no `0x9F` in its session and the crypt options of the two allow it. Counted as `bridge` in the hole
  punch counters. Needs `natCallback` and `natHolePunch` (#134).

### Upgrade notes

- No database change, no new parameter. The server version announced to clients stays 17.18.
- After the upgrade, LowID clients receive fewer sources: the LowID sources they dropped anyway are no longer sent.
