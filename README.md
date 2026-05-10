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

The JAR file will be generated in `build/libs/JEmuleServer-0.1.0.jar`.

## Running

### Run Mode (via Gradle)

You can start the server directly via Gradle:

```bash
./gradlew run
```

### Manual JAR Launch

Once the project is compiled, you can start the server with the following command:

```bash
java -Djava.net.preferIPv4Stack=true -jar build/libs/JEmuleServer-0.1.0.jar [port]
```

- `-Djava.net.preferIPv4Stack=true`: Recommended to avoid connectivity issues with some eMule/aMule clients (forces the
  use of IPv4).
- `[port]`: Optional, defaults to 4661.

## License

This project is distributed under the **GNU Lesser General Public License v3.0 (LGPLv3)**. See the `LICENSE.LGPL` and
`LICENSE.GPL` files for more details.

## Author

**Nicolas Hernandez** (herniatgmail.com)
