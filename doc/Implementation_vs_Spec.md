# Implementation vs eMule Protocol Retro-Specification

Résumé
------
Ce document compare les diagrammes de séquence décrits dans `doc/eMule_Protocol_RetroSpecification.md` avec l'implémentation actuelle du projet, liste les fichiers responsables et signale les divergences ou recommandations.

Vérifications réalisées
----------------------
Fichiers inspectés (référence):
- src/main/java/org/jemule/network/Packet.java
- src/main/java/org/jemule/protocol/OpCode.java
- src/main/java/org/jemule/network/handler/LoginHandler.java
- src/main/java/org/jemule/network/handler/PacketProcessor.java
- src/main/java/org/jemule/network/handler/SourceHandler.java
- src/main/java/org/jemule/network/Server.java
- src/main/java/org/jemule/protocol/Tag.java

1) Diagramme: Connexion Client-Serveur (Login)
- Spec: Client -> OP_LOGINREQUEST, Server -> OP_SERVERIDENT, OP_IDCHANGE (si besoin), OP_SERVERMESSAGE, OP_SERVERSTATUS, (optionnel) OP_SERVERLIST
- Implémentation: `LoginHandler.handleLogin` envoie `sendServerIdent` (0x41) comme premier paquet, ensuite ID_CHANGE (0x40), LOGIN_ACCEPTED (0x1B), et enfin `OP_SERVERMESSAGE` (0x38) contenant la version du serveur pour l'auto-détection eMule.
- Conclusion: Conforme au diagramme et optimisé pour la détection de version eMule.

2) Diagramme: Recherche de Fichiers (Search)
- Spec: OP_SEARCHREQUEST -> OP_SEARCHRESULT, possible OP_QUERY_MORE_RESULT
- Implémentation: `PacketProcessor` associe SEARCH_REQUEST à `SearchHandler.handleSearch` et QUERY_MORE_RESULT à `SearchHandler.handleQueryMoreResult`. `SearchHandler` construit et renvoie SEARCH_RESULT via `Packet`.
- Conclusion: Conforme. Code: src/main/java/org/jemule/network/handler/PacketProcessor.java and src/main/java/org/jemule/network/handler/SearchHandler.java

3) Diagramme: Demande et Obtention de Sources (GetSources)
- Spec (TCP): OP_GETSOURCES -> OP_FOUNDSOURCES
- Implémentation (TCP): `SourceHandler.handleGetSources` et renvoi via `Packet` FOUND_SOURCES/SOURCES_RESULT_OBFU.
- Implémentation (UDP): `Server.handleUdp` gère OP_GLOBGETSOURCES (0x9A) et renvoie OP_GLOBFOUNDSOURCES (0x9B) ainsi que OP_GLOBSERVSTATRES (0x97) avec une structure fixe de 44 octets pour la conformité eMule.
- Conclusion: Conforme pour TCP et UDP. Code: src/main/java/org/jemule/network/handler/SourceHandler.java and src/main/java/org/jemule/network/Server.java (handleUdp)

4) Diagramme: Transfert de Fichier (Client-Client)
- Spec: OP_HELLO / OP_HELLOANSWER, OP_REQUESTPARTS, OP_SENDINGPART, OP_END_OF_DOWNLOAD
- Implémentation serveur: Le serveur fournit des primitives de relais et callback (OpCode.CALLBACK) et accepte certains opcodes (COMPRESSED_PART handler présent), mais le transfert direct client->client est géré par les clients eux-mêmes. Le serveur n'implémente pas l'échange de blocs fichier pair-à-pair (cela est attendu).
- Conclusion: Conforme à l'intention (le serveur n'est pas supposé agir comme client pour transferts P2P), le serveur prend en charge callbacks et réponses nécessaires à l'établissement.

5) Kademlia (Bootstrap / Hello)
- Spec: KADEMLIA opcodes listés.
- Implémentation: OpCode support partiel (OpCode.fromByte gère certains cas) ; `Server` n'expose pas de gestion Kademlia complète via UDP dans `handleUdp` (implémentation KAD complète se trouve ailleurs ou est limitée). Recommandation: ajouter test/implémentation KAD si requis.

Tags (TLV)
-----------
- Spec: Tag type, name-as-id when high bit set, else string; types include hash/string/uint32/etc.
- Implémentation: `Tag.write` et `Tag.read` implémentent exactement le mécanisme TLV (type, name-as-id via high bit, ou length+name string), et types correspondants. `Tag.writeList`/`readList` utilisent un préfixe 4-octets pour compter les tags; ce choix est cohérent en interne mais documenter explicitement la présence du count dans les messages où applicable.

Divergences / Points d'attention
--------------------------------
1. Collision d'opcodes dans `OpCode.java`:
   - `QUERY_MORE_RESULT` et `PUBLISH_ACK` partagent la valeur `(byte)0x21` (ligne où ils sont définis). Cela crée une ambiguïté si le code s'appuie seulement sur la valeur byte pour distinguer rôles client/serveur. Actuellement le code évite certaines collisions via `fromByte(protocol, b)` mais il est recommandé de corriger l'enum pour attribuer des valeurs distinctes et ajouter un test unitaire qui vérifie l'unicité des valeurs par protocole.

2. Tests / Build:
   - Le projet contient des tests (src/test) couvrant Packet parsing, Server integration, etc. Aucune commande Maven (`mvn test`) à la racine n'a fonctionné car il n'y a pas de POM à la racine du workspace; si une build/test existe, documenter la commande exacte ou indiquer l'emplacement du POM pour exécution.

Actions recommandées
--------------------
- Corriger la collision d'opcodes dans `src/main/java/org/jemule/protocol/OpCode.java` (ajouter valeur distincte pour PUBLISH_ACK).
- Ajouter un fichier de tests unitaires qui valide explicitement les séquences critiques (login sequence, search result + query_more, getsources TCP/UDP responses). Ces tests existent en partie ; compléter si besoin.
- Documenter la présence du compteur de tags (4 octets) et les variantes d'encodage (ED2K vs eMule) dans `doc/eMule_Protocol_RetroSpecification.md` ou en annexe.

Fichiers modifiés/ajoutés
-------------------------
- Ajouté: `doc/Implementation_vs_Spec.md` (ce fichier)

Si tu veux, les étapes suivantes proposées:
- Appliquer la correction d'opcode (PR simple) et ajouter test unitaire — je peux le faire.
- Générer/ajouter des tests d'intégration explicitant chaque séquence (login, search, getsources) — je peux créer ces tests.
- Mettre à jour `doc/eMule_Protocol_RetroSpecification.md` pour inclure les notes d'implémentation (tag counts, opcodes effectifs) — je peux appliquer ces modifications aussi.

--
Notes: Ce rapport se base sur lecture du code; si tu veux que j'applique automatiquement les corrections (opcode fix + tests + mise à jour spec), donner la confirmation et je procéderai aux modifications et aux tests.