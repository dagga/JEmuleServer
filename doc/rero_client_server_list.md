### Rétro-spécification de l'affichage de la liste des serveurs eMule

L'analyse du code source d'eMule a permis d'identifier comment les informations serveurs sont collectées et affichées dans l'interface utilisateur. Voici les spécifications techniques pour une implémentation serveur (Java) conforme aux attentes du client eMule.

#### 1. Structure du tableau de bord (Interface Utilisateur)
Le tableau est géré par la classe `CServerListCtrl`. Les colonnes affichées sont les suivantes :

| Colonne | Source de données (`CServer`) | Description |
| :--- | :--- | :--- |
| **Nom** | `m_strName` | Nom du serveur (reçu via `OP_SERVERIDENT` ou `OP_SERVER_DESC_RES`). |
| **Adresse IP** | `ip` : `port` | Adresse IP et port TCP du serveur. |
| **Description** | `m_strDescription` | Texte descriptif du serveur. |
| **Ping** | `ping` | Temps de réponse en ms (mesuré par le client via UDP). |
| **Utilisateurs** | `users` | Nombre total d'utilisateurs connectés. |
| **Max. Util.** | `maxusers` | Capacité maximale du serveur. |
| **Fichiers** | `files` | Nombre total de fichiers indexés. |
| **Priorité** | `m_uPreference` | Priorité définie par l'utilisateur (Bas, Normal, Haut). |
| **Échecs** | `failedcount` | Nombre de tentatives de connexion échouées. |
| **Statique** | `staticservermember` | Indique si le serveur est dans la liste permanente. |
| **Soft Files** | `softfiles` | Limite souple de fichiers par utilisateur (UDP). |
| **Hard Files** | `hardfiles` | Limite stricte de fichiers par utilisateur (UDP). |
| **Version** | `m_strVersion` | Version logicielle du serveur (déduite ou reçue). |
| **Util. LowID** | `m_uLowIDUsers` | Nombre d'utilisateurs en LowID connectés. |
| **Obfuscation** | `m_nObfuscationPortTCP` | Indique si le serveur supporte le protocole obfusqué. |

---

#### 2. Protocoles et Opcodes attendus (Côté Serveur)

Pour remplir ces informations, le serveur doit implémenter les opcodes suivants :

##### A. Protocole TCP (Connexion initiale)
- **`OP_SERVERIDENT` (0x41)** : Envoyé lors de la connexion.
    - Contenu : `[Hash 16 octets][IP 4 octets][Port 2 octets][TagCount 4 octets][Tags...]`
    - Tags recommandés : `ST_SERVERNAME` (0x01, String), `ST_DESCRIPTION` (0x0B, String), `ST_TCP_FLAGS` (0x91, Int), `ST_UDP_FLAGS` (0x97, Int).
    - Note : JEmuleServer envoie ce paquet au tout début de la séquence de login et utilise l'IP publique auto-détectée pour plus de compatibilité.
- **`OP_SERVERMESSAGE` (0x38)** : Utilisé pour la version.
    - Le client cherche la chaîne `"server version "` suivie du numéro de version (ex: `17.15`). 
    - JEmuleServer envoie ce message ("Welcome to JEmuleServer! Running server version X.Y") à la fin du handshake pour permettre l'auto-détection.
    - Le client peut aussi détecter le tag `[emDynIP: host]` pour les adresses dynamiques.
- **`OP_IDCHANGE` (0x40)** : Envoyé juste après `OP_SERVERIDENT`.
    - Contenu : `[ClientID 4 octets][ServerFlags 4 octets]`
    - JEmuleServer utilise ce paquet pour informer le client de ses capacités (LargeFiles, Unicode, Obfuscation).
- **`OP_LOGIN_ACCEPTED` (0x1B)** : Confirme la connexion.
    - Contenu : `[ClientID 4 octets]`
- **`OP_SERVERSTATUS` (0x34)** : Mise à jour périodique.
    - Contenu : `[UserCount 4 octets][FileCount 4 octets]`

##### B. Protocole UDP (Statistiques et Ping)
- **`OP_GLOB_SERV_STAT_RES` (0x97)** : Réponse au ping de statut global.
    - Contenu : `[Challenge 4][UserCount 4][FileCount 4][MaxUsers 4][SoftFiles 4][HardFiles 4][UDPFlags 4][LowIDUsers 4][UDPPort 2][TCPPort 2][ServerKey 4]` (44 octets au total)
    - Note : Le `Challenge` doit correspondre à celui envoyé par le client dans la requête. Les drapeaux (UDPFlags) incluent généralement le support de l'Unicode et du filtrage IP.
- **`OP_SERVER_DESC_RES` (0x99)** : Réponse détaillée.
    - Utilisé pour mettre à jour le nom et la description via UDP.

##### C. Gestion des tags étendus
- **Support UINT64 (0x14)** : JEmuleServer supporte désormais les tags de type `TAGTYPE_UINT64`.
- **Fichiers volumineux (> 4GB)** : Utilisation combinée de `FT_FILESIZE` (0x02) et `FT_FILESIZE_HI` (0x3A) pour supporter les fichiers de plus de 4GB dans les paquets `OFFER_FILES` et `PUBLISH_FILES`.

#### 3. Liste des Tags importants (`CTag`)
Le serveur doit utiliser ces identifiants de tags pour envoyer des métadonnées :
- `0x01` : **Nom du serveur** (`ST_SERVERNAME`)
- `0x0B` : **Description** (`ST_DESCRIPTION`)
- `0x0C` : **DynIP** (`ST_DYNIP`) - pour les hôtes DNS.
- `0x11` : **Version** (parfois utilisé dans les tags, bien que souvent extrait du message texte).

#### 4. Recommandations pour l'implémentation Java
1. **Big Endian vs Little Endian** : Le protocole eMule utilise majoritairement le format **Little Endian** pour les entiers.
2. **Support Unicode** : Si le serveur supporte l'Unicode, il doit lever le flag `SRV_TCPFLG_UNICODE` (0x10) dans les capacités annoncées.
3. **Mise à jour dynamique** : Pour que l'interface du client soit fluide, envoyez régulièrement des paquets `OP_SERVERSTATUS` en TCP et répondez promptement aux requêtes UDP (port TCP+4 par défaut).
