package org.jemule.network;

import org.jemule.core.DatabaseManager;
import org.jemule.core.FileIndex;
import org.jemule.core.SearchQuery;
import org.jemule.protocol.Tag;
import org.jemule.security.FakeFileDetector;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ServerSearchBridgeTest {
    @Test
    void sendsSearchToVerifiedPeerAndPersistsOnlyMatchingResults() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket local = bind(loopback); DatagramSocket peerSocket = bind(loopback);
             DatabaseManager database = new DatabaseManager("mem:server_search_" + System.nanoTime())) {
            local.setSoTimeout(2000);
            peerSocket.setSoTimeout(2000);
            int peerTcpPort = peerSocket.getLocalPort() - 4;
            ServerGossip.VerifiedPeer peer = new ServerGossip.VerifiedPeer(
                    new ServerGossip.Endpoint(loopback, peerTcpPort), "verified", 0, System.currentTimeMillis());
            FileIndex index = new FileIndex(database);
            FakeFileDetector fakeFileDetector = new FakeFileDetector();
            ServerSearchBridge bridge = new ServerSearchBridge(() -> List.of(peer), () -> local,
                    index, fakeFileDetector, 1000);
            try (bridge) {
                SearchQuery query = new SearchQuery.TermQuery("linux", SearchQuery.ID_FILENAME);
                byte[] searchTree = ByteBuffer.allocate(3 + 5).order(ByteOrder.LITTLE_ENDIAN)
                        .put((byte) 0x01).putShort((short) 5).put("linux".getBytes()).array();
                var future = bridge.forkSearch(query, searchTree);

                DatagramPacket request = receive(peerSocket);
                assertEquals((byte) 0xE3, request.getData()[0]);
                assertEquals((byte) 0x90, request.getData()[1]);
                ByteBuffer requestData = ByteBuffer.wrap(request.getData(), 2, request.getLength() - 2)
                        .slice().order(ByteOrder.LITTLE_ENDIAN);
                assertEquals(1, Tag.readList(requestData).size());
                assertArrayEquals(searchTree, java.util.Arrays.copyOfRange(request.getData(),
                        2 + requestData.position(), request.getLength()));

                byte[] result = searchResult("0123456789ABCDEF0123456789ABCDEF", "Linux.iso", 1024, 4);
                byte[] nonMatch = searchResult("FEDCBA9876543210FEDCBA9876543210", "Movie.iso", 2048, 2);
                byte[] fakeMatch = searchResult("ABCDEF0123456789ABCDEF0123456789", "Linux crack.iso", 512, 3);
                byte[] responseBytes = new byte[result.length + nonMatch.length + fakeMatch.length];
                System.arraycopy(result, 0, responseBytes, 0, result.length);
                System.arraycopy(nonMatch, 0, responseBytes, result.length, nonMatch.length);
                System.arraycopy(fakeMatch, 0, responseBytes, result.length + nonMatch.length, fakeMatch.length);
                assertEquals(3, ServerSearchBridge.decodeResults(responseBytes).size());
                assertTrue(query.test(ServerSearchBridge.decodeResults(responseBytes).get(0)));
                peerSocket.send(new DatagramPacket(responseBytes, responseBytes.length, request.getSocketAddress()));
                DatagramPacket response = receive(local);
                assertTrue(bridge.handle(loopback, peerSocket.getLocalPort(),
                        java.util.Arrays.copyOf(response.getData(), response.getLength())));

                assertEquals(1, future.get(2, TimeUnit.SECONDS).size());
                assertTrue(index.contains("0123456789abcdef0123456789abcdef"));
                assertFalse(index.contains("fedcba9876543210fedcba9876543210"));
                assertFalse(index.contains("abcdef0123456789abcdef0123456789"));
                assertTrue(database.loadFiles().stream()
                        .anyMatch(file -> file.hash().equals("0123456789abcdef0123456789abcdef")));
            }
        }
    }

    @Test
    void decodesMultipleResultsAndRejectsMalformedOrMetadataIncompletePackets() {
        byte[] first = searchResult("0123456789ABCDEF0123456789ABCDEF", "Linux.iso", 1024, 4);
        byte[] second = searchResult("FEDCBA9876543210FEDCBA9876543210", "Ubuntu.iso", 2048, 2);
        byte[] combined = new byte[first.length + second.length];
        System.arraycopy(first, 0, combined, 0, first.length);
        System.arraycopy(second, 0, combined, first.length, second.length);

        assertEquals(2, ServerSearchBridge.decodeResults(combined).size());
        assertNull(ServerSearchBridge.decodeResults(java.util.Arrays.copyOf(combined, combined.length - 1)));
        assertNull(ServerSearchBridge.decodeResults(new byte[]{(byte) 0xE3, (byte) 0x99}));
    }

    private static DatagramSocket bind(InetAddress address) throws Exception {
        return new DatagramSocket(new InetSocketAddress(address, 0));
    }

    private static DatagramPacket receive(DatagramSocket socket) throws Exception {
        DatagramPacket packet = new DatagramPacket(new byte[2048], 2048);
        socket.receive(packet);
        return packet;
    }

    private static byte[] searchResult(String hash, String name, long size, int sources) {
        ByteBuffer packet = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);
        packet.put((byte) 0xE3).put((byte) 0x99);
        for (int i = 0; i < hash.length(); i += 2) {
            packet.put((byte) Integer.parseInt(hash.substring(i, i + 2), 16));
        }
        packet.putInt(0).putShort((short) 0);
        Tag.writeList(packet, List.of(
                new Tag(Tag.TYPE_STRING, Tag.NAME_FILENAME, name),
                new Tag(Tag.TYPE_INTEGER, Tag.NAME_FILESIZE, (int) size),
                new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, sources)));
        return java.util.Arrays.copyOf(packet.array(), packet.position());
    }
}
