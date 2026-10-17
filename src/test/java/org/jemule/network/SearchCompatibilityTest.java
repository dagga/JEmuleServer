package org.jemule.network;

import org.jemule.config.ServerConfig;
import org.jemule.core.ClientRegistry;
import org.jemule.core.FileIndex;
import org.jemule.core.FileMetadata;
import org.jemule.kad.*;
import org.jemule.network.handler.PublishHandler;
import org.jemule.network.handler.SearchHandler;
import org.jemule.protocol.OpCode;
import org.jemule.protocol.Tag;
import org.jemule.security.ChildAbuseFilter;
import org.jemule.security.FakeFileDetector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.jemule.network.EmuleTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * OP_SEARCHREQUEST built like GetSearchPacket (SearchResultsWnd.cpp) and OP_SEARCHRESULT decoded like
 * CSearchList::ProcessSearchAnswer / CSearchFile (eMule 0.50a).
 */
class SearchCompatibilityTest {

    FileIndex index;
    ClientRegistry registry;
    FakeContext client;

    @BeforeEach
    void setup() throws Exception {
        index = new FileIndex(null);
        registry = new ClientRegistry();
        client = loggedInContext("10.1.2.3", 50000, ServerConfig.DEFAULT, registry, index);
    }

    void publish(String hexHash, String name, long size, int type) throws Exception {
        byte[] file = offeredFile(hash(hexHash), 0xFBFBFBFB, 0xFBFB,
                new NewTags().str(0x01, name).num(0x02, size & 0xFFFFFFFFL).num(0x03, type));
        if (size > 0xFFFFFFFFL) {
            file = offeredFile(hash(hexHash), 0xFBFBFBFB, 0xFBFB,
                    new NewTags().str(0x01, name).num(0x02, size & 0xFFFFFFFFL).num(0x3A, size >>> 32).num(0x03, type));
        }
        new PublishHandler().handlePublish(client, OpCode.OFFER_FILES, offerFiles(file), new ByteArrayOutputStream());
    }

    SearchAnswer search(byte[] tree) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new SearchHandler().handleSearch(client, tree, out);
        Packet p = Packet.read(new ByteArrayInputStream(out.toByteArray()), 1 << 20);
        assertEquals(Packet.PROTOCOL_ED2K, p.protocol());
        assertEquals(OpCode.SEARCH_RESULT.value, p.opcode());
        return readSearchAnswer(p.data());
    }

    static List<String> names(SearchAnswer a) {
        return a.results().stream().map(r -> (String) r.tag(Tag.NAME_FILENAME)).sorted().toList();
    }

    @Test
    void resultCountIsLittleEndian() throws Exception {
        publish("11111111111111111111111111111111", "holiday video.avi", 700_000_000L, 2);
        SearchAnswer a = search(new SearchTree().keyword("holiday").toBytes());
        assertEquals(1, a.results().size());
        SearchResult r = a.results().getFirst();
        assertEquals("11111111111111111111111111111111", r.hash());
        assertEquals("holiday video.avi", r.tag(Tag.NAME_FILENAME));
        assertEquals("Video", r.tag(Tag.NAME_FILETYPE));
        assertEquals(1, ((Number) r.tag(Tag.NAME_SOURCES)).intValue());
    }

    @Test
    void nonAsciiKeywordsOnlyMatchTheirFiles() throws Exception {
        publish("11111111111111111111111111111111", "Vacances d'été 2025.avi", 700_000_000L, 2);
        publish("22222222222222222222222222222222", "Привет мир.mp3", 5_000_000L, 1);
        publish("33333333333333333333333333333333", "日本語ドラマ 01.mkv", 400_000_000L, 2);
        publish("44444444444444444444444444444444", "unrelated file.txt", 1000L, 5);

        assertEquals(List.of("Vacances d'été 2025.avi"), names(search(new SearchTree().keyword("été").toBytes())));
        assertEquals(List.of("Привет мир.mp3"), names(search(new SearchTree().keyword("ПРИВЕТ").toBytes())));
        assertEquals(List.of("日本語ドラマ 01.mkv"), names(search(new SearchTree().keyword("日本語").toBytes())));
        assertTrue(search(new SearchTree().keyword("中文").toBytes()).results().isEmpty());
    }

    @Test
    void sixtyFourBitSizeParameterIsSupported() throws Exception {
        publish("11111111111111111111111111111111", "big movie.mkv", 6_000_000_000L, 2);
        publish("22222222222222222222222222222222", "small movie.mkv", 700_000_000L, 2);
        // "movie" AND size > 5 GB : with LARGEFILES the client writes the value as uint64 (type 8)
        byte[] tree = new SearchTree().and().keyword("movie").num(0x02, 1, 5_000_000_000L).toBytes();
        assertEquals(List.of("big movie.mkv"), names(search(tree)));
    }

    @Test
    void malformedRequestGetsAnEmptyAnswer() throws Exception {
        publish("11111111111111111111111111111111", "holiday video.avi", 700_000_000L, 2);
        SearchAnswer a = search(new byte[]{0x01, 0x10}); // truncated keyword
        assertTrue(a.results().isEmpty(), "no guess on raw bytes");
    }

    @Test
    void stringMetaTagWithStringNameIsReadWithoutMisalignment() throws Exception {
        publish("11111111111111111111111111111111", "holiday video.avi", 700_000_000L, 2);
        // AND(meta "bitrate"="x" with a string tag name, keyword "holiday")
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(64).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 0).put((byte) 0);
        b.put((byte) 2).putShort((short) 1).put((byte) 'x').putShort((short) 7).put("bitrate".getBytes());
        b.put((byte) 1).putShort((short) 7).put("holiday".getBytes());
        assertEquals(List.of("holiday video.avi"), names(search(java.util.Arrays.copyOf(b.array(), b.position()))));
    }

    @Test
    void sizeBoundsKeepTheClientOperatorsStrict() throws Exception {
        publish("11111111111111111111111111111111", "size 999.bin", 999, 4);
        publish("22222222222222222222222222222222", "size 1000.bin", 1000, 4);
        publish("33333333333333333333333333333333", "size 2000.bin", 2000, 4);
        publish("44444444444444444444444444444444", "size 2001.bin", 2001, 4);
        // User asks for 1000 <= size <= 2000: GetSearchPacket sends size > 999 AND size < 2001
        byte[] tree = new SearchTree().and().and().keyword("size")
                .num(0x02, 1, 999).num(0x02, 2, 2001).toBytes();
        assertEquals(List.of("size 1000.bin", "size 2000.bin"), names(search(tree)));
    }

    @Test
    void availabilityAndCompleteSourcesFilters() throws Exception {
        publish("11111111111111111111111111111111", "popular song.mp3", 5_000_000, 1);
        publish("22222222222222222222222222222222", "rare song.mp3", 5_000_000, 1);
        FakeContext other = loggedInContext("10.4.5.6", 50002, ServerConfig.DEFAULT, registry, index);
        // second source of the popular song, but only a part file (ID 0xFCFCFCFC)
        new PublishHandler().handlePublish(other, OpCode.OFFER_FILES, offerFiles(offeredFile(hash("11111111111111111111111111111111"),
                0xFCFCFCFC, 0xFCFC, new NewTags().str(0x01, "popular song.mp3").num(0x02, 5_000_000).num(0x03, 1))), new ByteArrayOutputStream());

        // availability >= 2 is sent as FT_SOURCES > 1
        assertEquals(List.of("popular song.mp3"), names(search(new SearchTree().and().keyword("song").num(0x15, 1, 1).toBytes())));
        // complete sources >= 2 is sent as FT_COMPLETE_SOURCES > 1: only one complete source
        assertTrue(search(new SearchTree().and().keyword("song").num(0x30, 1, 1).toBytes()).results().isEmpty());

        SearchResult popular = search(new SearchTree().keyword("popular").toBytes()).results().getFirst();
        assertEquals(2, ((Number) popular.tag(Tag.NAME_SOURCES)).intValue());
        assertEquals(1, ((Number) popular.tag(Tag.NAME_COMPLETE_SOURCES)).intValue());
    }

    @Test
    void mediaFilters() throws Exception {
        byte[] withMedia = offeredFile(hash("11111111111111111111111111111111"), 0xFBFBFBFB, 0xFBFB, new NewTags()
                .str(0x01, "concert.avi").num(0x02, 700_000_000).num(0x03, 2)
                .num(0xD3, 3600).num(0xD4, 1200).str(0xD5, "xvid"));
        new PublishHandler().handlePublish(client, OpCode.OFFER_FILES, offerFiles(withMedia), new ByteArrayOutputStream());
        publish("22222222222222222222222222222222", "concert bootleg.avi", 700_000_000, 2);

        assertEquals(List.of("concert.avi"), names(search(new SearchTree().and().keyword("concert").meta(0xD5, "xvid").toBytes())));
        assertEquals(List.of("concert.avi"), names(search(new SearchTree().and().keyword("concert").num(0xD4, 1, 999).toBytes())));
        assertEquals(List.of("concert.avi"), names(search(new SearchTree().and().keyword("concert").num(0xD3, 1, 1799).toBytes())));
        assertEquals(List.of("concert.avi", "concert bootleg.avi").stream().sorted().toList(),
                names(search(new SearchTree().and().keyword("concert").meta(0x03, "Video").toBytes())));
    }

    @Test
    void moreResultsFlagAndQueryMore() throws Exception {
        for (int i = 0; i < 60; i++) {
            publish(String.format("%032x", i + 1), "episode " + i + ".avi", 100_000_000L + i, 2);
        }
        SearchAnswer first = search(new SearchTree().keyword("episode").toBytes());
        assertEquals(50, first.results().size());
        assertEquals(1, first.more(), "more results are available");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new SearchHandler().handleQueryMoreResult(client, out);
        Packet p = Packet.read(new ByteArrayInputStream(out.toByteArray()), 1 << 20);
        SearchAnswer second = readSearchAnswer(p.data());
        assertEquals(10, second.results().size());
        assertEquals(0, second.more());
    }

    @Test
    void singleAnswerSaysNoMoreResults() throws Exception {
        publish("11111111111111111111111111111111", "holiday video.avi", 700_000_000L, 2);
        SearchAnswer a = search(new SearchTree().keyword("holiday").toBytes());
        assertEquals(0, a.more());
        assertEquals(1, a.trailingBytes());
    }

    // ===============================================================
    // Point 4.2 — Unified Delivery & Result Merging Tests
    // ===============================================================

    @Test
    void testForkedKadSearchWithLocalResultsAnswersAtOnce() throws Exception {
        // 1. Publish 1 local file
        String localHash = "11111111111111111111111111111111";
        publish(localHash, "matrix-reloaded.avi", 700_000_000L, 2);

        // 2. Set up Kad bridge
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        try (KadUdpService serverUdp = new KadUdpService(0);
             KadUdpService peerUdp = new KadUdpService(0);
             KadSearchBridge bridge = new KadSearchBridge(table, serverUdp,
                     ChildAbuseFilter.defaults(), new FakeFileDetector(), 3, 2000)) {

            serverUdp.start();
            peerUdp.start();
            bridge.start();

            KadId peerId = table.generateRandomIdForBucket(1);
            KadContact peerContact = new KadContact(peerId, InetAddress.getLoopbackAddress(), peerUdp.getPort());
            table.addOrUpdate(peerContact);

            CountDownLatch reqLatch = new CountDownLatch(1);
            peerUdp.registerListener(KadOpCode.SEARCH_KEY_REQ, (packet, addr, port) -> reqLatch.countDown());

            client.setKadSearchBridge(bridge);

            // Warm search: the local result is sent without waiting for the 2 s Kad timeout
            long start = System.nanoTime();
            SearchAnswer answer = search(new SearchTree().keyword("matrix").toBytes());
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertEquals(List.of(localHash), answer.results().stream().map(SearchResult::hash).toList());
            assertEquals(0, answer.more());
            assertTrue(elapsedMs < 1000, "the local results must not wait for Kad: " + elapsedMs + " ms");
            assertTrue(reqLatch.await(3, TimeUnit.SECONDS), "the search is still forked to Kad");
        }
    }

    @Test
    void testForkedKadSearchDeduplicatesKadResults() throws Exception {
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        try (KadUdpService serverUdp = new KadUdpService(0);
             KadUdpService peerA = new KadUdpService(0);
             KadUdpService peerB = new KadUdpService(0);
             KadSearchBridge bridge = new KadSearchBridge(table, serverUdp,
                     ChildAbuseFilter.defaults(), new FakeFileDetector(), 3, 1000)) {

            serverUdp.start();
            peerA.start();
            peerB.start();
            bridge.start();

            table.addOrUpdate(new KadContact(table.generateRandomIdForBucket(1), InetAddress.getLoopbackAddress(), peerA.getPort()));
            table.addOrUpdate(new KadContact(table.generateRandomIdForBucket(2), InetAddress.getLoopbackAddress(), peerB.getPort()));

            String sharedHash = "22222222222222222222222222222222";
            KadId keywordHash = KadId.fromKeyword("matrix");
            FileMetadata shared = new FileMetadata(sharedHash, "matrix-revolutions.avi", 800_000_000L, "Video",
                    List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 5)));

            // Both peers announce the same file, each one also knows another file
            CountDownLatch reqLatch = new CountDownLatch(2);
            for (KadUdpService peer : List.of(peerA, peerB)) {
                String ownHash = peer == peerA ? "33333333333333333333333333333333" : "44444444444444444444444444444444";
                peer.registerListener(KadOpCode.SEARCH_KEY_REQ, (packet, addr, port) -> {
                    reqLatch.countDown();
                    List<FileMetadata> files = List.of(shared, new FileMetadata(ownHash, "matrix-" + ownHash + ".avi",
                            700_000_000L, "Video", List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 2))));
                    try {
                        peer.send(KadPacket.createSearchRes(keywordHash, files), addr, port);
                    } catch (Exception e) {
                        fail(e);
                    }
                });
            }

            client.setKadSearchBridge(bridge);

            // Cold search: the Kad answers are merged, the file announced twice is sent once
            SearchAnswer answer = search(new SearchTree().keyword("matrix").toBytes());
            assertTrue(reqLatch.await(3, TimeUnit.SECONDS), "Both peers should receive the search request");

            assertEquals(List.of(sharedHash, "33333333333333333333333333333333", "44444444444444444444444444444444"),
                    answer.results().stream().map(SearchResult::hash).sorted().toList());
            assertEquals(0, answer.more());
        }
    }

    @Test
    void testForkedKadSearchMissingLocallyDeliversKadResults() throws Exception {
        // No local files published
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        try (KadUdpService serverUdp = new KadUdpService(0);
             KadUdpService peerUdp = new KadUdpService(0);
             KadSearchBridge bridge = new KadSearchBridge(table, serverUdp,
                     ChildAbuseFilter.defaults(), new FakeFileDetector(), 3, 2000)) {

            serverUdp.start();
            peerUdp.start();
            bridge.start();

            KadId peerId = table.generateRandomIdForBucket(1);
            KadContact peerContact = new KadContact(peerId, InetAddress.getLoopbackAddress(), peerUdp.getPort());
            table.addOrUpdate(peerContact);

            String hash1 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
            String hash2 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
            KadId keywordHash = KadId.fromKeyword("cyberpunk");

            CountDownLatch reqLatch = new CountDownLatch(1);
            peerUdp.registerListener(KadOpCode.SEARCH_KEY_REQ, (packet, addr, port) -> {
                reqLatch.countDown();
                List<FileMetadata> files = List.of(
                        new FileMetadata(hash1, "cyberpunk-2077.iso", 50_000_000_000L, "Archive",
                                List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 10))),
                        new FileMetadata(hash2, "cyberpunk-edgerunners.mkv", 2_000_000_000L, "Video",
                                List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 8)))
                );
                KadPacket resPkt = KadPacket.createSearchRes(keywordHash, files);
                try {
                    peerUdp.send(resPkt, addr, port);
                } catch (Exception e) {
                    fail(e);
                }
            });

            client.setKadSearchBridge(bridge);

            SearchAnswer answer = search(new SearchTree().keyword("cyberpunk").toBytes());
            assertTrue(reqLatch.await(3, TimeUnit.SECONDS), "Peer should receive search request");

            assertEquals(2, answer.results().size());
            assertEquals(0, answer.more());
        }
    }

    @Test
    void testForkedKadSearchColdDiscoveryPagination() throws Exception {
        // No local files published
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        try (KadUdpService serverUdp = new KadUdpService(0);
             KadUdpService peerUdp = new KadUdpService(0);
             KadSearchBridge bridge = new KadSearchBridge(table, serverUdp,
                     ChildAbuseFilter.defaults(), new FakeFileDetector(), 3, 2000)) {

            serverUdp.start();
            peerUdp.start();
            bridge.start();

            KadId peerId = table.generateRandomIdForBucket(1);
            KadContact peerContact = new KadContact(peerId, InetAddress.getLoopbackAddress(), peerUdp.getPort());
            table.addOrUpdate(peerContact);

            KadId keywordHash = KadId.fromKeyword("anime");
            List<FileMetadata> sixtyFiles = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                sixtyFiles.add(new FileMetadata(
                        String.format("%032x", i + 100),
                        "anime-episode-" + i + ".mkv",
                        500_000_000L + i,
                        "Video",
                        List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 2))
                ));
            }

            CountDownLatch reqLatch = new CountDownLatch(1);
            peerUdp.registerListener(KadOpCode.SEARCH_KEY_REQ, (packet, addr, port) -> {
                reqLatch.countDown();
                KadPacket resPkt = KadPacket.createSearchRes(keywordHash, sixtyFiles);
                try {
                    peerUdp.send(resPkt, addr, port);
                } catch (Exception e) {
                    fail(e);
                }
            });

            client.setKadSearchBridge(bridge);

            // First batch (50 items, more=1)
            SearchAnswer first = search(new SearchTree().keyword("anime").toBytes());
            assertTrue(reqLatch.await(3, TimeUnit.SECONDS), "Peer should receive search request");
            assertEquals(50, first.results().size());
            assertEquals(1, first.more(), "More results flag must be set for cold search > 50 items");

            // Query more (remaining 10 items, more=0)
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new SearchHandler().handleQueryMoreResult(client, out);
            Packet p = Packet.read(new ByteArrayInputStream(out.toByteArray()), 1 << 20);
            SearchAnswer second = readSearchAnswer(p.data());
            assertEquals(10, second.results().size());
            assertEquals(0, second.more(), "More results flag must be 0 for last batch");
        }
    }

    // ===============================================================
    // Point 4.3 — Direct Retrieval on Subsequent Searches (Warm Cache)
    // ===============================================================

    @Test
    void testWarmSearchDirectRetrievalFromMemoryWithoutMoreResults() throws Exception {
        String dbPath = "mem:warm_search_test_" + System.currentTimeMillis();
        try (org.jemule.core.DatabaseManager db = new org.jemule.core.DatabaseManager(dbPath);
             KadUdpService serverUdp = new KadUdpService(0);
             KadUdpService peerUdp = new KadUdpService(0)) {

            FileIndex sharedIndex = new FileIndex(db);
            ClientRegistry sharedRegistry = new ClientRegistry();

            KadId localId = KadId.random();
            KadRoutingTable table = new KadRoutingTable(localId);

            try (KadSearchBridge bridge = new KadSearchBridge(
                    table, serverUdp, ChildAbuseFilter.defaults(), new FakeFileDetector(),
                    sharedIndex, db, 3, 2000, true, 1)) {

                serverUdp.start();
                peerUdp.start();
                bridge.start();

                KadId peerId = table.generateRandomIdForBucket(1);
                KadContact peerContact = new KadContact(peerId, InetAddress.getLoopbackAddress(), peerUdp.getPort());
                table.addOrUpdate(peerContact);

                String hash1 = "1234567890abcdef1234567890abcdef";
                String hash2 = "fedcba0987654321fedcba0987654321";
                KadId keywordHash = KadId.fromKeyword("ubuntu");

                CountDownLatch peerLatch = new CountDownLatch(1);
                peerUdp.registerListener(KadOpCode.SEARCH_KEY_REQ, (packet, addr, port) -> {
                    peerLatch.countDown();
                    List<FileMetadata> files = List.of(
                            new FileMetadata(hash1, "ubuntu-desktop.iso", 4_500_000_000L, "Archive",
                                    List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 5))),
                            new FileMetadata(hash2, "ubuntu-server.iso", 2_000_000_000L, "Archive",
                                    List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 3)))
                    );
                    KadPacket resPkt = KadPacket.createSearchRes(keywordHash, files);
                    try {
                        peerUdp.send(resPkt, addr, port);
                    } catch (Exception e) {
                        fail(e);
                    }
                });

                // Phase 1: Client A executes Cold Search (discovers and auto-ingests into H2 + FileIndex)
                FakeContext clientA = loggedInContext("10.1.1.1", 50001, ServerConfig.DEFAULT, sharedRegistry, sharedIndex);
                clientA.setKadSearchBridge(bridge);

                ByteArrayOutputStream outA = new ByteArrayOutputStream();
                new SearchHandler().handleSearch(clientA, new SearchTree().keyword("ubuntu").toBytes(), outA);
                assertTrue(peerLatch.await(3, TimeUnit.SECONDS));

                Packet pA = Packet.read(new ByteArrayInputStream(outA.toByteArray()), 1 << 20);
                SearchAnswer answerA = readSearchAnswer(pA.data());
                assertEquals(2, answerA.results().size());

                // Verify files are now saved in H2 Database and FileIndex
                assertTrue(sharedIndex.contains(hash1));
                assertTrue(sharedIndex.contains(hash2));
                assertEquals(2, db.loadFiles().size());

                // Phase 2: Client B connects later and executes Warm Search
                // No Kad bridge attached to Client B (or Kad offline) -> files served directly from local memory/H2
                FakeContext clientB = loggedInContext("10.2.2.2", 50002, ServerConfig.DEFAULT, sharedRegistry, sharedIndex);

                ByteArrayOutputStream outB = new ByteArrayOutputStream();
                long startMs = System.currentTimeMillis();
                new SearchHandler().handleSearch(clientB, new SearchTree().keyword("ubuntu").toBytes(), outB);
                long durationMs = System.currentTimeMillis() - startMs;

                Packet pB = Packet.read(new ByteArrayInputStream(outB.toByteArray()), 1 << 20);
                SearchAnswer answerB = readSearchAnswer(pB.data());

                // Must be returned instantly (< 100ms in test VM, < 5ms in production) in the first batch with more=0
                assertEquals(2, answerB.results().size(), "Warm search must return both ingested files directly");
                assertEquals(0, answerB.more(), "Warm search with <= 50 results must have more=0 (no 'More results' needed)");
                assertTrue(durationMs < 500, "Warm search should execute instantaneously without network waits");

                List<String> bHashes = answerB.results().stream().map(SearchResult::hash).toList();
                assertTrue(bHashes.contains(hash1));
                assertTrue(bHashes.contains(hash2));
            }
        }
    }

    @Test
    void testWarmSearchPersistentAcrossServerRestart() throws Exception {
        String dbPath = "mem:restart_search_test_" + System.currentTimeMillis() + ";DB_CLOSE_DELAY=-1";
        String hash = "abcdef0123456789abcdef0123456789";

        // 1. Initial server run: Kad file is ingested into H2 database
        try (org.jemule.core.DatabaseManager db = new org.jemule.core.DatabaseManager(dbPath)) {
            FileIndex index1 = new FileIndex(db);
            FileMetadata kadFile = new FileMetadata(hash, "debian-netinst.iso", 650_000_000L, "Archive",
                    List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 8)));
            index1.addFile(kadFile);

            // Verified saved in DB
            assertEquals(1, db.loadFiles().size());
        }

        // 2. Simulated server restart: new FileIndex loads files from the existing DatabaseManager
        try (org.jemule.core.DatabaseManager db = new org.jemule.core.DatabaseManager(dbPath)) {
            FileIndex index2 = new FileIndex(db);
            ClientRegistry registry2 = new ClientRegistry();

            assertTrue(index2.contains(hash), "FileIndex must load ingested Kad files from DB upon startup");

            FakeContext newClient = loggedInContext("10.3.3.3", 50003, ServerConfig.DEFAULT, registry2, index2);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new SearchHandler().handleSearch(newClient, new SearchTree().keyword("debian").toBytes(), out);

            Packet p = Packet.read(new ByteArrayInputStream(out.toByteArray()), 1 << 20);
            SearchAnswer answer = readSearchAnswer(p.data());

            assertEquals(1, answer.results().size(), "Search after server restart must find ingested Kad file");
            assertEquals(hash, answer.results().get(0).hash());
            assertEquals("debian-netinst.iso", answer.results().get(0).tag(Tag.NAME_FILENAME));
            assertEquals(0, answer.more());
        }
    }
}
