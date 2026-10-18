# JEmuleServer and Lugdunum/eServer

The included eMule client source lets this repository compare JEmuleServer's protocol behavior with the specific
eMule 0.50.1 client. Historical attribution to Lugdunum is shown only where the project references make that claim;
the eMule client source verifies client behavior, not the historical origin or release coverage of an extension.

> **Caution:** the protocol reference files in this repository contain conflicting opcode, tag and wire-format
> descriptions. For client behavior, this comparison uses eMule 0.50.1 source (`../eMule`, commit `88a8c8f`); see the
> [conflict review](protocole_unsafe.m). Client source establishes what that eMule version implements, not which
> historical server first introduced a feature.

| Feature | eMule 0.50.1 client behavior (source) | Lugdunum attribution in repository documents |
|---|---|---|
| Server identity and ID assignment | Recognizes `ID_CHANGE` (0x40) and `SERVER_IDENT` (0x41). | `protocols.md` attributes these as Lugdunum extensions; the client source confirms use, not historical origin. |
| Server list exchange | Sends `GETSERVERLIST` (0x14) and receives `SERVERLIST` (0x32). | `protocols.md` previously mislabeled 0x42 as `SERVER_LIST`; its attribution should not be relied on for that opcode. |
| Source lookup | Sends `GETSOURCES` (0x19) / `GETSOURCES_OBFU` (0x23); accepts `FOUNDSOURCES` (0x42) / `FOUNDSOURCES_OBFU` (0x44). | Historical origin/release coverage is not established by the client source. |
| Tags and Boolean search | `opcodes.h` / `packets.cpp` define the client's tag types and encodings; client search code handles search requests/results. | `protocols.md` attributes `CT_EMULE_VERSION` (0xFB), extended tags and advanced Boolean search to Lugdunum; that historical attribution needs an independent source. |
| RC4 obfuscation and ZLIB compression | The client sources implement these mechanisms; the compatibility audit records server-side interoperability issues for the audited revision. | Support by each historical Lugdunum release is not established here. |
| JEmuleServer-specific features | The database, admin panel, filtering, partial IPv6, Kad bridge and verified server gossip/search are documented in this repository. | These client sources do not establish whether or when Lugdunum implemented comparable features. |

## Evidence and limits

The Lugdunum-specific attributions are claims made by [`protocols.md`](protocols.md#3-lugdunum-eserver-extensions)
and the historical description in [`references.md`](references.md#developer-resources-and-historical-context). The
code in `../eMule` establishes the behavior of eMule 0.50.1; it does not independently substantiate those historical
claims or prove support in every Lugdunum release. See [`protocole_unsafe.m`](protocole_unsafe.m) for protocol-document
conflicts resolved against that client.

There is an opcode inconsistency in the reference notes: [`protocols.md`](protocols.md#3-lugdunum-eserver-extensions)
previously called 0x42 `SERVER_LIST`, while the opcode mapping used by JEmuleServer assigns 0x32 to `SERVER_LIST` and
0x42 to `FOUND_SOURCES`. The comparison describes the observable server-list capability without assigning 0x42 to it.
The separate GitHub repository still provides no implementation source or release-specific feature list. Claims about
unlisted internals or exact release support remain unknown.
