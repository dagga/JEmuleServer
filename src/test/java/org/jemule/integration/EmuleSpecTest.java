package org.jemule.integration;

import org.jemule.Main;
import org.jemule.config.ServerConfig;
import org.jemule.core.ClientFactory;
import org.jemule.network.Packet;
import org.jemule.network.Server;
import org.jemule.protocol.OpCode;
import org.jemule.protocol.Tag;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class EmuleSpecTest {
    private static Server server;
    private static final int TEST_PORT = 4661;
    private static Thread serverThread;

    @BeforeAll
    static void setup() throws IOException {
        ServerConfig config = new ServerConfig(
                TEST_PORT, 1024 * 1024, 100, 100, 5000, 100000, 1000, 100,
                "jemule_test_db", "ipfilter.dat", false, 0.5f, 10, 60, 300, 60, "127.0.0.1"
        );
        server = new Server(config, new ClientFactory());
        serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (IOException e) {
                // ignore
            }
        });
        serverThread.start();
        // Attendre que le serveur démarre
        try { Thread.sleep(1000); } catch (InterruptedException e) {}
    }

    @AfterAll
    static void tearDown() {
        server.stop();
        serverThread.interrupt();
    }

    @Test
    @DisplayName("Vérification de la conformité TCP (ServerIdent et Version)")
    void testTcpHandshakeConformity() throws IOException {
        try (Socket socket = new Socket("127.0.0.1", TEST_PORT)) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Envoyer LOGINREQUEST (ED2K 0x01)
            // Calculer la taille nécessaire : 16 (hash) + 4 (id) + 2 (port) + 4 (tag count) + tag (type 1 + name 1 + string_len 2 + string_val)
            String clientName = "TestClient";
            int tagSize = 1 + 1 + 2 + clientName.length();
            ByteBuffer loginData = ByteBuffer.allocate(16 + 4 + 2 + 4 + tagSize).order(ByteOrder.LITTLE_ENDIAN);
            loginData.put(new byte[16]); // User Hash
            loginData.putInt(0x0100007F); // Client ID (127.0.0.1)
            loginData.putShort((short) 4662); // Port
            loginData.putInt(1); // Tag count
            new Tag(Tag.TYPE_STRING, Tag.NAME_NAME, clientName).write(loginData);

            new Packet(Packet.PROTOCOL_ED2K, (byte) 0x01, loginData.array()).write(out, false);

            // Lire les réponses du serveur
            // On attend OP_SERVERMESSAGE (0x38) et OP_SERVERIDENT (0x41)
            boolean versionFound = false;
            boolean identFound = false;
            boolean idChangeFound = false;

            for (int i = 0; i < 15; i++) { // Lire plus de paquets car le message de version est envoyé plus tard
                Packet p = Packet.read(in, 1024 * 1024);
                if (p.protocol() == Packet.PROTOCOL_ED2K) {
                    if (p.opcode() == 0x38) { // SERVER_MESSAGE
                        String msg = new String(p.data(), 2, p.data().length - 2);
                        if (msg.contains("server version " + Main.ESERVER_VERSION)) {
                            versionFound = true;
                        }
                    } else if (p.opcode() == 0x41) { // SERVER_IDENT
                        identFound = true;
                        ByteBuffer buf = ByteBuffer.wrap(p.data()).order(ByteOrder.LITTLE_ENDIAN);
                        byte[] hash = new byte[16];
                        buf.get(hash);
                        int ip = (int) buf.getInt();
                        int port = buf.getShort() & 0xFFFF;
                        List<Tag> tags = Tag.readList(buf);
                        
                        assertTrue(tags.stream().anyMatch(t -> t.name().equals(Tag.NAME_SERVERNAME)), "ST_SERVERNAME manquant");
                        assertTrue(tags.stream().anyMatch(t -> t.name().equals(Tag.NAME_DESCRIPTION)), "ST_DESCRIPTION manquant");

                        Tag maxUsersTag = tags.stream().filter(t -> t.name().equals(Tag.NAME_MAXUSERS)).findFirst().orElse(null);
                        assertNotNull(maxUsersTag, "ST_MAXUSERS manquant");
                        assertEquals("5000", (String) maxUsersTag.value());

                        Tag softFilesTag = tags.stream().filter(t -> t.name().equals(Tag.NAME_SOFTFILES)).findFirst().orElse(null);
                        assertNotNull(softFilesTag, "ST_SOFTFILES manquant");
                        assertEquals("1000000", (String) softFilesTag.value());

                        Tag hardFilesTag = tags.stream().filter(t -> t.name().equals(Tag.NAME_HARDFILES)).findFirst().orElse(null);
                        assertNotNull(hardFilesTag, "ST_HARDFILES manquant");
                        assertEquals("2000000", (String) hardFilesTag.value());

                        Tag udpFlagsTag = tags.stream().filter(t -> t.name().equals(Tag.NAME_UDPFLAGS)).findFirst().orElse(null);
                        assertNotNull(udpFlagsTag, "ST_UDPFLAGS manquant");
                        int udpFlagsVal = (int) udpFlagsTag.value();
                        assertTrue((udpFlagsVal & 0x200) != 0, "Drapeau UDPOBFUSCATION (0x200) manquant dans ST_UDPFLAGS");
                        assertTrue((udpFlagsVal & 0x400) != 0, "Drapeau TCPOBFUSCATION (0x400) manquant dans ST_UDPFLAGS");
                    } else if (p.opcode() == 0x40) { // ID_CHANGE
                        idChangeFound = true;
                        // On attend 8 octets de données (ID + Flags)
                        assertEquals(8, p.data().length, "OP_IDCHANGE doit contenir exactement 8 octets de données");
                        ByteBuffer buf = ByteBuffer.wrap(p.data()).order(ByteOrder.LITTLE_ENDIAN);
                        int newId = buf.getInt();
                        int flags = buf.getInt();
                        // Vérifier que le bit LARGEFILES (0x100) est présent
                        assertTrue((flags & 0x100) != 0, "Bit LARGEFILES (0x100) manquant dans OP_IDCHANGE");
                    }
                }
                if (versionFound && identFound && idChangeFound) break;
            }

            assertTrue(versionFound, "Message de version 'server version' non trouvé");
            assertTrue(identFound, "Paquet OP_SERVERIDENT non trouvé");
            assertTrue(idChangeFound, "Paquet OP_IDCHANGE non trouvé");
        }
    }

    @Test
    @DisplayName("Vérification de la conformité UDP (Global Status Response)")
    void testUdpStatusConformity() throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(2000);
            InetAddress address = InetAddress.getByName("127.0.0.1");
            
            // Envoyer OP_GLOBSERVSTATREQ (0x96)
            int challenge = 0x12345678;
            ByteBuffer req = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
            req.put(Packet.PROTOCOL_ED2K);
            req.put((byte) 0x96);
            req.putInt(challenge);
            
            DatagramPacket packet = new DatagramPacket(req.array(), req.capacity(), address, TEST_PORT);
            socket.send(packet);
            
            // Recevoir OP_GLOBSERVSTATRES (0x97)
            byte[] buf = new byte[1024];
            DatagramPacket response = new DatagramPacket(buf, buf.length);
            socket.receive(response);
            
            // Note: 42 ou 44 octets selon l'implémentation de la spec.
            // On a mis 44 dans Server.java (ByteBuffer.allocate(44))
            // Mais l'opcode et le protocole prennent 2 octets, et le ByteBuffer peut être flip()é avant ou après.
            // Dans Server.java: resp.allocate(44), resp.put(proto), resp.put(opcode), ...
            // Donc la taille totale devrait bien être 44.
            assertTrue(response.getLength() >= 42, "La taille de OP_GLOBSERVSTATRES doit être au moins de 42 octets, reçu: " + response.getLength());
            
            ByteBuffer respBuf = ByteBuffer.wrap(buf, 0, response.getLength()).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(Packet.PROTOCOL_ED2K, respBuf.get());
            assertEquals((byte) 0x97, respBuf.get());
            assertEquals(challenge, respBuf.getInt(), "Le challenge doit être identique à l'envoi");
            
            // Vérifier les champs restants (juste s'assurer qu'ils sont lisibles)
            int users = respBuf.getInt();
            int files = respBuf.getInt();
            int maxUsers = respBuf.getInt();
            int softFiles = respBuf.getInt();
            int hardFiles = respBuf.getInt();
            int udpFlags = respBuf.getInt();
            int lowIdUsers = respBuf.getInt();
            int udpPort = respBuf.getShort() & 0xFFFF;
            int tcpPort = respBuf.getShort() & 0xFFFF;
            int serverKey = respBuf.getInt();
            
            assertEquals(TEST_PORT, tcpPort, "Le port TCP dans le paquet UDP est incorrect");
            assertEquals(5000, maxUsers, "MaxUsers incorrect dans OP_GLOBSERVSTATRES");
            assertEquals(1000000, softFiles, "SoftFiles incorrect dans OP_GLOBSERVSTATRES");
            assertEquals(2000000, hardFiles, "HardFiles incorrect dans OP_GLOBSERVSTATRES");
            assertTrue((udpFlags & 0x200) != 0, "Drapeau UDPOBFUSCATION (0x200) manquant dans OP_GLOBSERVSTATRES");
            assertTrue((udpFlags & 0x400) != 0, "Drapeau TCPOBFUSCATION (0x400) manquant dans OP_GLOBSERVSTATRES");
        }
    }
}
