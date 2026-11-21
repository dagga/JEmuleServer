## JEmuleServer 1.7.0

### New features

- **IPv6 sources (#117).** A client that is LowID on IPv4 but reachable on a public IPv6 address can be given as a
  source to the clients that read IPv6 source records (aMule master with `ENABLE_IPV6`, eMuleQt), as ed2k-server and
  eNode-go do. The client announces its address at login (`CT_MOD_IP_V6`, `0xAE`) or later (`OP_IPV6_UPDATE`,
  `0x63`); the server dials it back on that address and checks its user hash before publishing it, and reports its
  verdict in `OP_SERVERIDENT` (`ST_IPV6_STATUS`, `0xAB`: `0x01` held, then `0x07` reached or `0x05` not reached).
  IPv6 records only go to LowID clients that read them, never to other servers. Off by default: `ipv6Sources=true`,
  applied without restart to new logins; off whatever its value when the JVM runs with
  `java.net.preferIPv4Stack=true`. See [doc/ipv6.md](ipv6.md) (#122).
- **Obfuscated HighID test and IPv6 dial-back (#124).** A client that announces the crypt layer at login is dialled
  with eMule's client-to-client obfuscation first, as ed2k-server and eNode-go do, then in plain if that handshake
  fails and the client does not require the layer. A client that requires the layer accepts a plain test only from
  the server's IPv4 address during its login: without this, it was never reached over IPv6. eMuleQt's hidden strict
  option, which refuses even that test, now gets its HighID. On by default; `obfuscatedFirewallCheck=false` goes back
  to plain dials only, without restart (#129).
- **Login messages editable without restart.** `welcomeMessage` replaces the "Welcome to JEmuleServer version ...
  Your ID is ..." text shown in the client's server info, and `loginMessage` adds a further message right after it,
  for rules or announcements. Both accept `{version}` and `{id}`, and `\n` for a new line; empty means the default
  welcome and no further message. Editable from the web panel settings and the `settings` console command.

### Changes

- **Server version 17.18.** The version announced to clients moves from 17.17 to 17.18 with the IPv6 source opcodes.
- The HighID test logs at debug level whether a client was reached obfuscated or plain
  (`Firewall check (obfuscated|plain) of <ip>:<port> reached`).

### Fixes

- An `OP_SERVERIDENT` sent again during a session (the IPv6 verdict) keeps the NAT callback session token: a new
  token would have made the client's `0x9F` proofs fail.

### Compatibility

The obfuscated HighID test was checked against eMule 0.50a, 0.70b and 0.72a under Wine, with the crypt layer off,
supported and required, the obfuscated test on and off, and with every option on (`ipv6Sources` and the four NAT
options): every login got its HighID, obfuscated for the clients announcing the crypt layer, and the sessions stayed
up. eMule ignores the IPv6 and NAT TCP flags it does not know. aMule master was checked by its maintainer, with the
crypt layer required and over IPv6.

### Upgrade notes

- No database change.
- New parameters, all applied without restart: `ipv6Sources` (default `false`), `obfuscatedFirewallCheck` (default
  `true`), `welcomeMessage` and `loginMessage` (default empty).
- The HighID test of most clients is now obfuscated: if HighID ratios drop after the upgrade, set
  `obfuscatedFirewallCheck=false` from the web panel and report it.
