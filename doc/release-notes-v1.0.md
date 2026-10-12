## JEmuleServer 1.0

Première version stable de JEmuleServer, serveur eD2K (eMule) en Java destiné à rendre aux clients eMule le
même service, a minima, que le serveur historique Lugdunum.

Cette version corrige les 26 incompatibilités relevées par l'[audit de compatibilité avec le client eMule 0.50a](https://github.com/dagga/JEmuleServer/blob/v1.0/doc/AUDIT_compatibilite_client_eMule.md),
mené à partir du code source du client.

### Nouveautés et corrections principales

- **Sources** : les demandes de sources (`OP_GETSOURCES` / `OP_GETSOURCES_OBFU`, TCP et UDP) aboutissent ; IP et
  ports en little-endian ; port d'écoute réel des clients ; tous les clients partageant un fichier sont renvoyés ;
  les sources disparaissent à la déconnexion.
- **Publication** : décodage complet des tags eMule (chaînes compactes, entiers non signés, 64 bits), plus de
  perte de publication selon le nombre de fichiers partagés, type de fichier normalisé (`Video`, `Audio`…).
- **Recherche** : résultats correctement comptés, bouton « Plus » fonctionnel, mots-clés Unicode, filtres de
  taille / disponibilité / sources complètes / bitrate / durée / codec, paramètres 64 bits, recherche globale UDP.
- **HighID / LowID** : test de joignabilité au login (`OP_HELLO` → `OP_HELLOANSWER`), LowID sinon, et relais des
  callbacks (`OP_CALLBACKREQUESTED` / `OP_CALLBACK_FAIL`).
- **Obfuscation** : handshake TCP (Diffie-Hellman + RC4) et obfuscation UDP serveur conformes au client.
- **Robustesse** : décompression zlib bornée, plus de paquets inconnus envoyés aux clients.
- Nom affiché par les clients : `JEmuleServer v1.0`, description `eMule Server - version 1.0`.

### Téléchargements

| Fichier | Usage |
|---|---|
| `jemuleserver_1.0-1_amd64.deb` | Debian / Ubuntu (Java inclus) |
| `jemuleserver-1.0-1.x86_64.rpm` | Fedora / RHEL / openSUSE (Java inclus) |
| `JEmuleServer-1.0.exe` | Windows, installateur (Java inclus) |
| `JEmuleServer-1.0.jar` | Toute plateforme avec Java 21 ou plus : `java -jar JEmuleServer-1.0.jar [server.properties] [port]` |
| `jemuleserver.service` | Unité systemd pour les paquets Linux |
| `SHA256SUMS` | Sommes de contrôle |

Les paquets installent l'application dans `/opt/jemuleserver` (Linux) ou `Program Files\JEmuleServer` (Windows).
Lancé sans argument, le programme installé range sa configuration (`server.properties`, créée au premier
démarrage) et sa base H2 dans `~/.local/share/jemuleserver` (Linux) ou `%LOCALAPPDATA%\JEmuleServer` (Windows).
Avec un fichier de configuration en argument, les chemins relatifs restent relatifs au répertoire courant.

### Installation en service (Linux)

```bash
sudo dnf install ./jemuleserver-1.0-1.x86_64.rpm      # ou : sudo apt install ./jemuleserver_1.0-1_amd64.deb
sudo useradd --system --home-dir /var/lib/jemuleserver --create-home --shell /usr/sbin/nologin jemule
sudo install -d /etc/jemuleserver
sudo curl -L -o /etc/jemuleserver/server.properties https://raw.githubusercontent.com/dagga/JEmuleServer/v1.0/server.properties
sudo cp jemuleserver.service /etc/systemd/system/ && sudo systemctl daemon-reload
sudo systemctl enable --now jemuleserver
```

Ports à ouvrir : **TCP 4661**, **UDP 4661, 4665 et 4673** (port + 4 : UDP ; port + 12 : UDP obfusqué).
Le serveur doit aussi pouvoir ouvrir des connexions TCP sortantes vers les clients (test HighID).

### Mise à jour depuis une version précédente

La base H2 existante est conservée (schéma inchangé) : arrêter le serveur, sauvegarder `jemule_db.mv.db`,
remplacer le JAR ou installer le paquet, relancer **depuis le même répertoire** (ou avec le même
`databasePath`). Les fichiers présents en base réapparaissent dans les recherches dès que leurs sources
se reconnectent et les republient.

Nouveau paramètre facultatif : `lowIdCheckTimeoutSeconds` (5 par défaut, 0 = tout le monde en LowID).
