# eD2k/eMule : conflits documentaires tranchés par le code client

> **Référence d’autorité retenue :** l’implémentation cliente du dépôt adjacent `../eMule`, commit
> `88a8c8f` (version `0.50.1`, `Version.h`). Les renvois ci-dessous ciblent les fichiers de cette révision.
> Cette analyse décrit ce que ce client émet, reçoit et décode. Elle ne prouve pas qu’une fonctionnalité a été
> inventée par Lugdunum, ni que toutes les versions d’eMule ou tous les serveurs ont le même comportement.
>
> Le suffixe `.m` est conservé à la demande; le contenu est du Markdown, pas du code Objective-C.

## Incohérences résolues par le client 0.50.1

### 1. OpCodes TCP client-serveur

La table faisant autorité est `../eMule/opcodes.h`, section `client <-> server`. Elle attribue notamment :

| Direction | Opcode | Nom dans le client 0.50.1 |
|---|---:|---|
| Client → serveur | `0x14` | `OP_GETSERVERLIST` |
| Client → serveur | `0x15` | `OP_OFFERFILES` |
| Client → serveur | `0x16` | `OP_SEARCHREQUEST` |
| Client → serveur | `0x19` | `OP_GETSOURCES` |
| Client → serveur | `0x21` | `OP_QUERY_MORE_RESULT` |
| Client → serveur | `0x23` | `OP_GETSOURCES_OBFU` |
| Serveur → client | `0x32` | `OP_SERVERLIST` |
| Serveur → client | `0x33` | `OP_SEARCHRESULT` |
| Serveur → client | `0x34` | `OP_SERVERSTATUS` |
| Serveur → client | `0x35` | `OP_CALLBACKREQUESTED` |
| Serveur → client | `0x36` | `OP_CALLBACK_FAIL` |
| Serveur → client | `0x38` | `OP_SERVERMESSAGE` |
| Serveur → client | `0x40` | `OP_IDCHANGE` |
| Serveur → client | `0x41` | `OP_SERVERIDENT` |
| Serveur → client | `0x42` | `OP_FOUNDSOURCES` |
| Serveur → client | `0x44` | `OP_FOUNDSOURCES_OBFU` |

Sources : `../eMule/opcodes.h`; dispatch réception serveur dans `../eMule/ServerSocket.cpp`.

**Contradictions documentaires corrigées ou à corriger :**

- `protocol_completion.md` et l’ancienne version de `protocols.md` appellent `0x42` `SERVER_LIST`. Le client attribue
  `0x42` à `FOUNDSOURCES`; `SERVERLIST` est `0x32`.
- `protocol_completion.md` appelle `0x64` `SEARCH_RESULT`; le client reçoit les résultats TCP via `0x33`.
- `protocol_completion.md` appelle `0x14` `SOURCES_RESULT`; le client envoie `GETSERVERLIST` sur `0x14`.
- `protocol_completion.md` appelle `0x15` `GET_SOURCES`; le client publie les fichiers avec `OFFERFILES` sur `0x15`.
- Le rétro-spec attribue au contraire correctement `0x14` à `GETSERVERLIST`, `0x15` à `OFFERFILES`, `0x19` à
  `GETSOURCES`, `0x32` à `SERVERLIST`, `0x33` à `SEARCHRESULT` et `0x42` à `FOUNDSOURCES`.

### 2. Source lookup et réponse obfusquée

Dans `../eMule/opcodes.h`, `OP_GETSOURCES_OBFU=0x23` et `OP_FOUNDSOURCES_OBFU=0x44` sont déclarés dans la section
client-serveur, avec le protocole serveur décrit par le client comme `0xE3`. `../eMule/ServerSocket.cpp` accepte les
réponses `FOUNDSOURCES` et `FOUNDSOURCES_OBFU`; `../eMule/DownloadQueue.cpp` choisit `0x23` selon les capacités
annoncées du serveur.

Le client **ne** décrit **pas** `GET_SOURCES_OBFU` comme `0xC5/0x23` ni sa réponse comme `0xC5/0x24`. Ces affirmations
dans `protocols.md` et `protocol_completion.md` sont incompatibles avec la table et le traitement du client ciblé.
L’octet `0x24` n’est pas la réponse source obfusquée dans cette table. Ces documents confondent aussi la variante
obfusquée client-serveur avec le protocole étendu eMule.

### 3. `COMPRESSED_PART` appartient au protocole client-client

`../eMule/opcodes.h` déclare `OP_COMPRESSEDPART=0x40` dans la section `client <-> client`, et
`OP_COMPRESSEDPART_I64=0xA1` dans la table des transferts 64 bits. Ce n’est pas un opcode client-serveur et cette
déclaration ne suffit pas à justifier de le décrire comme `0xC5/0x40`.

`protocols.md` et `protocol_completion.md` attribuent `COMPRESSED_PART` à `0xC5/0x28`, également incompatible avec la
valeur trouvée dans le client 0.50.1. Le `0x40` client-client ne doit pas être confondu avec `IDCHANGE=0x40` côté
serveur : les opcodes sont contextualisés par protocole et connexion.

### 4. Types de tags et encodage `NEWTAGS`

Les constantes de `../eMule/opcodes.h` et les méthodes `CTag::WriteNewEd2kTag` / `CTag::ReadTag` de
`../eMule/packets.cpp` établissent pour ce client :

- `UINT64=0x0B`, et non `0x14`;
- `BOOL=0x05`, `BOOLARRAY=0x06`;
- `STR1..STR16=0x11..0x20`, donc `0x14=STR4`;
- sous `NEWTAGS`, les chaînes de 1 à 16 octets sont encodées dans le type correspondant, sans préfixe de longueur de
  chaîne; les entiers sont écrits dans le plus petit type entier correspondant;
- une chaîne trop longue pour `STR1..STR16` utilise `STRING` et sa longueur 16 bits.

`eMule_client_server_list.md` donne `UINT64=0x14`, valeur qui est `STR4` dans le code client. `protocols.md` assimile
`BOOLARRAY` à `BOOL` et présente l’encodage texte à longueur 16 bits sans distinguer le format `NEWTAGS`. Ces
descriptions sont contradictoires avec le client.

`Protocol_Implementation_Notes.md` dit que le code Java préfixe certaines listes de tags d’un compte 32 bits. Le
rétro-spec client ne détaille pas systématiquement ce framing. Cette propriété du code Java ne doit pas être présentée
comme confirmée par le client sans vérifier chaque paquet et son lecteur client.

### 5. Réponse UDP `GLOBSERVSTATRES`

`../eMule/opcodes.h` définit `OP_GLOBSERVSTATRES=0x97`. `../eMule/UDPSocket.cpp` lit les valeurs avec ces offsets :

| Offset données | Champ lu par le client |
|---:|---|
| `+0` | challenge |
| `+4` | utilisateurs |
| `+8` | fichiers |
| `+12` | maximum utilisateurs |
| `+16` | soft files |
| `+20` | hard files |
| `+24` | drapeaux UDP |
| `+28` | utilisateurs LowID |
| `+32` | port UDP d’obfuscation |
| `+34` | port TCP d’obfuscation |
| `+36` | clé UDP |

Le client n’accède aux trois derniers champs que si la charge utile a au moins **40 octets** (`size >= 40`); il signale
les éventuels octets supplémentaires après `+40`. `eMule_client_server_list.md` appelle ces ports simplement UDP/TCP
et annonce 44 octets, alors que les champs qu’il énumère totalisent 40. `Implementation_vs_Spec.md` répète « 44-byte
structure ». Pour la disposition lue par le client, la taille minimale documentée par le code est 40 octets, pas 44.

### 6. Réponse de description serveur UDP

`../eMule/opcodes.h` définit `OP_SERVER_DESC_REQ=0xA2` et `OP_SERVER_DESC_RES=0xA3`.
`../eMule/UDPSocket.cpp` décode `0xA3` selon l’ancien format nom/description ou, si le challenge le permet, selon le
nouveau format tagué. `eMule_Protocol_RetroSpecification.md` donne `0xA3`; `eMule_client_server_list.md` donne `0x99`,
qui est une autre constante (`OP_GLOBSEARCHRES`) dans `opcodes.h`.

### 7. Login : ce que le client exige et ce qu’il ne tranche pas

`../eMule/ServerSocket.cpp` passe l’état client à `CS_CONNECTED` lorsqu’il reçoit `OP_IDCHANGE=0x40`. La réception de
`OP_SERVERIDENT=0x41` sert au traitement des informations serveur et le commentaire du client précise qu’il est reçu
à la suite de `OP_GETSERVERLIST`. `sockets.cpp` envoie `OP_LOGINREQUEST` puis, une fois connecté, peut envoyer
`OP_GETSERVERLIST`.

Cela confirme le rôle de `IDCHANGE` comme signal de connexion pour ce client. Cela ne suffit pas à fixer un ordre
universel pour tous les paquets de handshake envoyés par un serveur. Les séquences divergentes de `protocols.md` et
`Implementation_vs_Spec.md` ne sont donc pas justifiées en les déclarant toutes « conformes ». Le code client consulté
ne définit pas `LOGIN_ACCEPTED=0x1B` comme le signal qui termine le login.

### 8. Kademlia Kad2

`../eMule/opcodes.h` définit le protocole `OP_KADEMLIAHEADER=0xE4` et les opcodes Kad2, notamment
`BOOTSTRAP_REQ/RES=0x01/0x09`, `HELLO_REQ/RES=0x11/0x19`, `REQ/RES=0x21/0x29`,
`SEARCH_KEY_REQ=0x33` et `SEARCH_RES=0x3B`. `../eMule/kademlia/net/KademliaUDPListener.cpp` traite ces commandes
sous ce framing et met en œuvre les échanges Kad2.

L’implémentation locale décrite par `kademlia.md` et `Protocol_Implementation_Notes.md` utilise le byte `0x3A` et des
réponses `0x02`, `0x12`, `0x22`. Elle n’est donc pas compatible sur le fil avec le Kad2 de ce client, même si ses tests
internes réussissent. `Implementation_vs_Spec.md` le signale déjà; les documents qui décrivent le bridge sans cette
réserve doivent être lus avec cette limite.

### 9. Limites des affirmations historiques

Le code eMule 0.50.1 permet d’établir les opcodes, formats et comportements auxquels **ce client** s’attend. Il ne
permet pas, à lui seul, d’établir que Lugdunum a inventé chaque extension ou que toutes les versions d’eMule ont
conservé ces choix. Les attributions historiques de `protocols.md` (par exemple « introduit par Lugdunum » pour
`CT_EMULE_VERSION`) restent des affirmations historiques distinctes, à sourcer séparément. Elles ne doivent pas être
déduites de la seule présence d’une constante dans le client.

## Portée et méthode

- Révision examinée : `../eMule` commit `88a8c8f`, affiché `0.50.1` dans `Version.h`.
- Sources principales : `opcodes.h`, `ServerSocket.cpp`, `sockets.cpp`, `UDPSocket.cpp`, `packets.cpp`,
  `DownloadQueue.cpp`, `kademlia/net/KademliaUDPListener.cpp`.
- Une correction des autres documents doit citer cette révision lorsqu’elle affirme une compatibilité avec ce client.
- Cette comparaison n’est pas un audit complet du code serveur JEmuleServer; les états et écarts propres à
  l’implémentation serveur doivent être vérifiés dans son code et ses tests, indépendamment des attentes du client.
