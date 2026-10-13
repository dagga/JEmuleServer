### Retro-specification of the eMule server list display

Analysing the eMule source code showed how server information is collected and displayed in the user interface. Below are the technical specifications for a (Java) server implementation that meets the eMule client's expectations.

#### 1. Structure of the server list (User Interface)
The list is managed by the `CServerListCtrl` class. The displayed columns are:

| Column | Data source (`CServer`) | Description |
| :--- | :--- | :--- |
| **Name** | `m_strName` | Server name (received via `OP_SERVERIDENT` or `OP_SERVER_DESC_RES`). |
| **IP Address** | `ip` : `port` | IP address and TCP port of the server. |
| **Description** | `m_strDescription` | Descriptive text of the server. |
| **Ping** | `ping` | Response time in ms (measured by the client over UDP). |
| **Users** | `users` | Total number of connected users. |
| **Max Users** | `maxusers` | Maximum capacity of the server. |
| **Files** | `files` | Total number of indexed files. |
| **Priority** | `m_uPreference` | Priority set by the user (Low, Normal, High). |
| **Failed** | `failedcount` | Number of failed connection attempts. |
| **Static** | `staticservermember` | Whether the server is in the permanent list. |
| **Soft Files** | `softfiles` | Soft limit of files per user (UDP). |
| **Hard Files** | `hardfiles` | Hard limit of files per user (UDP). |
| **Version** | `m_strVersion` | Software version of the server (inferred or received). |
| **LowID Users** | `m_uLowIDUsers` | Number of connected LowID users. |
| **Obfuscation** | `m_nObfuscationPortTCP` | Whether the server supports the obfuscated protocol. |

---

#### 2. Expected protocols and opcodes (server side)

To fill in this information, the server must implement the following opcodes:

##### A. TCP protocol (initial connection)
- **`OP_SERVERIDENT` (0x41)**: Sent during the connection.
    - Content: `[Hash 16 bytes][IP 4 bytes][Port 2 bytes][TagCount 4 bytes][Tags...]`
    - Recommended tags: `ST_SERVERNAME` (0x01, String), `ST_DESCRIPTION` (0x0B, String), `ST_TCP_FLAGS` (0x91, Int), `ST_UDP_FLAGS` (0x97, Int).
    - Note: JEmuleServer sends this packet at the very beginning of the login sequence and uses the auto-detected public IP for better compatibility.
- **`OP_SERVERMESSAGE` (0x38)**: Used for the version.
    - The client looks for the string `"server version "` followed by the version number (e.g. `17.15`).
    - JEmuleServer sends this message ("Welcome to JEmuleServer! Running server version X.Y") at the end of the handshake to allow auto-detection.
    - The client can also detect the `[emDynIP: host]` tag for dynamic addresses.
- **`OP_IDCHANGE` (0x40)**: Sent right after `OP_SERVERIDENT`.
    - Content: `[ClientID 4 bytes][ServerFlags 4 bytes]`
    - JEmuleServer uses this packet to tell the client about its capabilities (LargeFiles, Unicode, Obfuscation).
- **`OP_LOGIN_ACCEPTED` (0x1B)**: Confirms the connection.
    - Content: `[ClientID 4 bytes]`
- **`OP_SERVERSTATUS` (0x34)**: Periodic update.
    - Content: `[UserCount 4 bytes][FileCount 4 bytes]`

##### B. UDP protocol (statistics and ping)
- **`OP_GLOB_SERV_STAT_RES` (0x97)**: Response to the global status ping.
    - Content: `[Challenge 4][UserCount 4][FileCount 4][MaxUsers 4][SoftFiles 4][HardFiles 4][UDPFlags 4][LowIDUsers 4][UDPPort 2][TCPPort 2][ServerKey 4]` (44 bytes in total)
    - Note: The `Challenge` must match the one sent by the client in the request. The flags (UDPFlags) usually include Unicode support and IP filtering.
- **`OP_SERVER_DESC_RES` (0x99)**: Detailed response.
    - Used to update the name and description over UDP.

##### C. Extended tag handling
- **UINT64 support (0x14)**: JEmuleServer now supports tags of type `TAGTYPE_UINT64`.
- **Large files (> 4GB)**: `FT_FILESIZE` (0x02) and `FT_FILESIZE_HI` (0x3A) are used together to support files larger than 4GB in `OFFER_FILES` and `PUBLISH_FILES` packets.

#### 3. Important tags (`CTag`)
The server must use these tag identifiers to send metadata:
- `0x01`: **Server name** (`ST_SERVERNAME`)
- `0x0B`: **Description** (`ST_DESCRIPTION`)
- `0x0C`: **DynIP** (`ST_DYNIP`) - for DNS hosts.
- `0x11`: **Version** (sometimes used in tags, although it is often extracted from the text message).

#### 4. Recommendations for the Java implementation
1. **Big Endian vs Little Endian**: The eMule protocol mostly uses **Little Endian** for integers.
2. **Unicode support**: If the server supports Unicode, it must set the `SRV_TCPFLG_UNICODE` (0x10) flag in the announced capabilities.
3. **Dynamic updates**: To keep the client interface responsive, send `OP_SERVERSTATUS` packets regularly over TCP and answer UDP requests promptly (TCP port + 4 by default).
