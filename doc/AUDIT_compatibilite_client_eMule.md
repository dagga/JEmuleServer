# Audit de compatibilité : client eMule ↔ JEmuleServer

- **Date** : 2026-10-12
- **Client de référence** : sources eMule **0.50a** (`/home/dagga/IdeaProjects/eMule`, `Version.h` : 0.50.1)
- **Serveur audité** : JEmuleServer, branche `main`, commit `5cc2106` (« Fix obfuscation »)
- **Méthode** : lecture du code client (`ServerSocket.cpp`, `sockets.cpp`, `EncryptedStreamSocket.cpp`, `UDPSocket.cpp`, `packets.cpp`, `SharedFileList.cpp`, `SearchResultsWnd.cpp`, `SearchList.cpp`, `SearchFile.cpp`, `DownloadQueue.cpp`, `PartFile.cpp`, `BaseClient.cpp`, `opcodes.h`, `server.h`, `Preferences.h`), puis comparaison octet par octet avec ce qu'émet et ce qu'attend JEmuleServer. Les autres documents de `doc/` n'ont **pas** servi de référence : ce qui est écrit ici vient uniquement du code du client et du serveur.

> Tous les entiers du protocole ed2k sont en **little-endian**. Les chemins côté client sont relatifs à la racine des sources eMule ; ceux côté serveur sont relatifs à `src/main/java/org/jemule/`.

---

## 1. Synthèse

Avec un client eMule 0.50a dans sa **configuration par défaut**, JEmuleServer **accepte la connexion** et le client s'affiche « connecté » avec un HighID. En revanche, **les trois services essentiels d'un serveur ed2k ne fonctionnent pas correctement** :

| Service | État | Cause principale |
|---|---|---|
| Connexion / login (TCP en clair) | ✅ Fonctionne | — |
| Connexion obfusquée (TCP) | ❌ Échoue toujours (le client repasse ensuite en clair) | Magic DH écrit en big-endian, padding client non consommé, faux « mode 0x97 » |
| Publication des fichiers partagés | ❌ Très partielle | Types de tags « NEWTAGS » non gérés, entiers lus comme signés, heuristique `data[0] < 32` |
| Recherche (TCP) | ⚠️ Dégradée | Compteur de résultats en big-endian, type de fichier entier, mots-clés non ASCII qui renvoient tout l'index |
| Recherche de sources (TCP) | ❌ Ignorée par défaut | `0x23` (GETSOURCES_OBFU) est interprété comme un ID_CHANGE ; réponse fausse dans tous les cas |
| Recherche de sources (UDP) | ❌ Inopérante | Le client chiffre tous ses paquets UDP vers le serveur, qui ne les déchiffre pas ; IP/port en big-endian |
| Sources renvoyées | ❌ Inutilisables | Port éphémère au lieu du port d'écoute, une seule source par fichier, sources jamais purgées |
| LowID / callback | ❌ Absent | HighID attribué à tout le monde sans test de joignabilité ; mauvais opcode de callback |
| Recherche globale UDP, « plus de résultats » | ❌ / ⚠️ | Pas de traitement de `OP_GLOBSEARCHREQ*`, pas d'octet « more » |

**Verdict** : JEmuleServer **n'atteint pas** aujourd'hui le service minimal d'un serveur Lugdunum. Un client peut se connecter, mais il ne peut pas publier de façon fiable, il trouve des résultats incomplets ou faux et, surtout, **il ne peut obtenir aucune source exploitable**. Le transfert de fichiers entre utilisateurs du serveur ne marche donc pas.

La plupart des défauts sont **localisés et faciles à corriger** (boutisme, table d'opcodes, types de tags). Le plan de correction est donné en section 4.

### Tableau des constats

| # | Sév. | Domaine | Constat |
|---|---|---|---|
| C1 | 🔴 Bloquant | Sources TCP | `OP_GETSOURCES_OBFU` (0xE3/0x23) est routé vers `ID_CHANGE_V2` : la requête est ignorée |
| C2 | 🔴 Bloquant | Sources TCP | La réponse est émise en protocole 0xC5, que le client rejette sur la socket serveur ; il manque aussi l'octet d'options de chiffrement |
| C3 | 🔴 Bloquant | Sources TCP/UDP | IP et port des sources écrits en big-endian (`DataOutputStream`) |
| C4 | 🔴 Bloquant | Sources | Le port de la source est le port TCP éphémère de la connexion au serveur, pas le port d'écoute annoncé au login |
| C5 | 🔴 Bloquant | Index | `byHash.put()` écrase le fichier à chaque publication : une seule source par fichier |
| C6 | 🔴 Bloquant | Index | Sources et fichiers jamais retirés à la déconnexion |
| C7 | 🔴 Bloquant | Tags | Types `TAGTYPE_STR1..STR16` (0x11–0x20) non gérés ; `TYPE_UINT64 = 0x14` au lieu de `0x0B` |
| C8 | 🔴 Bloquant | Publication | Heuristique `data[0] < 32` : un client qui partage 32 à 127 fichiers voit toute sa publication ignorée |
| C9 | 🔴 Bloquant | Publication | Entiers `uint8/uint16/uint32` lus comme signés : fichiers de 2 à 4 Go, et certaines tailles courtes, rejetés |
| C10 | 🔴 Bloquant | UDP | `SRV_UDPFLG_UDPOBFUSCATION` est annoncé alors que l'UDP chiffré n'est pas implémenté : tout l'UDP du client est perdu après le premier ping |
| C11 | 🔴 Bloquant | Obfuscation TCP | Le handshake DH échoue toujours (magic en big-endian, padding non consommé, faux mode « 0x97 ») |
| C12 | 🟠 Majeur | Recherche | Compteur de résultats écrit en big-endian |
| C13 | 🟠 Majeur | Publication/Recherche | `FT_FILETYPE` est reçu en entier (TYPETAGINTEGER), stocké sous forme de chaîne `"2"` et renvoyé tel quel : le filtre par type ne marche jamais |
| C14 | 🟠 Majeur | Recherche | Mots-clés non ASCII : tous les tokens deviennent vides, et la requête correspond à **tout** l'index |
| C15 | 🟠 Majeur | Recherche | Paramètre numérique 64 bits (type 8) non géré : la requête se rabat sur une « recherche texte » des octets bruts |
| C16 | 🟠 Majeur | Login | HighID systématique, sans test de joignabilité : les clients NATés sont injoignables |
| C17 | 🟠 Majeur | Callback | Le serveur répond avec `0x99` au lieu de `OP_CALLBACKREQUESTED` (0x35), avec le port inversé et faux, et sans `OP_CALLBACK_FAIL` |
| C18 | 🟠 Majeur | UDP | `OP_GLOBSERVSTATRES` place `port+4` à l'emplacement du port UDP d'obfuscation |
| C19 | 🟠 Majeur | UDP | Paquet inventé `0x9C` « GLOBFOUNDSOURCES_V6 » : 0x9C est en réalité `OP_GLOBCALLBACKREQ` |
| C20 | 🟡 Mineur | Recherche | Pas d'octet « more » final ; `OP_QUERY_MORE_RESULT` ne peut donc pas être déclenché par l'interface |
| C21 | 🟡 Mineur | Recherche | Pas de recherche globale UDP (`OP_GLOBSEARCHREQ`/`2`/`3`) |
| C22 | 🟡 Mineur | Recherche | Les bornes de taille sont décalées de 1 (`>=`/`<=` appliqués à `val-1`/`val+1`) |
| C23 | 🟡 Mineur | Identité | `ST_VERSION` = 17 s'affiche « 0.17 » (le format attendu est `maj<<16 | min`) |
| C24 | 🟡 Mineur | Login | Paquets inutiles ou inconnus du client : `0x1B`, `0x4A` (en 0xC5 et en 0xE3), `0x22`, et un datagramme UDP envoyé au port **TCP** du client |
| C25 | 🟡 Mineur | Robustesse | `Packet.decompress` boucle à l'infini sur un flux zlib tronqué |
| C26 | 🟡 Mineur | Login | Les tags du login (`CT_NAME`, `CT_VERSION`, `CT_SERVER_FLAGS`, `CT_EMULE_VERSION`) ne sont pas lus |

---

## 2. Ce que fait le client eMule (référence)

### 2.1 Séquence de connexion TCP

1. `CServerConnect::ConnectToServer` → `CServerSocket::ConnectTo` (`ServerSocket.cpp`).
   Si `IsServerCryptLayerTCPRequested()` est vrai (option « préférer les connexions obfusquées », **désactivée par défaut**) et que le serveur annonce un port d'obfuscation et le flag TCPOBFUSCATION, le client se connecte au port d'obfuscation en mode DH. Sinon, il se connecte en clair.
2. En cas d'échec d'une connexion obfusquée, le client **réessaie en clair** sauf si le chiffrement est « requis » (`sockets.cpp`, `CServerConnect::ConnectionFailed` / `TryAnotherConnectionRequest`).
3. `CServerConnect::ConnectionEstablished` (`sockets.cpp`) envoie **`OP_LOGINREQUEST` (0xE3/0x01), non compressé** :
   `<userhash 16><clientID 4 = 0><port TCP d'écoute 2><tagcount 4 = 4>` puis 4 tags au format **ancien** (`WriteTagToFile` : `type`, `uint16 namelen = 1`, `nameID`) :
   `CT_NAME` (chaîne), `CT_VERSION` = 0x3C, `CT_SERVER_FLAGS` = `SRVCAP_ZLIB|NEWTAGS|LARGEFILES|UNICODE` + bits de chiffrement, `CT_EMULE_VERSION`.
4. Le client passe en `CS_CONNECTED` **uniquement** à la réception de `OP_IDCHANGE` (0x40). Il lit alors (`CServerSocket::ProcessPacket`) :
   `<clientID 4>[<TCP flags 4>][<inutilisé 4>][<IP rapportée 4>][<port TCP d'obfuscation 4>]`.
5. Dès qu'il est connecté, il appelle `SendListToServer()` (publication), puis `OP_GETSERVERLIST` si l'option est active.
6. Opcodes traités sur la socket serveur : `0x38, 0x40, 0x33, 0x42, 0x44, 0x34, 0x41, 0x32, 0x35, 0x36, 0x05`. Tout paquet de protocole autre que 0xE3 (ou 0xD4 décompressé) est **ignoré** (`CServerSocket::PacketReceived`).
7. Keep-alive : une `OP_OFFERFILES` vide (`<count 4 = 0>`) si `ServerKeepAliveTimeout` > 0 (valeur par défaut : 0, donc désactivé).

### 2.2 Effets des flags annoncés par le serveur

Les flags que JEmuleServer annonce **changent ce que le client envoie**. Il faut donc honorer chacun d'eux :

| Flag annoncé | Conséquence côté client |
|---|---|
| `SRV_TCPFLG_COMPRESSION` (0x01) | `OP_OFFERFILES` compressé (0xD4) ; dans l'offre, ID/port remplacés par `0xFBFBFBFB/0xFBFB` (fichier complet) ou `0xFCFCFCFC/0xFCFC` (fichier partiel) |
| `SRV_TCPFLG_NEWTAGS` (0x08) | Tags d'offre écrits avec `WriteNewEd2kTag` : chaînes de 1 à 16 octets en `TAGTYPE_STR1..STR16` (0x11–0x20), entiers compactés en `UINT8`/`UINT16`/`UINT32` |
| `SRV_TCPFLG_UNICODE` (0x10) | Chaînes en UTF-8 brut |
| `SRV_TCPFLG_TYPETAGINTEGER` (0x80) | `FT_FILETYPE` envoyé **en entier** (`ED2KFT_AUDIO=1 … CDIMAGE=7`) |
| `SRV_TCPFLG_LARGEFILES` (0x100) | Publication de fichiers > 4 Go (`FT_FILESIZE` + `FT_FILESIZE_HI`) ; recherches avec paramètres 64 bits (type 8) ; `GETSOURCES` avec `<0 4><taille 8>` |
| `SRV_TCPFLG_TCPOBFUSCATION` (0x400) | `SupportsGetSourcesObfuscation()` : le client envoie **`OP_GETSOURCES_OBFU` (0xE3/0x23)** au lieu de `OP_GETSOURCES`, dès que `CryptLayerSupported` est vrai (**vrai par défaut**) |
| `SRV_UDPFLG_EXT_GETSOURCES` (0x01) | `OP_GLOBGETSOURCES` contient jusqu'à 35 hashes |
| `SRV_UDPFLG_UDPOBFUSCATION` (0x200) | Si la clé UDP du serveur ≠ 0, **tous** les paquets UDP vers le serveur sont chiffrés et envoyés au port UDP d'obfuscation (`CUDPSocket::SendBuffer`) |

---

## 3. Constats détaillés

### 3.1 Recherche de sources (TCP) : C1, C2, C3

**Client** (`DownloadQueue.cpp`, `ProcessLocalRequests`) :
```
<hash 16><taille 4>            (ou <hash 16><0 4><taille 8> pour un gros fichier)
protocole 0xE3, opcode 0x23 (OP_GETSOURCES_OBFU) si CryptLayerSupported && serveur TCPOBFUSCATION
                     sinon 0x19 (OP_GETSOURCES)
```
Réponses acceptées (`ServerSocket.cpp`, en 0xE3 uniquement) :
- `OP_FOUNDSOURCES` 0x42 : `<hash 16><count 1>(<ID 4><port 2>)*`
- `OP_FOUNDSOURCES_OBFU` 0x44 : `<hash 16><count 1>(<ID 4><port 2><cryptOptions 1>[<userhash 16> si cryptOptions & 0x80])*` (`CPartFile::AddSources`)

**JEmuleServer** :
- `protocol/OpCode.java:30` et `:71` : en 0xE3, `GET_SOURCES_OBFU` est explicitement sauté et `0x23` correspond à `ID_CHANGE_V2`. `PacketProcessor.handleIdChange` se contente d'écrire un log. **Avec la configuration par défaut du client, aucune requête de sources TCP n'aboutit.** (`OP_IDCHG 0x23` n'existe pas dans le protocole client-serveur.)
- `network/handler/SourceHandler.java:76` : pour une requête en 0xC5, la réponse part en **protocole 0xC5**, que le client rejette (« unknown protocol »). Le client n'envoie de toute façon jamais de 0xC5 au serveur.
- `SourceHandler.java:68-69` : `DataOutputStream.writeInt/writeShort` écrivent en **big-endian**. Comme `ipToLong()` renvoie déjà la valeur little-endian de l'IP, l'adresse reçue est **inversée** (1.2.3.4 devient 4.3.2.1) et le port aussi.
- Le format `_OBFU` (octet `cryptOptions` par source) n'est pas produit.

**Correction** :
1. Supprimer `ID_CHANGE_V2` et mapper `0xE3/0x23` vers `GET_SOURCES_OBFU`.
2. Toujours répondre en 0xE3 : `0x44` avec `cryptOptions` (bit 0x01 supporté, 0x02 demandé, 0x04 requis, 0x80 hash présent, tirés du `CT_SERVER_FLAGS` du login de la source) si la requête était `0x23`, sinon `0x42`.
3. Remplacer `DataOutputStream` par un `ByteBuffer` en `LITTLE_ENDIAN`.

### 3.2 Contenu de l'index des sources : C4, C5, C6

- **C4** : `PublishHandler.java:115` enregistre `context.getState()`, dont `port()` vaut `socket.getPort()` (`LoginHandler.java:34`), c'est-à-dire le **port source éphémère** de la connexion du client au serveur. Le port d'écoute réel est celui du login (`<port 2>` après l'ID) : `LoginHandler` le lit (`:95`) mais seulement sous le nom trompeur `clientUdpPort`, puis le jette. Les clients reçoivent donc des sources sur lesquelles ils ne peuvent pas se connecter.
- **C5** : `core/FileIndex.java:92` `byHash.put(meta.hash(), meta)` remplace l'entrée existante par un nouveau `FileMetadata` qui ne contient **que** le dernier publieur. Un fichier n'a donc jamais plus d'une source, et `FT_SOURCES` vaut toujours 1.
- **C6** : `ClientHandler.cleanup()` retire le client du registre mais pas des `sources()` des fichiers. Les fichiers d'un client déconnecté restent indexés et sont renvoyés comme sources mortes, y compris après redémarrage via la base.

**Correction** : stocker dans `ClientState` le port d'écoute annoncé (et les flags de chiffrement et le userhash) ; faire une fusion (`computeIfAbsent` + ajout de la source) dans `addFile` ; à la déconnexion, retirer la source de chaque fichier publié par ce client, puis retirer les fichiers qui n'ont plus de source. Le compteur `FT_SOURCES` et `FT_COMPLETE_SOURCES` (0x30, voir les marqueurs `0xFB…`/`0xFC…`) peuvent alors être exacts.

### 3.3 Décodage des tags : C7, C9

**Client** (`packets.cpp`) :
- Types : `HASH 0x01, STRING 0x02, UINT32 0x03, FLOAT32 0x04, BOOL 0x05, BOOLARRAY 0x06, BLOB 0x07, UINT16 0x08, UINT8 0x09, BSOB 0x0A, UINT64 0x0B, STR1..STR16 0x11..0x20`.
- `WriteNewEd2kTag` (utilisé pour l'offre de fichiers dès que NEWTAGS est annoncé) : une chaîne de 1 à 16 octets s'écrit `type=0x10+len` **sans** champ longueur ; un entier s'écrit dans le plus petit type `UINT8/16/32/64` possible.

**JEmuleServer** (`protocol/Tag.java`) :
- `TYPE_UINT64 = 0x14` (`:23`) : 0x14 est en réalité **`TAGTYPE_STR4`**. Une chaîne de 4 octets (codec « xvid », « h264 », « divx », extension…) est lue comme un `long` de 8 octets, ce qui désaligne silencieusement toute la suite du paquet.
- `0x0B` (vrai UINT64) et `0x11–0x20` (STR1..STR16) lèvent `Unknown tag type` (`:231`). `readList` s'arrête alors et `PublishHandler` abandonne **tous les fichiers suivants** du paquet.
- `TYPE_BOOL_ALT = 0x06` est traité comme 1 octet alors que BOOLARRAY a le format `<len 2><bits>`.
- **C9** : `UINT32` est lu par `getInt()` (signé, `:190`), `UINT16` par `getShort()`, `UINT8` par `get()`. Une taille ≥ 2 Gio (< 4 Gio), ou une taille courte de 128–255 ou 32 768–65 535 octets, donne une valeur négative, et `PublishHandler.java:110` rejette le fichier (`size < 0`).

**Correction** : aligner les constantes sur `opcodes.h` ; ajouter STR1..STR16, UINT64 = 0x0B, BOOLARRAY et BSOB ; lire les entiers non signés (`Integer.toUnsignedLong`, `& 0xFFFF`, `& 0xFF`) ; ne jamais interrompre la lecture d'une liste de tags pour un type inconnu de taille connue.

### 3.4 Publication : C8, C13

**Client** (`SharedFileList.cpp`, `SendListToServer` / `CreateOfferedFilePacket`) : `OP_OFFERFILES` (0x15) = `<count 4>` puis, pour chaque fichier, `<hash 16><ID 4><port 2><tagcount 4><tags…>`. Au maximum `min(ST_SOFTFILES, 200)` fichiers par paquet, compressé en 0xD4. Tags envoyés : `FT_FILENAME`, `FT_FILESIZE` [+ `FT_FILESIZE_HI`], `FT_FILERATING` (uint8), `FT_FILETYPE` (**entier** car TYPETAGINTEGER est annoncé, chaîne seulement pour les collections), `FT_MEDIA_LENGTH/BITRATE/CODEC`.

**JEmuleServer** (`network/handler/PublishHandler.java`) :
- **C8** (`:32`) : `if (data[0] < 32)` décide entre format « binaire » et un format « texte » `hash|nom|taille|type` qui n'existe pas dans eMule. `data[0]` est l'octet de poids faible du compteur : **pour 32 à 127 fichiers, toute l'offre part dans la branche texte et est ignorée**.
- `:54` : l'ID et le port de l'offre sont ignorés. Il faudrait au minimum reconnaître `0xFBFBFBFB` (complet) et `0xFCFCFCFC` (partiel) pour `FT_COMPLETE_SOURCES`, et utiliser l'ID et le port de la session pour les autres valeurs.
- **C13** : `FT_FILETYPE` arrive en `UINT8` et devient `String.valueOf(Byte)`, soit `"2"`. Il est ensuite renvoyé tel quel dans les résultats (`SearchHandler.java:94`). Le client l'affiche comme type de fichier, et `CSearchList::AddToList` **supprime** le résultat lorsqu'un type de recherche est choisi (`"Video" ≠ "2"`). De son côté, la requête de recherche envoie le type en **chaîne** (`"Audio"`, `"Video"`, `"Image"`, `"Pro"`, `"Doc"`, voir `GetSearchPacket`) et ne peut jamais correspondre.
- Réponse `0x22` (`PUBLISH_ACK`) : opcode inconnu du client, sans effet. À supprimer.
- `FakeFileDetector` peut envoyer un `OP_SERVERMESSAGE` par fichier rejeté. Avec 200 fichiers, c'est potentiellement une avalanche de messages.

**Correction** : supprimer l'heuristique et le format texte ; la publication est toujours binaire. Normaliser `FT_FILETYPE` à la réception (entier 1..7 → `Audio/Video/Image/Pro/Doc/Pro/Pro`, voir `GetED2KFileTypeSearchTerm`) et renvoyer la chaîne.

### 3.5 Recherche : C12, C14, C15, C20, C21, C22

**Client** (`SearchResultsWnd.cpp`, `CSearchExprTarget` / `GetSearchPacket`) :
- Opérateurs `00 00` (AND), `00 01` (OR), `00 02` (NOT), en préfixe.
- Mot-clé : `01 <len 2><utf8>`.
- Méta chaîne : `02 <len 2><valeur><0x0001><id 1>` (FT_FILETYPE, FT_FILEFORMAT, FT_MEDIA_CODEC…).
- Numérique : `03 <uint32><op 1><0x0001><id 1>`, **ou `08 <uint64><op 1>…` si la valeur dépasse 4 Gio et que le serveur annonce LARGEFILES**. Le client transforme `>=v` en `> v-1` (op 1) et `<=v` en `< v+1` (op 2). Les identifiants utilisés sont FT_FILESIZE, FT_SOURCES, FT_COMPLETE_SOURCES, FT_MEDIA_BITRATE et FT_MEDIA_LENGTH.
- Réponse attendue `OP_SEARCHRESULT` (0x33) : `<count 4>(<hash 16><ID 4><port 2><tags>)*[<more 1>]` (`CSearchList::ProcessSearchAnswer`). L'octet final `0x01` active le bouton « Plus », qui envoie `OP_QUERY_MORE_RESULT` (0x21).

**JEmuleServer** :
- **C12** (`SearchHandler.java:77`) : `dos.writeInt(results.size())` est en big-endian. Pour 1 résultat, le client lit 16 777 216. Il ajoute les vrais résultats puis lève une exception de fin de flux, rattrapée pour `OP_SEARCHRESULT` ; mais `LocalEd2kSearchEnd()` n'est jamais appelé et la recherche reste « en cours » jusqu'au timeout. Le fonctionnement « à peu près » observé n'est qu'un effet de bord.
- **C14** (`core/SearchQuery.java:69`) : `split("[^a-zA-Z0-9]+")` supprime toutes les lettres non ASCII. Pour « été », « Привет » ou « 日本 », tous les tokens sont vides et `TermQuery` renvoie `true` : **la recherche renvoie n'importe quel fichier de l'index**. Il faut un découpage Unicode (`\p{L}\p{N}`), cohérent avec `FileIndex.TOKENIZER`.
- **C15** : le type `0x08` (numérique 64 bits) n'est pas géré, ce qui provoque une exception. Le serveur se rabat alors sur `FileIndex.search(new String(data))`, une recherche sur les **octets binaires de la requête**, dont le résultat n'a aucun sens. Il faut gérer le type 8, et en cas d'échec de parsing répondre 0 résultat plutôt que d'appliquer ce repli.
- **C22** : `MODE_MIN=1` est appliqué comme `>=` sur `v-1` et `MODE_MAX=2` comme `<=` sur `v+1`. Il faut implémenter les opérateurs 0..5 (`ED2K_SEARCH_OP_*`) avec leur vraie sémantique (`>` et `<` stricts).
- Les filtres `FT_SOURCES`, `FT_COMPLETE_SOURCES`, `BITRATE`, `LENGTH` et `CODEC` sont ignorés (toujours vrais).
- **C20** : l'octet « more » n'est jamais envoyé, alors que le serveur garde des résultats en attente par paquets de 50. Il faut ajouter `0x01`/`0x00` en fin de paquet.
- **C21** : `Server.handleUdp` ne traite pas `OP_GLOBSEARCHREQ` (0x98), `REQ2` (0x92) ni `REQ3` (0x90). La recherche « globale » ne renvoie rien depuis JEmuleServer.

### 3.6 Login, attribution d'ID et callback : C16, C17, C24, C26

- **C16** (`LoginHandler.java:32`) : l'ID vaut toujours l'IP de la socket, donc HighID. Lugdunum ouvre d'abord une connexion vers `<IP>:<port d'écoute>` (c'est pour ce test que le client envoie `CT_EMULE_VERSION`, voir le commentaire dans `sockets.cpp`) et n'attribue un HighID qu'en cas de succès ; sinon il donne un LowID (< 16 777 216). Sans ce test, un client derrière un NAT reçoit un HighID, ses sources sont injoignables et le mécanisme de callback n'est jamais utilisé. Effet secondaire : une IP dont le dernier octet vaut 0 (`a.b.c.0`) produit un ID < 2^24, que le client considère comme un LowID.
- **C17** (`SourceHandler.handleCallback`) : le client (`BaseClient.cpp`) envoie `OP_CALLBACKREQUEST` 0x1C `<ID 4>`. Le serveur doit envoyer à la cible **`OP_CALLBACKREQUESTED` (0x35)** `<IP 4><port 2>[<cryptOptions 1><userhash 16>]` avec l'IP et le **port d'écoute** du demandeur, ou bien répondre `OP_CALLBACK_FAIL` (0x36) au demandeur. JEmuleServer envoie `0x99` (l'opcode client-client `OP_CALLBACK`, inconnu sur la socket serveur), avec `Short.reverseBytes(port)` (`:103`) appliqué au port éphémère, et ne répond rien en cas d'échec.
- **C24** : `OP_IDCHANGE` (0x40) et `OP_SERVERIDENT` (0x41) sont corrects. En revanche :
  - `0x1B` (`LOGIN_ACCEPTED`) n'est pas un opcode serveur → client ; il est ignoré ;
  - `0x4A` est envoyé en 0xC5 (rejeté) **et** en 0xE3 (opcode inconnu) ; il est inutile ;
  - un datagramme `GLOBSERVSTATRES`/`SERVER_DESC_RES` non sollicité part vers le **port TCP** du client (`:95` puis `sendUdpStatusToClient`) ;
  - 31 `OP_SERVERSTATUS` sont envoyés pendant les 5 premières minutes par un thread dédié à chaque client, en plus du heartbeat.
  Tout cela est à retirer : le client ne s'en sert pas.
- **C26** : les tags du login ne sont pas exploités. `CT_SERVER_FLAGS` indique pourtant le support zlib (le serveur n'active la compression que si le login est en 0xD4, ce qu'eMule ne fait jamais), le support Unicode et les options de chiffrement nécessaires à `OP_FOUNDSOURCES_OBFU` et `OP_CALLBACKREQUESTED`. Le pseudo `CT_NAME` n'est pas non plus conservé.

### 3.7 Obfuscation TCP : C11

**Client** (`EncryptedStreamSocket.cpp`, en-tête et `StartNegotiation`/`Negotiate`, état `ECS_PENDING_SERVER`) :
```
Client  : <marqueur 1 ≠ E3/C5/D4><g^a 96><padding aléatoire 0-15>      (en clair)
Serveur : <g^b 96>(en clair) RC4[ <MAGICVALUE_SYNC 4 LE><méthodes 1><préférée 1><padLen 1><pad> ]
Client  : RC4[ <MAGICVALUE_SYNC 4 LE><méthode 1><padLen 1><pad> ]      (envoyé avec le 1er paquet)
Clés    : S = (g^a)^b mod p sur 96 octets ; envoi serveur = MD5(S‖203) ; réception = MD5(S‖34) ; 1024 octets RC4 jetés
```

**JEmuleServer** (`network/handler/ObfuscationHandler.java`) :
1. `:66` : la lecture s'arrête au premier octet `0x97` trouvé dans `g^a`, qui est aléatoire, ce qui arrive dans **environ 30 %** des connexions. Le serveur bascule alors dans un « mode simplifié 0x97 » qui **n'existe pas** dans eMule (clé `MD5(0x97‖4 octets)`), et le handshake échoue.
2. `:212-213` : `ByteBuffer.allocate(…)` est big-endian par défaut, si bien que `MAGICVALUE_SYNC` part en `83 5E 6F C4` alors que le client attend `C4 6F 5E 83` (`ReadUInt32` little-endian). Le client répond « wrong magic value after DH-Agreement » et coupe : **le handshake échoue dans 100 % des cas restants**.
3. `:70` : le padding en clair (0–15 octets) du client n'est pas consommé. Il passe ensuite dans le déchiffreur RC4 et décale le flux de clé.
4. `:252`, `:272` : la recherche du magic côté réception compare d'abord l'octet de poids **fort** (0x83), alors que le premier octet reçu est le poids faible (0xC4).
5. `handleObfuscatedHandshake()` et `Obfuscation.isReplay()` implémentent un protocole qui n'existe pas et ne sont pas utilisés par le flux réel.

**Impact** : les clients avec « préférer obfuscation » font une tentative inutile puis repassent en clair (délai à chaque connexion). Les clients avec « obfuscation requise » **ne peuvent pas se connecter**.

**Correction** :
- Détection : 1er octet ∉ {E3, C5, D4} → lire exactement 1 + 96 octets, puis **tout ce qui est disponible sans bloquer** (le padding, envoyé dans le même segment) avant de répondre.
- Magic en little-endian.
- Côté réception, chercher `C4 6F 5E 83` après déchiffrement, à partir de l'octet 0 du flux RC4.
- Supprimer entièrement le « mode 0x97 ».
- Écrire un test croisé avec un vecteur produit par le code C++ (ou une réimplémentation du côté client en Java dans le test).

### 3.8 UDP serveur : C10, C18, C19, C23

**Client** (`UDPSocket.cpp`) :
- Ping : `OP_GLOBSERVSTATREQ` (0x96) `<challenge 4>` envoyé à `port+4`.
- La réponse `OP_GLOBSERVSTATRES` est lue ainsi : `+0 challenge`, `+4 users`, `+8 files`, `+12 maxusers`, `+16 soft`, `+20 hard`, `+24 UDPflags`, `+28 lowid`, **`+32 port UDP d'obfuscation`, `+34 port TCP d'obfuscation`, `+36 clé UDP`**.
- Dès que `clé ≠ 0` et `UDPflags & 0x200`, et puisque `CryptLayerSupported` est vrai par défaut, `CUDPSocket::SendBuffer` **chiffre chaque paquet** (`EncryptSendServer`) et l'envoie à `GetObfuscationPortUDP()`.

**JEmuleServer** (`network/Server.java`) :
- **C10** : `UDPFLG_UDPOBFUSCATION` est annoncé avec une clé aléatoire (`:368-373`), mais `handleUdp` jette tout datagramme dont le 1er octet n'est ni E3 ni C5 (`:341`, « not fully implemented »). Après le premier ping réussi, **tous les pings, demandes de sources UDP et recherches globales du client sont perdus**. Le serveur finit compté comme « ne répondant pas » en UDP. **Correction immédiate** : ne plus annoncer `0x200` (ni la clé) tant que `EncryptedDatagramSocket` (protocole client→serveur : clé `MD5(<clé serveur 4><0x6B><random 2>)`, réponse avec `0xA5`, magic `0x13EF24D5` ; voir l'en-tête de `EncryptedDatagramSocket.cpp`) n'est pas implémenté.
- **C18** (`:371`) : `port+4` est placé à l'offset +32 (port UDP d'**obfuscation**). Si le flag 0x200 est conservé, il faut y mettre le vrai port UDP d'obfuscation ; sinon, 0.
- **C3 (UDP)** (`:438`) : `OP_GLOBFOUNDSOURCES` (0x9B) a lui aussi IP et port en big-endian. De plus, seule la **première** des 35 hashes d'un `OP_GLOBGETSOURCES` est traitée ; le client accepte plusieurs blocs `<hash><count>(…)` concaténés dans une seule réponse.
- **C19** (`:450`) : le paquet `0x9C` « V6 » est une invention. `0x9C` est `OP_GLOBCALLBACKREQ` dans `opcodes.h`. À retirer.
- `OP_GLOBGETSOURCES2` (0x94, `<hash 16><taille 4>[…]`) n'est pas implémenté. C'est cohérent tant que `SRV_UDPFLG_EXT_GETSOURCES2` n'est pas annoncé (il ne l'est pas).
- `OP_SERVER_DESC_RES` au nouveau format (challenge `xxxxF0FF`) est correct. **C23** : `ST_VERSION` entier = 17 s'affiche « 0.17 » (`strVersion.Format("%u.%02u", v>>16, v&0xFFFF)`). Il faut envoyer `(17<<16)|15`, ou une chaîne `"17.15"`.

### 3.9 Robustesse : C25

- `network/Packet.java:167` : `while (!i.finished())` : si le flux zlib est tronqué, `inflate()` renvoie 0 avec `needsInput()` vrai et la boucle tourne **indéfiniment** (déni de service par un simple paquet 0xD4). Ajouter `if (count == 0 && (i.needsInput() || i.needsDictionary())) throw …` et une borne sur la taille décompressée (le client utilise 250 000 octets pour ce qui vient du serveur).
- Un fichier de debug `build/packet-debug.log` est écrit à chaque paquet en mode DEBUG (I/O synchrone par paquet).

---

## 4. Plan de correction recommandé

Par ordre d'impact, chaque étape étant testable isolément avec un client eMule 0.50a :

1. **Boutisme** : bannir `DataOutputStream` des réponses protocole (`SearchHandler`, `SourceHandler`, `Server.handleUdp`) ; magic DH en LE. *(C3, C11 en partie, C12)*
2. **Table d'opcodes** : `0xE3/0x23` → `GET_SOURCES_OBFU` ; réponses de sources toujours en 0xE3 (0x42 ou 0x44 avec `cryptOptions`) ; retirer `ID_CHANGE_V2`, `0x9C` V6, `0x1B`, `0x4A`, `0x22`. *(C1, C2, C19, C24)*
3. **Tags** : constantes alignées sur `opcodes.h`, STR1..16, UINT64 = 0x0B, entiers non signés. *(C7, C9)*
4. **Publication** : suppression du format texte, normalisation de `FT_FILETYPE`, gestion des marqueurs `0xFB…`/`0xFC…`. *(C8, C13)*
5. **Modèle de sources** : port d'écoute issu du login, fusion des sources par hash, purge à la déconnexion. *(C4, C5, C6)*
6. **UDP** : ne plus annoncer `SRV_UDPFLG_UDPOBFUSCATION` ni de clé (ou bien implémenter le chiffrement), multi-hash dans `GLOBGETSOURCES`, offsets corrects de `GLOBSERVSTATRES`. *(C10, C18)*
7. **Recherche** : tokenisation Unicode, type numérique 8, opérateurs 0..5, octet « more », 0 résultat en cas d'erreur de parsing. *(C14, C15, C20, C22)*
8. **LowID et callback** : test de joignabilité au login, `OP_CALLBACKREQUESTED`/`OP_CALLBACK_FAIL`. *(C16, C17)*
9. **Obfuscation TCP** : réécriture du handshake DH (ou, d'ici là, ne plus annoncer TCPOBFUSCATION ni de port d'obfuscation). *(C11)*
10. **Finitions** : `ST_VERSION`, recherche globale UDP, garde-fou zlib, parsing des tags du login. *(C21, C23, C25, C26)*

**Mesure conservatrice immédiate** : tant que les étapes 6 et 9 ne sont pas faites, retirer `TCPFLG_TCPOBFUSCATION`, `UDPFLG_TCPOBFUSCATION`, `UDPFLG_UDPOBFUSCATION`, la clé UDP et les ports d'obfuscation des réponses `OP_IDCHANGE`, `OP_SERVERIDENT`, `GLOBSERVSTATRES` et `SERVER_DESC_RES`. Le client enverra alors `OP_GETSOURCES` (0x19) et de l'UDP en clair, deux chemins beaucoup plus proches de fonctionner.

### Tests de non-régression suggérés

Les tests existants (`EmuleSpecTest`, `ServerTagsTest`) vérifient JEmuleServer contre sa propre compréhension du protocole. Il faudrait ajouter des tests qui **rejouent des trames construites exactement comme le client** :
- un `OP_OFFERFILES` compressé avec `WriteNewEd2kTag` (nom court en STR*, taille en UINT16, type en UINT8, codec « xvid ») et 50 fichiers ;
- un `OP_GETSOURCES_OBFU` `<hash><taille>` et la vérification octet par octet de la réponse 0x44 ;
- un `OP_SEARCHRESULT` relu avec la logique de `CSearchList::ProcessSearchAnswer` (count LE + octet more) ;
- un handshake DH complet, côté client simulé en Java d'après `EncryptedStreamSocket.cpp`.

---

## 5. Points conformes

- Encapsulation TCP `<proto 1><len 4 LE><opcode 1><payload>` et décompression 0xD4.
- `OP_SERVERMESSAGE` : `<len 2><texte>`, avec « server version x.y » reconnu par le client.
- `OP_IDCHANGE` sur 20 octets : ID, flags TCP, IP rapportée, port d'obfuscation aux bons offsets.
- `OP_SERVERIDENT` : `<hash><IP><port><tags>` ; les tags `ST_SERVERNAME`/`ST_DESCRIPTION` sont bien lus par le client (les tags inconnus sont ignorés sans erreur).
- `OP_SERVERSTATUS` `<users 4><files 4>`.
- `OP_SERVERLIST` vide (`count = 0`), valide.
- `OP_GLOBSERVSTATREQ`/`RES` : challenge renvoyé, et offsets 0 à 31 corrects.
- `OP_SERVER_DESC_REQ`/`RES` : détection du nouveau format par `0xF0FF` correcte.
- Parsing de l'arbre de recherche (AND/OR/NOT, mots-clés, méta-chaînes à ID numérique, numérique 32 bits).
- Keep-alive client (`OP_OFFERFILES` vide) accepté sans effet de bord.
- Constantes DH (`dh768_p`, g = 2, a/b de 128 bits, magic values 34/203, rejet des 1024 premiers octets RC4) identiques au client.
