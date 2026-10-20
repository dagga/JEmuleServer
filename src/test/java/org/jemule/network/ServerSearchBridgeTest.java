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
    void splitsAFullServerAnswerIntoOneSearchPerFileType() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket local = bind(loopback); DatagramSocket peerSocket = bind(loopback);
             DatabaseManager database = new DatabaseManager("mem:server_partition_" + System.nanoTime())) {
            peerSocket.setSoTimeout(500);
            ServerGossip.VerifiedPeer peer = new ServerGossip.VerifiedPeer(
                    new ServerGossip.Endpoint(loopback, peerSocket.getLocalPort() - 4), "verified", 0, System.currentTimeMillis());
            try (ServerSearchBridge bridge = new ServerSearchBridge(() -> List.of(peer), () -> local,
                    new FileIndex(database), new FakeFileDetector(), 2000)) {
                for (int search = 0; search <= ServerSearchBridge.MAX_PARTITIONS_PER_SERVER_PER_MINUTE; search++) {
                    String keyword = "linux" + search;
                    byte[] searchTree = keywordTree(keyword);
                    bridge.forkSearch(new SearchQuery.TermQuery(keyword, SearchQuery.ID_FILENAME), searchTree);
                    assertArrayEquals(ServerSearchBridge.searchRequest(searchTree), data(receive(peerSocket)));

                    // The server returns as many results as it sends at most: they are probably truncated
                    java.io.ByteArrayOutputStream answer = new java.io.ByteArrayOutputStream();
                    for (int i = 0; i < ServerSearchBridge.PARTITION_THRESHOLD; i++) {
                        answer.writeBytes(searchResult(String.format("%02d", search) + "%030X".formatted(i + 1),
                                keyword + " " + i + ".iso", 1024, 2));
                    }
                    assertTrue(bridge.handle(loopback, peerSocket.getLocalPort(), answer.toByteArray()));

                    if (search < ServerSearchBridge.MAX_PARTITIONS_PER_SERVER_PER_MINUTE) {
                        for (String type : ServerSearchBridge.PARTITION_TYPES) {
                            assertArrayEquals(ServerSearchBridge.searchRequest(
                                    ServerSearchBridge.withFileType(searchTree, type)), data(receive(peerSocket)));
                        }
                        // Answers to the split searches do not split them again
                        assertTrue(bridge.handle(loopback, peerSocket.getLocalPort(), answer.toByteArray()));
                    }
                    assertThrows(java.net.SocketTimeoutException.class, () -> receive(peerSocket),
                            "nothing more is sent to the server");
                }
            }
        }
    }

    @Test
    void doesNotSplitASearchAlreadyRestrictedToAFileType() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket local = bind(loopback); DatagramSocket peerSocket = bind(loopback);
             DatabaseManager database = new DatabaseManager("mem:server_no_partition_" + System.nanoTime())) {
            peerSocket.setSoTimeout(500);
            ServerGossip.VerifiedPeer peer = new ServerGossip.VerifiedPeer(
                    new ServerGossip.Endpoint(loopback, peerSocket.getLocalPort() - 4), "verified", 0, System.currentTimeMillis());
            try (ServerSearchBridge bridge = new ServerSearchBridge(() -> List.of(peer), () -> local,
                    new FileIndex(database), new FakeFileDetector(), 2000)) {
                SearchQuery query = new SearchQuery.AndQuery(new SearchQuery.TermQuery("linux", SearchQuery.ID_FILENAME),
                        new SearchQuery.TermQuery("Pro", SearchQuery.ID_FILETYPE));
                bridge.forkSearch(query, ServerSearchBridge.withFileType(keywordTree("linux"), "Pro"));
                receive(peerSocket);
                java.io.ByteArrayOutputStream answer = new java.io.ByteArrayOutputStream();
                for (int i = 0; i < 2 * ServerSearchBridge.PARTITION_THRESHOLD; i++) {
                    answer.writeBytes(searchResult("%032X".formatted(i + 1), "linux " + i + ".iso", 1024, 2));
                }
                assertTrue(bridge.handle(loopback, peerSocket.getLocalPort(), answer.toByteArray()));
                assertThrows(java.net.SocketTimeoutException.class, () -> receive(peerSocket));
            }
        }
    }

    @Test
    void typedClientQueriesMatchServerResultsWithAnIntegerTypeOrNoTypeAtAll() {
        SearchQuery videoQuery = new SearchQuery.AndQuery(new SearchQuery.TermQuery("matrix", SearchQuery.ID_FILENAME),
                new SearchQuery.TermQuery("Video", SearchQuery.ID_FILETYPE));
        // Captured from live servers: FT_FILETYPE as an ED2KFT_* integer (2 = video), or no type tag at all
        byte[] integerType = searchResult("0123456789ABCDEF0123456789ABCDEF", List.of(
                new Tag(Tag.TYPE_STRING, Tag.NAME_FILENAME, "The Matrix Reloaded.avi"),
                new Tag(Tag.TYPE_INTEGER, Tag.NAME_FILESIZE, 1_489_940_480),
                new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 6),
                new Tag(Tag.TYPE_INTEGER, Tag.NAME_FILETYPE, 2)));
        byte[] noType = searchResult("FEDCBA9876543210FEDCBA9876543210", "Matrix.1999.1080p.mkv", 1_870_003_012L, 4);

        var decoded = ServerSearchBridge.decodeResults(integerType);
        assertEquals("Video", decoded.get(0).type());
        assertTrue(videoQuery.test(decoded.get(0)));
        assertTrue(videoQuery.test(ServerSearchBridge.decodeResults(noType).get(0)));
    }

    @Test
    void searchGetsItsResultsBeforeTheirIngestion() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket local = bind(loopback); DatagramSocket peerSocket = bind(loopback);
             DatabaseManager database = new DatabaseManager("mem:server_search_ingestion_" + System.nanoTime())) {
            ServerGossip.VerifiedPeer peer = new ServerGossip.VerifiedPeer(
                    new ServerGossip.Endpoint(loopback, peerSocket.getLocalPort() - 4), "verified", 0, System.currentTimeMillis());
            FileIndex index = new FileIndex(database);
            org.jemule.core.IngestionExecutor ingestion = org.jemule.core.IngestionExecutor.create();
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            ingestion.execute(() -> { // the ingestion thread is busy
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            try (ServerSearchBridge bridge = new ServerSearchBridge(() -> List.of(peer), () -> local, index,
                    new FakeFileDetector(), 300)) {
                bridge.setIngestionExecutor(ingestion);
                var future = bridge.forkSearch(new SearchQuery.TermQuery("linux", SearchQuery.ID_FILENAME), keywordTree("linux"));
                assertTrue(bridge.handle(loopback, peerSocket.getLocalPort(),
                        searchResult("0123456789ABCDEF0123456789ABCDEF", "Linux.iso", 1024, 4)));

                assertEquals(1, future.get(2, TimeUnit.SECONDS).size(), "delivered while the ingestion waits");
                assertFalse(index.contains("0123456789abcdef0123456789abcdef"));
                release.countDown();
                ingestion.close();
                assertTrue(index.contains("0123456789abcdef0123456789abcdef"), "then ingested");
                assertEquals(1, bridge.getFilesIngested());
            }
        }
    }

    private static byte[] keywordTree(String keyword) {
        return ByteBuffer.allocate(3 + keyword.length()).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 0x01).putShort((short) keyword.length()).put(keyword.getBytes()).array();
    }

    private static byte[] data(DatagramPacket packet) {
        return java.util.Arrays.copyOf(packet.getData(), packet.getLength());
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
        return searchResult(hash, List.of(
                new Tag(Tag.TYPE_STRING, Tag.NAME_FILENAME, name),
                new Tag(Tag.TYPE_INTEGER, Tag.NAME_FILESIZE, (int) size),
                new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, sources)));
    }

    private static byte[] searchResult(String hash, List<Tag> tags) {
        ByteBuffer packet = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);
        packet.put((byte) 0xE3).put((byte) 0x99);
        for (int i = 0; i < hash.length(); i += 2) {
            packet.put((byte) Integer.parseInt(hash.substring(i, i + 2), 16));
        }
        packet.putInt(0).putShort((short) 0);
        Tag.writeList(packet, tags);
        return java.util.Arrays.copyOf(packet.array(), packet.position());
    }
}
