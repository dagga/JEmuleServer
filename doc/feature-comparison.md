# JEmuleServer and Lugdunum/eServer

This comparison is intentionally limited to verifiable information. The public
[Lugdunum GitHub repository](https://github.com/rusingineer/Lugdunum) describes itself as an “eMule/eDonkey server”
and “reverse engineering”, but the inspected default-branch repository contains no implementation source or feature
documentation beyond that short README. Its unlisted or undocumented capabilities are **unknown**, not unsupported.

| Area | JEmuleServer | Lugdunum/eServer |
|---|---|---|
| Project description | Java eMule server; current project capabilities are documented in this repository. | The repository README describes it as an eMule/eDonkey server. |
| Language and runtime | Java 21; the project uses virtual threads. | Not established by the available README or repository contents. |
| Published feature evidence | See this README, [`protocols.md`](protocols.md), [`kademlia.md`](kademlia.md), and [`ipv6.md`](ipv6.md). | The repository has no implementation source or feature list from which to confirm individual capabilities. |
| Kademlia search bridge | Implemented and documented in [`kademlia.md`](kademlia.md). | Unknown from available public documentation. |
| Verified server gossip and search | Implemented; peers are checked before being used. | Unknown from available public documentation. |
| Loopback web administration | Implemented; see the [administration section](../README.md#administration-interface). | Unknown from available public documentation. |
| IPv6 | Partial; see [IPv6 limitations](ipv6.md). | Unknown from available public documentation. |

Earlier README claims about specific Lugdunum implementation details (language, storage, filters, IPv6, and feature
parity) are removed because the available source did not establish them. A stronger comparison requires a verifiable
Lugdunum source release or authoritative feature documentation.
