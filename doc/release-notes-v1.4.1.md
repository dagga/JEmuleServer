## JEmuleServer 1.4.1

### Fixes

- **Configured server peers are never blocked by the IP filter** (#59). A server listed in `serverPeers` is kept,
  verified, searched and heard whatever the IP filter says; a warning is logged once when it lies in a blocked range.
  Two servers configured as each other's peers now use each other both ways: forwarded searches, source requests and
  index updates.
- **Hosting providers no longer block eD2K servers.** The emule-security IP filter is made for eMule clients: about
  28,000 of its 172,776 ranges block whole hosting providers ("hosting", "Cloud", "cloudflare:AS..."), where nearly
  every eD2K server runs. The other servers are now checked without those ranges, unless their description also names
  a hostile activity (AP2P, phishing, fraud, fake servers, spam, DDoS...). The sources Kad returns keep the whole
  filter.

### Upgrade

No change to the configuration. The binary cache of the IP filter (`<ipFilterPath>.bin`) is rebuilt at the first
start.
