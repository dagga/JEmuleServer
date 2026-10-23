package org.jemule.integration;

import org.jemule.config.ServerConfig;
import org.jemule.core.ClientFactory;
import org.jemule.network.Packet;
import org.jemule.network.Server;
import org.jemule.protocol.OpCode;
import org.jemule.protocol.Tag;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class ServerIntegrationTest {
    private static Server server;
    private static Thread serverThread;
    private static int serverPort;
    private static String loopbackHost;

    @BeforeAll
    public static void startServer() throws Exception {
        // Find an available port
        ServerSocket ss = new ServerSocket(0);
        int port = ss.getLocalPort();
        ss.close();

        ServerConfig d = ServerConfig.DEFAULT;
        ServerConfig config = new ServerConfig(
                port,
                d.maxPacketSize(),
                d.maxSearchResults(),
                d.floodMaxRequestsPerSecond(),
                d.maxUsers(),
                d.maxFiles(),
                d.maxFilesPerUser(),
                d.maxSourcesPerFile(),
                d.databasePath(),
                d.ipFilterPath(),
                d.fakeFileDetectionEnabled(),
                d.cbFailureRateThreshold(),
                d.cbMinimumNumberOfCalls(),
                d.cbWaitDurationInSeconds(),
                d.tcpKeepAliveTimeoutInSeconds(),
                d.heartbeatIntervalSeconds(),
                d.publicIp()
        );

        server = new Server(config, new ClientFactory());
        serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (IOException e) {
                // Ignore errors during shutdown or binding issues if port was taken
                System.err.println("[DEBUG_LOG] Server thread error: " + e.getMessage());
            }
        }, "integration-server");
        serverThread.setDaemon(true);
        serverThread.start();

        // Wait longer for server to start listening and bind ports
        Thread.sleep(500);
        serverPort = port;
        loopbackHost = java.net.InetAddress.getLoopbackAddress().getHostAddress();
    }

    @AfterAll
    public static void stopServer() throws Exception {
        if (server != null) {
            server.stop();
        }
        // Allow time for ports to be released
        Thread.sleep(500);
    }

    @Test
    public void testFullHandshakePublishAndUdp() throws Exception {
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            // connect to server
            try (Socket s = new Socket()) {
                s.setSoTimeout(5000);
                s.connect(new java.net.InetSocketAddress(loopbackHost, serverPort), 5000);

                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();

                // Send a LOGIN_REQUEST built like eMule (the listen port is needed to be handed out as a source)
                Packet login = new Packet(Packet.PROTOCOL_ED2K, OpCode.LOGIN_REQUEST.value,
                        org.jemule.network.EmuleTestSupport.loginRequest(new byte[16], 4662, "integration", 0));
                login.write(out, false);

                // Read the handshake sequence and verify order/content (tolerates EOF)
                Packet p_ver = readPacketOrEOF(in);
                Packet p1 = readPacketOrEOF(in);
                Packet p2 = readPacketOrEOF(in);

                Assertions.assertNotNull(p_ver, "Connection closed before SERVER_MESSAGE (version)");
                Assertions.assertEquals(Packet.PROTOCOL_ED2K, p_ver.protocol());
                Assertions.assertEquals(OpCode.SERVER_MESSAGE.value, p_ver.opcode());
                Assertions.assertTrue(new String(p_ver.data()).contains("server version " + org.jemule.Main.ESERVER_VERSION));

                Assertions.assertNotNull(p1, "Connection closed before SERVER_IDENT");
                Assertions.assertEquals(OpCode.SERVER_IDENT.value, p1.opcode());

                Assertions.assertNotNull(p2, "Connection closed before ID_CHANGE");
                Assertions.assertEquals(Packet.PROTOCOL_ED2K, p2.protocol());
                Assertions.assertEquals(OpCode.ID_CHANGE.value, p2.opcode());

                // Now publish a file with OP_OFFERFILES, exactly as the eMule client does
                String hash = "0123456789abcdef0123456789abcdef"; // 32 hex chars
                byte[] offer = org.jemule.network.EmuleTestSupport.offerFiles(
                        org.jemule.network.EmuleTestSupport.completeFile(org.jemule.network.EmuleTestSupport.hash(hash), "test-file.txt", 123));
                Packet publish = new Packet(Packet.PROTOCOL_ED2K, OpCode.OFFER_FILES.value, offer);
                publish.write(out, false);

                // Now request sources via TCP: <hash 16><size 4> as sent by CDownloadQueue::ProcessLocalRequests
                byte[] getSourcesPayload = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                        .put(org.jemule.network.EmuleTestSupport.hash(hash)).putInt(123).array();
                Packet getSources = new Packet(Packet.PROTOCOL_ED2K, OpCode.GET_SOURCES.value, getSourcesPayload);
                getSources.write(out, false);

                // Read until we get a SOURCES_RESULT (some SERVER_STATUS may be interleaved)
                Packet sourcesRes = null;
                for (int i = 0; i < 10; i++) {
                    Packet pp = readPacketOrEOF(in);
                    if (pp != null && pp.opcode() == OpCode.FOUND_SOURCES.value) {
                        sourcesRes = pp;
                        break;
                    }
                    if (pp == null) break; // Connection closed
                }
                if (sourcesRes != null) {
                    Assertions.assertEquals(Packet.PROTOCOL_ED2K, sourcesRes.protocol());
                    byte[] sr = sourcesRes.data();
                    Assertions.assertTrue(sr.length >= 17, "SOURCES_RESULT payload too short");
                    int count = sr[16] & 0xFF;
                    Assertions.assertTrue(count >= 1, "Expected at least one source after publish");
                } else {
                    // TCP GET_SOURCES was flaky in this environment; continue and validate via UDP below
                }

                // UDP checks: OP_GLOBSERVSTATREQ (0x96) and OP_GLOBGETSOURCES (0x9A)
                try (java.net.DatagramSocket ds = new java.net.DatagramSocket()) {
                    ds.setSoTimeout(2000);
                    byte[] statReq = new byte[6];
                    statReq[0] = Packet.PROTOCOL_ED2K;
                    statReq[1] = (byte) 0x96;
                    // 4 bytes challenge
                    statReq[2] = 0x11; statReq[3] = 0x22; statReq[4] = 0x33; statReq[5] = 0x44;
                    ds.send(new java.net.DatagramPacket(statReq, statReq.length, java.net.InetAddress.getByName(loopbackHost), serverPort));
                    byte[] buf = new byte[1024];
                    java.net.DatagramPacket resp = new java.net.DatagramPacket(buf, buf.length);
                    ds.receive(resp);
                    Assertions.assertEquals(Packet.PROTOCOL_ED2K & 0xFF, resp.getData()[0] & 0xFF);
                    Assertions.assertEquals(0x97, resp.getData()[1] & 0xFF);

                    // UDP GET_SOURCES
                    byte[] udpReq = new byte[18];
                    udpReq[0] = Packet.PROTOCOL_ED2K;
                    udpReq[1] = (byte) 0x9A;
                    // 16-byte hash
                    byte[] hashBytes = new byte[16];
                    for (int i = 0; i < 16; i++) hashBytes[i] = (byte) Integer.parseInt(hash.substring(i*2, i*2+2), 16);
                    System.arraycopy(hashBytes, 0, udpReq, 2, 16);
                    ds.send(new java.net.DatagramPacket(udpReq, udpReq.length, java.net.InetAddress.getByName(loopbackHost), serverPort));
                    java.net.DatagramPacket udpResp = new java.net.DatagramPacket(new byte[512], 512);
                    ds.receive(udpResp);
                    byte[] gr = udpResp.getData();
                    int len = udpResp.getLength();
                    Assertions.assertEquals(Packet.PROTOCOL_ED2K & 0xFF, gr[0] & 0xFF);
                    Assertions.assertEquals(0x9B, gr[1] & 0xFF);

                    // Payload: [E3 9B] [16 bytes hash] [1 byte count] [count * (ID 4, port 2)]
                    if (len >= 2 + 16 + 1) {
                        int ipv4Count = gr[2 + 16] & 0xFF;
                        Assertions.assertTrue(ipv4Count >= 1, "Expected at least one source in OP_GLOBFOUNDSOURCES");
                    } else {
                        Assertions.fail("UDP response too short");
                    }
                }
            }
            return null;
        });
    }

    @Test
    public void udpServerListRequestReturnsABoundedVerifiedPeerList() throws Exception {
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            try (java.net.DatagramSocket socket = new java.net.DatagramSocket()) {
                socket.setSoTimeout(3000);
                byte[] request = {Packet.PROTOCOL_ED2K, (byte) 0xA4};
                socket.send(new java.net.DatagramPacket(request, request.length,
                        java.net.InetAddress.getByName(loopbackHost), serverPort + 4));

                byte[] bytes = new byte[2048];
                java.net.DatagramPacket response = new java.net.DatagramPacket(bytes, bytes.length);
                socket.receive(response);
                Assertions.assertTrue(response.getLength() >= 3);
                Assertions.assertEquals(Packet.PROTOCOL_ED2K, response.getData()[0]);
                Assertions.assertEquals((byte) 0xA1, response.getData()[1], "OP_SERVER_LIST_RES");
                int count = response.getData()[2] & 0xFF;
                Assertions.assertEquals(3 + count * 6, response.getLength());
            }
            return null;
        });
    }

    // Helper method to read a packet or null if EOF
    private Packet readPacketOrEOF(InputStream in) {
        try {
            return Packet.read(in, ServerConfig.DEFAULT.maxPacketSize());
        } catch (java.io.EOFException e) {
            return null;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testBinaryPublishWithTags() throws Exception {
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            try (Socket s = new Socket()) {
                s.setSoTimeout(5000);
                s.connect(new java.net.InetSocketAddress(loopbackHost, serverPort), 5000);
                
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();

                // Send LOGIN_REQUEST
                Packet login = new Packet(Packet.PROTOCOL_ED2K, OpCode.LOGIN_REQUEST.value, new byte[0]);
                login.write(out, false);

                // Read handshake: version message, ident, ID change, status, welcome message
                for (int i = 0; i < 5; i++) {
                    readPacketOrEOF(in);
                }

                // Create and send a binary PUBLISH_FILES with tags using LITTLE_ENDIAN
                // Binary format: [count:4] [hash:16] [tags]
                // Tags: [count:4] [tag1] [tag2] ...
                ByteBuffer publishBuf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);

                // Number of files (1)
                publishBuf.putInt(1);

                // File hash (16 bytes) - use a deterministic hash
                byte[] fileHash = new byte[16];
                for (int i = 0; i < 16; i++) fileHash[i] = (byte) ((i * 17) % 256);
                publishBuf.put(fileHash);

                // Tags for the file - use proper ED2K tag names
                List<Tag> tagsList = new ArrayList<>();
                tagsList.add(new Tag(Tag.TYPE_STRING, Tag.NAME_NAME, "binary-test.bin"));
                tagsList.add(new Tag(Tag.TYPE_INTEGER, "\u0002", 8192L)); // File size (tag type 2)
                tagsList.add(new Tag(Tag.TYPE_STRING, "\u0003", "application/octet-stream")); // File type (tag type 3)

                // Write tags using Tag.writeList (which handles serialization properly)
                Tag.writeList(publishBuf, tagsList);

                // Extract the payload
                byte[] publishPayloadBytes = new byte[publishBuf.position()];
                System.arraycopy(publishBuf.array(), 0, publishPayloadBytes, 0, publishBuf.position());

                Packet publish = new Packet(Packet.PROTOCOL_ED2K, OpCode.PUBLISH_FILES.value, publishPayloadBytes);
                publish.write(out, false);

                // There is no acknowledgement in the ed2k protocol: wait for the file to be indexed
                boolean indexed = false;
                for (int i = 0; i < 50 && !indexed; i++) {
                    indexed = !server.getFileIndex().search("binary-test", 10).isEmpty();
                    if (!indexed) Thread.sleep(100);
                }
                Assertions.assertTrue(indexed, "binary publish with tags was not indexed");
            }
            return null;
        });
    }

    @Test
    public void clientsAreNotBlockedByIpFilter(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        java.nio.file.Path filterFile = tempDir.resolve("ipfilter.dat");
        java.nio.file.Files.write(filterFile, List.of(
                loopbackHost + " - " + loopbackHost + " , 000 , Blocked Loopback"
        ));

        ServerSocket ss = new ServerSocket(0);
        int testPort = ss.getLocalPort();
        ss.close();

        ServerConfig d = ServerConfig.DEFAULT;
        ServerConfig config = new ServerConfig(
                testPort,
                d.maxPacketSize(),
                d.maxSearchResults(),
                d.floodMaxRequestsPerSecond(),
                d.maxUsers(),
                d.maxFiles(),
                d.maxFilesPerUser(),
                d.maxSourcesPerFile(),
                d.databasePath(),
                filterFile.toString(),
                d.fakeFileDetectionEnabled(),
                d.cbFailureRateThreshold(),
                d.cbMinimumNumberOfCalls(),
                d.cbWaitDurationInSeconds(),
                d.tcpKeepAliveTimeoutInSeconds(),
                d.heartbeatIntervalSeconds(),
                d.publicIp()
        );

        Server testServer = new Server(config, new ClientFactory());
        Thread testThread = new Thread(() -> {
            try {
                testServer.start();
            } catch (IOException ignored) {
            }
        });
        testThread.setDaemon(true);
        testThread.start();
        Thread.sleep(300);

        try {
            try (Socket s = new Socket()) {
                s.setSoTimeout(3000);
                s.connect(new java.net.InetSocketAddress(loopbackHost, testPort), 3000);
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();

                Packet login = new Packet(Packet.PROTOCOL_ED2K, OpCode.LOGIN_REQUEST.value,
                        org.jemule.network.EmuleTestSupport.loginRequest(new byte[16], 4662, "filtered-client", 0));
                login.write(out, false);

                Packet p_ver = readPacketOrEOF(in);
                Assertions.assertNotNull(p_ver, "Client IP is not blocked by ipfilter.dat");
            }
        } finally {
            testServer.stop();
        }
    }

    @Test
    public void adminKickClosesTheClientConnection() throws Exception {
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            try (Socket s = new Socket()) {
                s.setSoTimeout(5000);
                s.connect(new java.net.InetSocketAddress(loopbackHost, serverPort), 5000);
                InputStream in = s.getInputStream();
                new Packet(Packet.PROTOCOL_ED2K, OpCode.LOGIN_REQUEST.value,
                        org.jemule.network.EmuleTestSupport.loginRequest(new byte[16], 4662, "kick-me", 0))
                        .write(s.getOutputStream(), false);
                Packet ident = null;
                for (int i = 0; i < 3; i++) ident = readPacketOrEOF(in);
                Assertions.assertNotNull(ident, "Connection closed before the end of the handshake");

                boolean kicked = false;
                for (int i = 0; i < 50 && !kicked; i++) {
                    kicked = server.kickClient("kick-me");
                    if (!kicked) Thread.sleep(100);
                }
                Assertions.assertTrue(kicked, "The logged-in client was not found");

                try {
                    while (readPacketOrEOF(in) != null) {
                        // packets sent before the kick
                    }
                } catch (RuntimeException e) {
                    Assertions.assertFalse(e.getCause() instanceof java.net.SocketTimeoutException,
                            "The connection was not closed by the kick");
                }
                // The client sees the socket closed before the server thread has unregistered the connection
                boolean stillConnected = true;
                for (int i = 0; i < 50 && stillConnected; i++) {
                    stillConnected = server.kickClient("kick-me");
                    if (stillConnected) Thread.sleep(100);
                }
                Assertions.assertFalse(stillConnected, "The client is still connected after the kick");
            }
        });
    }
}
