## JEmuleServer 1.6.3

### New features

- **Server name and description editable from the admin panel, without restart.** `serverName` and
  `serverDescription` are new hot parameters, listed first in the Settings page of the web panel and in the `settings`
  console command. Empty means the defaults. At most 255 bytes in UTF-8, no control character. New logins and the
  clients refreshing their server list get the new values; connected clients keep the name they received at login
  (#128).
- **NAT traversal between LowID clients (#112).** All parts are opt-in, off by default and hot (new logins). They
  follow aMule master `a6d6290be0` (`docs/NAT_T_Protocol.md`), ed2k-server and eNode-go:
  - `natCallback`: Lugdunum eserver's NAT callback between two LowID clients of this server. A client that sets
    `0x1000` in `CT_SERVER_FLAGS` gets an odd LowID and a 24-byte `OP_IDCHANGE`, may send UDP `0x9F` keepalives and
    obfuscated `0xA6` requests; the target is woken by TCP `0x37` or UDP `0xA7`. Requests are limited per requester
    and per target (#115).
  - `natCallbackToken`: a session token in `OP_SERVERIDENT` (`0xAC`, with `0xA9` = 1) lets a `0x9F` proven with
    HMAC-SHA256 and a rising counter count from any address, for clients whose UDP leaves from another public IP than
    their TCP (#116).
  - `natHolePunch`: ed2k-server's LowID hole punch. TCP flags `0x20000` and `0x100000` in `OP_IDCHANGE`; a LowID asks
    with TCP `0x60` and both sides get each other's UDP endpoint in `0x61` (or the requester gets a `0x62` reason);
    between two clients with the hole-punch extension, the target is first woken with `0x37`. aMule sends its `0x9F`
    keepalives only to a server that sets `0x20000` (#119).
  - `natCallbackCrossServer`: a client logged in to another server may ask this server to wake one of its LowIDs
    (obfuscated `0xA6` with requester ID 0), announced with UDP flag `0x100000`. Limited per source IP and per target;
    no answer reveals whether a target exists. See [doc/nat-callback-cross-server.md](nat-callback-cross-server.md)
    (#118).

### Changes

- **Server version 17.17.** The version announced to clients moves from 17.16 to 17.17 with the NAT traversal
  opcodes.
- The 20-byte `OP_IDCHANGE` now carries the server TCP port in its third field instead of 0 (eMule and aMule skip it).

### Upgrade notes

- No database change.
- New parameters, all hot: `serverName`, `serverDescription` (empty by default), `natCallback`, `natCallbackToken`,
  `natHolePunch`, `natCallbackCrossServer` (all `false` by default; the last three need `natCallback`). Without them in
  `server.properties`, the server behaves as 1.6.2.
