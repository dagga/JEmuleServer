## JEmuleServer 1.2.5

- Restrict `ipfilter.dat` filtering to listed and federated eMule servers (server peers, server.met seeds, client-reported servers and UDP gossip) without blocking ordinary client connections.
- Ensure blocked servers are excluded from server lists sent to clients and gossip packets from blocked IPs are rejected.
