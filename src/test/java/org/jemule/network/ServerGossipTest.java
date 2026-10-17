package org.jemule.network;

import org.jemule.protocol.Tag;
import org.jemule.security.ServerObfuscation;
import org.jemule.security.UdpObfuscation;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServerGossipTest {
    private static final int CHALLENGE = 0x12345678;

    @Test
    void parsesConfiguredIpv4SeedEndpointsAndRejectsInvalidEntries() {
        var peers = ServerGossip.parseEndpoints("127.0.0.1:4661, 192.0.2.10:5000,");
        assertEquals(2, peers.size());
        assertThrows(IllegalArgumentException.class, () -> ServerGossip.parseEndpoints("127.0.0.1"));
        assertThrows(IllegalArgumentException.class, () -> ServerGossip.parseEndpoints("[::1]:4661"));
        assertThrows(IllegalArgumentException.class, () -> ServerGossip.parseEndpoints("127.0.0.1:65532"));
    }

    @Test
    void acceptsOnlyChallengeMatchedPlausibleServerStatus() throws Exception {
        byte[] status = statusPacket(CHALLENGE, 100, 200, 1000, 500);

        ServerGossip.Status decoded = ServerGossip.validateStatus(status, CHALLENGE);
        assertNotNull(decoded);
        assertEquals(0x12345678, decoded.udpKey());
        assertNull(ServerGossip.validateStatus(status, CHALLENGE + 1));
        assertNull(ServerGossip.validateStatus(java.util.Arrays.copyOf(status, status.length - 1), CHALLENGE));
        assertNull(ServerGossip.validateStatus(statusPacket(CHALLENGE, 1001, 200, 1000, 500), CHALLENGE));
        assertNull(ServerGossip.validateStatus(statusPacket(CHALLENGE, 100, 501, 1000, 500), CHALLENGE));
    }

    @Test
    void requiresWellFormedServerDescriptionAndExcludesMldonkeyIdentity() throws Exception {
        int descriptionChallenge = 0x5678F0FF;
        assertEquals("Verified eD2K", ServerGossip.validateDescription(
                descriptionPacket(descriptionChallenge, "Verified eD2K", "Lugdunum-compatible server"),
                descriptionChallenge));
        assertNull(ServerGossip.validateDescription(
                descriptionPacket(descriptionChallenge, "MLDonkey server", "eDonkey server"),
                descriptionChallenge));
        assertNull(ServerGossip.validateDescription(
                descriptionPacket(descriptionChallenge, "", "No usable identity"),
                descriptionChallenge));
        assertNull(ServerGossip.validateDescription(
                descriptionPacket(descriptionChallenge + 1, "Valid name", "Description"),
                descriptionChallenge));
    }

    @Test
    void decodesServerListsOnlyWhenCountAndPacketLengthAgree() throws Exception {
        byte[] response = ByteBuffer.allocate(3 + 2 * 6)
                .put((byte) 0xE3).put((byte) 0xA1).put((byte) 2)
                .put(new byte[]{(byte) 192, 0, 2, 8}).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 4661)
                .put(new byte[]{(byte) 198, 51, 100, 9}).putShort((short) 5000)
                .array();

        List<ServerGossip.Endpoint> peers = ServerGossip.decodeServerList(response);
        assertNotNull(peers);
        assertEquals(2, peers.size());
        assertEquals(InetAddress.getByName("192.0.2.8"), peers.get(0).address());
        assertEquals(4661, peers.get(0).tcpPort());
        assertEquals(5000, peers.get(1).tcpPort());
        assertNull(ServerGossip.decodeServerList(java.util.Arrays.copyOf(response, response.length - 1)));
    }

    @Test
    void verifiesPeerThenExchangesAnObfuscatedServerList() throws Exception {
        try (DatagramSocket local = bindServerUdpSocket(); DatagramSocket peer = bindServerUdpSocket()) {
            local.setSoTimeout(2000);
            peer.setSoTimeout(2000);
            int localTcpPort = local.getLocalPort() - 4;
            int peerTcpPort = peer.getLocalPort() - 4;
            ServerGossip gossip = new ServerGossip("127.0.0.1:" + peerTcpPort);
            InetAddress loopback = InetAddress.getByName("127.0.0.1");

            gossip.poll(local, System.currentTimeMillis());
            DatagramPacket statusRequest = receive(peer);
            assertEquals((byte) 0xE3, statusRequest.getData()[0]);
            assertEquals((byte) 0x96, statusRequest.getData()[1]);
            int statusChallenge = ByteBuffer.wrap(statusRequest.getData(), 2, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).getInt();
            byte[] status = statusPacket(statusChallenge, 100, 200, 1000, 500,
                    UdpObfuscation.serverKeyFor(loopback));
            peer.send(new DatagramPacket(status, status.length, statusRequest.getSocketAddress()));
            DatagramPacket statusResponse = receive(local);
            assertTrue(gossip.handle(local, loopback, peer.getLocalPort(),
                    java.util.Arrays.copyOf(statusResponse.getData(), statusResponse.getLength()),
                    false, loopback, localTcpPort, System.currentTimeMillis()));

            DatagramPacket descriptionRequest = receive(peer);
            assertEquals((byte) 0xA2, descriptionRequest.getData()[1]);
            int descriptionChallenge = ByteBuffer.wrap(descriptionRequest.getData(), 2, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).getInt();
            byte[] description = descriptionPacket(descriptionChallenge, "Verified server", "Compatible");
            peer.send(new DatagramPacket(description, description.length, descriptionRequest.getSocketAddress()));
            DatagramPacket descriptionResponse = receive(local);
            assertTrue(gossip.handle(local, loopback, peer.getLocalPort(),
                    java.util.Arrays.copyOf(descriptionResponse.getData(), descriptionResponse.getLength()),
                    false, loopback, localTcpPort, System.currentTimeMillis()));

            DatagramPacket encryptedListRequest = receive(peer);
            byte[] decodedListRequest = ServerObfuscation.decode(
                    java.util.Arrays.copyOf(encryptedListRequest.getData(), encryptedListRequest.getLength()),
                    UdpObfuscation.serverKeyFor(loopback));
            assertNotNull(decodedListRequest, "server-list request should use the peer's advertised UDP key");
            assertEquals((byte) 0xA0, decodedListRequest[1]);
            assertArrayEquals(loopback.getAddress(),
                    java.util.Arrays.copyOfRange(decodedListRequest, 2, 6));
            assertEquals(localTcpPort, Short.toUnsignedInt(ByteBuffer.wrap(decodedListRequest, 6, 2)
                    .order(ByteOrder.LITTLE_ENDIAN).getShort()));

            byte[] discovered = new byte[3 + 6];
            ByteBuffer response = ByteBuffer.wrap(discovered).order(ByteOrder.LITTLE_ENDIAN);
            response.put((byte) 0xE3).put((byte) 0xA1).put((byte) 1)
                    .put(new byte[]{(byte) 192, 0, 2, 77}).putShort((short) 4665);
            peer.send(new DatagramPacket(discovered, discovered.length, encryptedListRequest.getSocketAddress()));

            DatagramPacket listResponse = receive(local);
            byte[] responseBytes = java.util.Arrays.copyOf(listResponse.getData(), listResponse.getLength());
            assertTrue(gossip.handle(local, loopback, peer.getLocalPort(), responseBytes,
                    false, loopback, localTcpPort, System.currentTimeMillis()));

            InetAddress discoveredAddress = InetAddress.getByName("192.0.2.77");
            assertEquals("Verified server", gossip.verifiedPeers().iterator().next().name());
            assertTrue(gossip.candidateEndpoints().stream().anyMatch(endpoint ->
                    endpoint.address().equals(discoveredAddress) && endpoint.tcpPort() == 4665));
        }
    }

    @Test
    void rejectsMldonkeyPeerAfterChallengeMatchedStatusAndDescription() throws Exception {
        try (DatagramSocket local = bindServerUdpSocket(); DatagramSocket peer = bindServerUdpSocket()) {
            local.setSoTimeout(2000);
            peer.setSoTimeout(2000);
            int localTcpPort = local.getLocalPort() - 4;
            int peerTcpPort = peer.getLocalPort() - 4;
            ServerGossip gossip = new ServerGossip("127.0.0.1:" + peerTcpPort);
            InetAddress loopback = InetAddress.getByName("127.0.0.1");

            gossip.poll(local, System.currentTimeMillis());
            DatagramPacket statusRequest = receive(peer);
            int statusChallenge = ByteBuffer.wrap(statusRequest.getData(), 2, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).getInt();
            byte[] status = statusPacket(statusChallenge, 100, 200, 1000, 500);
            peer.send(new DatagramPacket(status, status.length, statusRequest.getSocketAddress()));
            DatagramPacket statusResponse = receive(local);
            assertTrue(gossip.handle(local, loopback, peer.getLocalPort(),
                    java.util.Arrays.copyOf(statusResponse.getData(), statusResponse.getLength()),
                    false, loopback, localTcpPort, System.currentTimeMillis()));

            DatagramPacket descriptionRequest = receive(peer);
            int descriptionChallenge = ByteBuffer.wrap(descriptionRequest.getData(), 2, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).getInt();
            byte[] description = descriptionPacket(descriptionChallenge, "MLDonkey server", "Legacy server");
            peer.send(new DatagramPacket(description, description.length, descriptionRequest.getSocketAddress()));
            DatagramPacket descriptionResponse = receive(local);
            assertTrue(gossip.handle(local, loopback, peer.getLocalPort(),
                    java.util.Arrays.copyOf(descriptionResponse.getData(), descriptionResponse.getLength()),
                    false, loopback, localTcpPort, System.currentTimeMillis()));

            assertTrue(gossip.verifiedPeers().isEmpty());
            assertTrue(gossip.candidateEndpoints().isEmpty());
        }
    }

    private static byte[] statusPacket(int challenge, long users, long files, long maxUsers, long hardFiles) {
        return statusPacket(challenge, users, files, maxUsers, hardFiles, 0x12345678);
    }

    private static byte[] statusPacket(int challenge, long users, long files, long maxUsers, long hardFiles,
                                       int udpKey) {
        return ByteBuffer.allocate(42).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 0xE3).put((byte) 0x97).putInt(challenge)
                .putInt((int) users).putInt((int) files).putInt((int) maxUsers)
                .putInt(1000).putInt((int) hardFiles).putInt(0x200).putInt(5)
                .putShort((short) 4673).putShort((short) 4661).putInt(udpKey)
                .array();
    }

    private static DatagramSocket bindServerUdpSocket() throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            DatagramSocket socket = new DatagramSocket(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            if (socket.getLocalPort() >= 5 && socket.getLocalPort() <= 65535) return socket;
            socket.close();
        }
        throw new IllegalStateException("Could not bind a server UDP socket");
    }

    private static DatagramPacket receive(DatagramSocket socket) throws Exception {
        DatagramPacket packet = new DatagramPacket(new byte[2048], 2048);
        socket.receive(packet);
        return packet;
    }

    private static byte[] descriptionPacket(int challenge, String name, String description) {
        ByteBuffer buffer = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 0xE3).put((byte) 0xA3).putInt(challenge);
        Tag.writeList(buffer, List.of(
                new Tag(Tag.TYPE_STRING, Tag.NAME_SERVERNAME, name),
                new Tag(Tag.TYPE_STRING, Tag.NAME_DESCRIPTION, description)));
        return java.util.Arrays.copyOf(buffer.array(), buffer.position());
    }
}
