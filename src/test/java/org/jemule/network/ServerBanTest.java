package org.jemule.network;

import org.jemule.config.ServerConfig;
import org.jemule.core.ClientFactory;
import org.jemule.security.ServerBans;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** The administrator bans an eD2K server: forgotten, never heard again, and still banned after a restart. */
class ServerBanTest {
    private static final int PORT = 14671;

    @TempDir
    Path tempDir;

    private Server server(String databasePath) {
        Properties props = new Properties();
        props.setProperty("port", String.valueOf(PORT));
        props.setProperty("databasePath", databasePath);
        props.setProperty("serverPeers", "127.0.0.1:4661, 192.0.2.40:4661");
        props.setProperty("relayServer", "198.51.100.7:4661");
        props.setProperty("ipFilterPath", "");
        props.setProperty("serverMetUrl", "");
        props.setProperty("adminPort", "0");
        props.setProperty("adminWebPort", "0");
        props.setProperty("kadEnabled", "false");
        return new Server(ServerConfig.fromProperties(props), new ClientFactory());
    }

    /** The answers of the server to one UDP datagram from {@code source}, received on its TCP+4 port. */
    private static List<DatagramPacket> udp(Server server, InetAddress source, byte[] datagram) throws Exception {
        SourcesCompatibilityTest.CapturingDatagramSocket socket = new SourcesCompatibilityTest.CapturingDatagramSocket() {
            @Override
            public int getLocalPort() {
                return PORT + 4;
            }
        };
        var handleUdp = Server.class.getDeclaredMethod("handleUdp", java.net.DatagramSocket.class, DatagramPacket.class);
        handleUdp.setAccessible(true);
        handleUdp.invoke(server, socket, new DatagramPacket(datagram, datagram.length, source, 4665));
        socket.close();
        return socket.sent;
    }

    private static byte[] statusRequest() {
        return ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).put((byte) 0xE3).put((byte) 0x96)
                .putInt(0x55AA1234).array();
    }

    /** OP_SERVER_LIST_REQ, the registration of a server: {@code <ip 4><tcp port 2><challenge 4>}. */
    private static byte[] registration(InetAddress address) {
        return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).put((byte) 0xE3).put((byte) 0xA0)
                .put(address.getAddress()).putShort((short) 4661).putInt(77).array();
    }

    @Test
    void aBannedServerIsForgottenIgnoredAndStillBannedAfterARestart() throws Exception {
        String databasePath = tempDir.resolve("db").toString();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        InetAddress relay = InetAddress.getByName("198.51.100.7");
        Server server = server(databasePath);
        try {
            assertTrue(server.isPeerServer(loopback), "a configured peer");
            assertTrue(server.getServerRelay().active(), "relay mode");
            assertEquals(1, udp(server, loopback, statusRequest()).size(), "answered before the ban");

            ServerBans.Ban ban = server.banServer(ServerBans.parse("127.0.0.1"), "  fake server  ");
            assertEquals("fake server", ban.reason());
            Thread.sleep(5); // a later ban
            server.banServer(ServerBans.parse("198.51.100.7"), null);

            assertFalse(server.isPeerServer(loopback), "the ban wins over serverPeers");
            assertTrue(server.getServerViews().stream().noneMatch(v -> v.endpoint().address().equals(loopback)),
                    "forgotten at once");
            assertEquals(1, server.getServerViews().size(), "the other configured peer stays");
            assertTrue(udp(server, loopback, statusRequest()).isEmpty(), "its status requests are dropped");
            assertTrue(udp(server, loopback, registration(loopback)).isEmpty());
            assertTrue(server.getServerViews().stream().noneMatch(v -> v.endpoint().address().equals(loopback)),
                    "its registration is not heard");
            assertFalse(server.getServerRelay().active(), "the ban wins over relayServer: relay mode is off");
            assertFalse(server.isPeerServer(relay));
            assertEquals(0, server.getServerRelay().announce().length, "not listed to the clients");
        } finally {
            server.stop();
        }

        server = server(databasePath);
        try {
            assertEquals(List.of("198.51.100.7", "127.0.0.1"),
                    server.getServerBans().list().stream().map(ServerBans.Ban::ip).toList(), "the most recent first");
            assertEquals("fake server", server.getServerBans().list().get(1).reason());
            assertTrue(server.isServerBanned(loopback));
            assertTrue(server.getServerViews().stream().noneMatch(v -> v.endpoint().address().equals(loopback)),
                    "a configured peer banned is dropped at startup");
            assertTrue(udp(server, loopback, statusRequest()).isEmpty());
            assertFalse(server.getServerRelay().active());

            assertTrue(server.unbanServer(ServerBans.parse("127.0.0.1")));
            assertFalse(server.unbanServer(ServerBans.parse("127.0.0.1")), "no longer banned");
            assertEquals(1, udp(server, loopback, statusRequest()).size(), "heard again");
            assertTrue(server.unbanServer(ServerBans.parse("198.51.100.7")));
            assertTrue(server.getServerRelay().active(), "relay mode is back at once");
        } finally {
            server.stop();
        }

        server = server(databasePath);
        try {
            assertEquals(0, server.getServerBans().size(), "the unban is stored too");
        } finally {
            server.stop();
        }
    }
}
