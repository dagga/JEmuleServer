## JEmuleServer 1.0.1

Version corrective de la 1.0.

### Corrections

- **HighID derrière un NAT** : quand plusieurs clients partagent la même IP publique, le test de joignabilité
  (`OP_HELLO` → `OP_HELLOANSWER`) pouvait être reçu et validé par un autre client de la même box, vers lequel le
  port annoncé était redirigé. Le client qui se connectait recevait alors un HighID inutilisable, identique à
  celui de l'autre client. Le serveur vérifie désormais que la réponse porte le hash utilisateur envoyé au login ;
  sinon le client reçoit un LowID.
- **Windows** : un chemin de configuration commençant par `/` ou `\` (sans lettre de lecteur) n'est plus
  rattaché au répertoire de données.
- **README** : section HighID / LowID, ports UDP 4665 et 4673 dans la configuration du pare-feu, paramètre
  `lowIdCheckTimeoutSeconds`, JDK 21 requis pour compiler, liens vers les releases et les notes de version.
- Nom affiché par les clients : `JEmuleServer v1.0.1`, description `eMule Server - version 1.0.1`.

### Téléchargements

| Fichier | Usage |
|---|---|
| `jemuleserver_1.0.1-1_amd64.deb` | Debian / Ubuntu (Java inclus) |
| `jemuleserver-1.0.1-1.x86_64.rpm` | Fedora / RHEL / openSUSE (Java inclus) |
| `JEmuleServer-1.0.1.exe` | Windows, installateur (Java inclus) |
| `JEmuleServer-1.0.1.jar` | Toute plateforme avec Java 21 ou plus : `java -jar JEmuleServer-1.0.1.jar [server.properties] [port]` |
| `jemuleserver.service` | Unité systemd pour les paquets Linux |
| `SHA256SUMS` | Sommes de contrôle |

### Mise à jour depuis la 1.0

Aucun changement de configuration ni de schéma de base : arrêter le serveur, remplacer le JAR ou installer le
nouveau paquet, puis relancer. L'installation en service et les ports à ouvrir sont décrits dans les
[notes de la 1.0](https://github.com/dagga/JEmuleServer/blob/v1.0/doc/release-notes-v1.0.md).
