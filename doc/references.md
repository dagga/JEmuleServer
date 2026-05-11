# Project References

This document lists the sources and technical references used for the implementation of JEmuleServer. These sources provide the specifications for the eDonkey2000 (ed2k) and eMule protocols, including extensions, obfuscation, and compression mechanisms.

## Primary Protocol Specifications

1.  **eMule Protocol Specification (Yoram Kulbak & Danny Bickson)**
    *   *IPTPS02 - eMule: The Peer-to-Peer System of the Next Generation*
    *   URL: [http://www.cs.rice.edu/Conferences/IPTPS02/109.pdf](http://www.cs.rice.edu/Conferences/IPTPS02/109.pdf)
    *   Description: Academic paper providing a detailed analysis of the eMule system and its core protocol.

2.  **eDonkey Protocol 0.6.2 (pdonkey)**
    *   *Historical documentation of the original eDonkey2000 protocol.*
    *   URL: [http://prdownloads.sourceforge.net/pdonkey/eDonkey-protocol-0.6.2.html?download](http://prdownloads.sourceforge.net/pdonkey/eDonkey-protocol-0.6.2.html?download)
    *   Description: One of the earliest detailed documentations of the ed2k client-server protocol.

3.  **eFarm Project (Client-Server Version 3.0)**
    *   *ed2k Protocol Documentation*
    *   URL: [http://www.filesharingweb.de/emule_protokolle/eFarm-Protocol_V3_1_EN.pdf](http://www.filesharingweb.de/emule_protokolle/eFarm-Protocol_V3_1_EN.pdf)
    *   Description: Comprehensive guide on the ed2k protocol from the eFarm project.

## Extended Protocol and Wiki Documentation

4.  **aMule Project Wiki**
    *   *ed2k Protocol Specification*
    *   URL: [https://wiki.amule.org/wiki/Ed2k_protocol](https://wiki.amule.org/wiki/Ed2k_protocol)
    *   Description: Community-maintained wiki with up-to-date information on ed2k messages and tag types.

5.  **eMule Official Wiki**
    *   *Protocol Obfuscation*
    *   URL: [https://www.emule-project.net/home/perl/help.cgi?l=1&topic_id=848](https://www.emule-project.net/home/perl/help.cgi?l=1&topic_id=848)
    *   Description: Official documentation for the RC4-based protocol obfuscation.

6.  **Hydranode Project**
    *   *ed2k Protocol Documentation*
    *   URL: [http://hydranode.com/docs/ed2k/ed2kproto.html](http://hydranode.com/docs/ed2k/ed2kproto.html)
    *   Description: Technical reference from the Hydranode multi-protocol client project.

## Developer Resources and Historical Context

7.  **eMule Plus KB**
    *   *Developer Knowledge Base and Diagrams*
    *   URL: [http://emuleplus.info/forum/index.php?showforum=23&hyperlink=/Developers/KB/Diagrams](http://emuleplus.info/forum/index.php?showforum=23&hyperlink=/Developers/KB/Diagrams)
    *   Description: Forum-based knowledge base for eMule Plus developers.

8.  **eMule Internals Presentation**
    *   *Hebrew University of Jerusalem*
    *   URL: [http://www.cs.huji.ac.il/labs/danss/p2p/eMule/eMule.ppt](http://www.cs.huji.ac.il/labs/danss/p2p/eMule/eMule.ppt)
    *   Description: Presentation slides detailing eMule's architecture and protocol.

9.  **eMule Source Code**
    *   *SourceForge Repository*
    *   URL: [http://sourceforge.net/project/showfiles.php?group_id=53489&package_id=145950](http://sourceforge.net/project/showfiles.php?group_id=53489&package_id=145950)
    *   Description: The reference implementation of the eMule client.

10. **Lugdunum eServer (eServer)**
    *   *Historical Context*
    *   Description: Reference server implementation (Lugdunum) which introduced critical extensions like OpCodes 0x40-0x42 and the 0xFB tag.

11. **Wireshark Wiki**
    *   *eDonkey Protocol Dissector*
    *   URL: [https://wiki.wireshark.org/eDonkey](https://wiki.wireshark.org/eDonkey)
    *   Description: Information on how ed2k packets are dissected and identified in network traffic.
