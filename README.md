# JEmuleServer

JEmuleServer is an experimental eMule server written in Java.

## Why this project?

This project was initiated because the historical source code of the **Lugdunum** eMule server was not available.
Lugdunum was the reference for eDonkey2000 (ed2k) servers for years, but its code remained closed and the binaries
became obsolete or difficult to run on modern systems. JEmuleServer aims to provide a modern, open-source (LGPLv3), and
high-performance alternative using the latest Java features (such as Virtual Threads).

## Compilation

To compile the project and generate the executable JAR file (Fat JAR):

```bash
./gradlew build
```

The JAR file will be generated in `build/libs/JEmuleServer-0.3.0.jar`.

## Running

### Run Mode (via Gradle)

You can start the server directly via Gradle:

```bash
./gradlew run
```

### Manual JAR Launch

Once the project is compiled, you can start the server with the following command:

```bash
java -Djava.net.preferIPv4Stack=true -jar build/libs/JEmuleServer-0.3.0.jar [port]
```

- `-Djava.net.preferIPv4Stack=true`: Recommended to avoid connectivity issues with some eMule/aMule clients (forces the
  use of IPv4).
- `[port]`: Optional, defaults to 4661.

## Firewall Configuration (Fedora)

To allow clients to connect to your server, you must ensure your firewall is running and open the TCP and UDP ports (default 4661). On Fedora, use the following commands:

### 1. Start and enable firewalld
```bash
sudo systemctl start firewalld
sudo systemctl enable firewalld
```

### 2. Open ports
```bash
sudo firewall-cmd --add-port=4661/tcp --permanent
sudo firewall-cmd --add-port=4661/udp --permanent
sudo firewall-cmd --reload
```

### 3. Verify configuration
```bash
sudo firewall-cmd --list-ports
```

*Note: Don't forget to also configure Port Forwarding on your router/internet box if necessary.*

## Features Comparison

Here is a comparison between JEmuleServer and the historical **eServer (Lugdunum)**:

| Feature | JEmuleServer | eServer (Lugdunum) |
| :--- | :---: | :---: |
| **Language / Runtime** | Java 21 (Virtual Threads) | C / C++ (Native) |
| **Protocol Obfuscation (RC4)** | ✅ Supported | ✅ Supported |
| **ZLIB Compression** | ✅ Supported | ✅ Supported |
| **Advanced Search (AND/OR/NOT)** | ✅ Supported | ✅ Supported |
| **Large Files (>4GB) Support** | ✅ Supported | ✅ Supported |
| **Lugdunum Extensions (0x40-0x42)** | ✅ Supported | ✅ Supported |
| **Embedded Database** | ✅ Supported (H2) | ❌ No (Memory/Files) |
| **User Quotas** | ✅ Supported | ✅ Supported |
| **IP Filtering (ipfilter.dat)** | 🚧 In Progress | ✅ Supported |
| **LowID Management (Callback)** | 🚧 In Progress | ✅ Supported |
| **External Config File** | 🚧 In Progress | ✅ Supported |
| **Web Admin Interface** | ❌ Planned | ✅ Supported |
| **IPv6 Support** | ❌ Planned | ❌ No |

## License

This project is distributed under the **GNU Lesser General Public License v3.0 (LGPLv3)**. See the `LICENSE.LGPL` and
`LICENSE.GPL` files for more details.

## Author

**Nicolas Hernandez** (herniatgmail.com)

## Documentation

More information can be found in the `doc/` directory:
- [Protocols Description](doc/protocols.md)
- [Technical References](doc/references.md)
- [Class Diagram](doc/class_diagram.puml)
- [Connection Sequence Diagram](doc/sequence_diagram.puml)
