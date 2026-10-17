/*
 * JEmuleServer - An eMule server.
 * Copyright (C) 2026 Nicolas Hernandez (hernicatgmail.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.jemule.kad;

import org.jemule.core.FileMetadata;
import org.jemule.core.SearchQuery;
import org.jemule.protocol.Tag;
import org.jemule.security.ChildAbuseFilter;
import org.jemule.security.FakeFileDetector;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class KadSearchBridgeTest {

    private InetAddress testIp(String ip) {
        try {
            return InetAddress.getByName(ip);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ===============================================================
    // Point 3.1 — Asynchronous Search Forking Bridge
    // ===============================================================

    @Test
    void testExtractKeywordFromQuery() {
        SearchQuery q1 = new SearchQuery.TermQuery("ubuntu", SearchQuery.ID_FILENAME);
        assertEquals("ubuntu", KadSearchBridge.extractKeyword(q1));

        SearchQuery q2 = new SearchQuery.AndQuery(
                new SearchQuery.TermQuery("linux", SearchQuery.ID_FILENAME),
                new SearchQuery.NumericQuery(SearchQuery.ID_FILESIZE, SearchQuery.OP_GREATER, 1000)
        );
        assertEquals("linux", KadSearchBridge.extractKeyword(q2));

        SearchQuery q3 = new SearchQuery.OrQuery(
                new SearchQuery.NumericQuery(SearchQuery.ID_FILESIZE, SearchQuery.OP_GREATER, 500),
                new SearchQuery.TermQuery("debian", SearchQuery.ID_FILENAME)
        );
        assertEquals("debian", KadSearchBridge.extractKeyword(q3));

        assertNull(KadSearchBridge.extractKeyword(new SearchQuery.NumericQuery(SearchQuery.ID_FILESIZE, SearchQuery.OP_GREATER, 500)));
    }

    @Test
    void testForkSearchSendsRequestAndCollectsResults() throws Exception {
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        try (KadUdpService serverUdp = new KadUdpService(0);
             KadUdpService peerUdp = new KadUdpService(0);
             KadSearchBridge bridge = new KadSearchBridge(table, serverUdp, 3, 2000)) {

            serverUdp.start();
            peerUdp.start();
            bridge.start();

            // Add peer contact in routing table
            KadId peerId = table.generateRandomIdForBucket(1);
            KadContact peerContact = new KadContact(peerId, InetAddress.getLoopbackAddress(), peerUdp.getPort());
            table.addOrUpdate(peerContact);

            CountDownLatch searchReqLatch = new CountDownLatch(1);
            String targetKeyword = "matrix";
            KadId keywordHash = KadId.fromKeyword(targetKeyword);
            String expectedFileHash = "aabbccddeeff00112233445566778899";

            peerUdp.registerListener(KadOpCode.SEARCH_KEY_REQ, (packet, addr, port) -> {
                KadPacket.SearchKeyReq req = KadPacket.parseSearchKeyReq(packet.data());
                if (req != null && req.keywordHash().equals(keywordHash)) {
                    searchReqLatch.countDown();
                    // Reply with SEARCH_RES
                    FileMetadata meta = new FileMetadata(expectedFileHash, "matrix-reloaded.avi", 700000000L, "Video",
                            List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 5)));
                    KadPacket resPkt = KadPacket.createSearchRes(keywordHash, List.of(meta));
                    try {
                        peerUdp.send(resPkt, addr, port);
                    } catch (Exception e) {
                        fail(e);
                    }
                }
            });

            CompletableFuture<List<FileMetadata>> future = bridge.forkSearch(targetKeyword);

            assertTrue(searchReqLatch.await(3, TimeUnit.SECONDS), "Peer did not receive SEARCH_KEY_REQ");

            List<FileMetadata> results = future.get(3, TimeUnit.SECONDS);
            assertNotNull(results);
            assertEquals(1, results.size());
            assertEquals(expectedFileHash, results.get(0).hash());
            assertEquals("matrix-reloaded.avi", results.get(0).name());
        }
    }

    @Test
    void testForkSearchTimeoutCompletesCleanly() throws Exception {
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        try (KadUdpService serverUdp = new KadUdpService(0);
             KadSearchBridge bridge = new KadSearchBridge(table, serverUdp, 3, 300)) {

            serverUdp.start();
            bridge.start();

            // Search with no responding peers
            CompletableFuture<List<FileMetadata>> future = bridge.forkSearch("nonexistent");
            List<FileMetadata> results = future.get(2, TimeUnit.SECONDS);
            assertNotNull(results);
            assertTrue(results.isEmpty());
        }
    }

    @Test
    void testForkSearchWithoutContactsCompletesWithoutWaitingForTheTimeout() throws Exception {
        KadRoutingTable table = new KadRoutingTable(KadId.random());

        try (KadUdpService serverUdp = new KadUdpService(0);
             KadSearchBridge bridge = new KadSearchBridge(table, serverUdp, 3, 10_000)) {
            serverUdp.start();
            bridge.start();

            // Isolated DHT: no contact can be queried, so the search is over at once
            CompletableFuture<List<FileMetadata>> future = bridge.forkSearch("isolated");
            assertTrue(future.isDone(), "a search sent to nobody must not wait for the 10 s timeout");
            assertTrue(future.get().isEmpty());
            assertEquals(0, bridge.getActiveSessionCount());
        }
    }

    @Test
    void testForkSearchOnClosedSocketCompletesWithoutWaitingForTheTimeout() throws Exception {
        KadRoutingTable table = new KadRoutingTable(KadId.random());
        table.addOrUpdate(new KadContact(table.generateRandomIdForBucket(1), InetAddress.getLoopbackAddress(), 4672));

        try (KadUdpService serverUdp = new KadUdpService(0);
             KadSearchBridge bridge = new KadSearchBridge(table, serverUdp, 3, 10_000)) {
            serverUdp.start();
            bridge.start();
            serverUdp.close();

            // Every send fails: the search ends immediately instead of hanging the client
            CompletableFuture<List<FileMetadata>> future = bridge.forkSearch("closed");
            assertTrue(future.isDone(), "a search whose requests all failed must not wait for the 10 s timeout");
            assertTrue(future.get().isEmpty());
            assertEquals(0, bridge.getActiveSessionCount());
        }
    }

    // ===============================================================
    // Point 3.2 — Mandatory Protective Filtering
    // ===============================================================

    @Test
    void testAcceptFileRejectsNullAndNullHash() {
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        try (KadUdpService udp = new KadUdpService(0);
             KadSearchBridge bridge = new KadSearchBridge(table, udp,
                     ChildAbuseFilter.defaults(), new FakeFileDetector(), 3, 2000)) {

            // null file
            assertFalse(bridge.acceptFile(null));

            // file with null hash
            FileMetadata noHash = new FileMetadata(null, "somefile.txt", 1000, "Text");
            assertFalse(bridge.acceptFile(noHash));
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    void testAcceptFileAllowsCleanFile() {
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        try (KadUdpService udp = new KadUdpService(0);
             KadSearchBridge bridge = new KadSearchBridge(table, udp,
                     ChildAbuseFilter.defaults(), new FakeFileDetector(), 3, 2000)) {

            FileMetadata clean = new FileMetadata("aabbccddeeff00112233445566778899",
                    "Ubuntu-24.04-desktop-amd64.iso", 4_700_000_000L, "Archive");
            assertTrue(bridge.acceptFile(clean));
            assertEquals(0, bridge.getIllicitFilesBlocked());
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    void testAcceptFileRejectsBannedHash() {
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        try (KadUdpService udp = new KadUdpService(0);
             KadSearchBridge bridge = new KadSearchBridge(table, udp,
                     ChildAbuseFilter.defaults(), new FakeFileDetector(), 3, 2000)) {

            String hash = "aabbccddeeff00112233445566778899";
            bridge.banHash(hash);
            assertTrue(bridge.isHashBanned(hash));

            FileMetadata file = new FileMetadata(hash, "legitimate-file.avi", 700_000_000L, "Video");
            assertFalse(bridge.acceptFile(file));
            assertEquals(1, bridge.getIllicitFilesBlocked());
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    void testAcceptFileRejectsBannedHashCaseInsensitive() {
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        try (KadUdpService udp = new KadUdpService(0);
             KadSearchBridge bridge = new KadSearchBridge(table, udp,
                     ChildAbuseFilter.defaults(), new FakeFileDetector(), 3, 2000)) {

            // Ban with lowercase
            bridge.banHash("aabbccddeeff00112233445566778899");

            // File arrives with uppercase hash
            FileMetadata file = new FileMetadata("AABBCCDDEEFF00112233445566778899",
                    "some-file.avi", 700_000_000L, "Video");
            assertFalse(bridge.acceptFile(file));

            // Verify isHashBanned is case-insensitive too
            assertTrue(bridge.isHashBanned("AABBCCDDEEFF00112233445566778899"));
            assertTrue(bridge.isHashBanned("aabbccddeeff00112233445566778899"));
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    void testAcceptFileRejectsChildAbuseMaterial() {
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        try (KadUdpService udp = new KadUdpService(0);
             KadSearchBridge bridge = new KadSearchBridge(table, udp,
                     ChildAbuseFilter.defaults(), new FakeFileDetector(), 3, 2000)) {

            // File with a banned keyword from child-abuse-keywords.properties
            String illicitHash = "11223344556677889900aabbccddeeff";
            FileMetadata illicit = new FileMetadata(illicitHash,
                    "pthc collection.avi", 500_000L, "Video");

            assertFalse(bridge.acceptFile(illicit));
            assertEquals(1, bridge.getIllicitFilesBlocked());

            // Verify the hash was automatically added to the in-memory ban set
            assertTrue(bridge.isHashBanned(illicitHash));
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    void testAcceptFileRejectsChildAbuseMaterialAndNotifiesListeners() {
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        try (KadUdpService udp = new KadUdpService(0);
             KadSearchBridge bridge = new KadSearchBridge(table, udp,
                     ChildAbuseFilter.defaults(), new FakeFileDetector(), 3, 2000)) {

            // Register a listener
            AtomicReference<FileMetadata> capturedFile = new AtomicReference<>();
            AtomicReference<String> capturedReason = new AtomicReference<>();
            bridge.addIllicitFileListener((file, reason) -> {
                capturedFile.set(file);
                capturedReason.set(reason);
            });

            String illicitHash = "11223344556677889900aabbccddeeff";
            FileMetadata illicit = new FileMetadata(illicitHash,
                    "pthc collection.avi", 500_000L, "Video");

            assertFalse(bridge.acceptFile(illicit));

            // Listener should have been notified
            assertNotNull(capturedFile.get());
            assertEquals(illicitHash, capturedFile.get().hash());
            assertEquals(ChildAbuseFilter.BAN_REASON, capturedReason.get());
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    void testAcceptFileRejectsFakeFile() {
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        try (KadUdpService udp = new KadUdpService(0);
             KadSearchBridge bridge = new KadSearchBridge(table, udp,
                     ChildAbuseFilter.defaults(), new FakeFileDetector(), 3, 2000)) {

            // File with double extension — detected as fake
            String fakeHash = "22334455667788990011aabbccddeeff";
            FileMetadata fakeFile = new FileMetadata(fakeHash,
                    "Movie.mp4.exe", 1_000_000L, "Video");

            assertFalse(bridge.acceptFile(fakeFile));
            assertEquals(1, bridge.getIllicitFilesBlocked());

            // Fake files are NOT auto-banned (only rejected by heuristic)
            assertFalse(bridge.isHashBanned(fakeHash));
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    void testAcceptFileRejectsFakeFileSuspiciousKeyword() {
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        try (KadUdpService udp = new KadUdpService(0);
             KadSearchBridge bridge = new KadSearchBridge(table, udp,
                     ChildAbuseFilter.defaults(), new FakeFileDetector(), 3, 2000)) {

            // File with suspicious keyword "keygen"
            FileMetadata keygen = new FileMetadata("33445566778899001122aabbccddeeff",
                    "Photoshop keygen.exe", 2_000_000L, "Program");
            assertFalse(bridge.acceptFile(keygen));
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    void testFilteringDuringLiveSearch() throws Exception {
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        try (KadUdpService serverUdp = new KadUdpService(0);
             KadUdpService peerUdp = new KadUdpService(0);
             KadSearchBridge bridge = new KadSearchBridge(table, serverUdp,
                     ChildAbuseFilter.defaults(), new FakeFileDetector(), 3, 3000)) {

            serverUdp.start();
            peerUdp.start();
            bridge.start();

            // Add peer contact
            KadId peerId = table.generateRandomIdForBucket(1);
            KadContact peerContact = new KadContact(peerId, InetAddress.getLoopbackAddress(), peerUdp.getPort());
            table.addOrUpdate(peerContact);

            String targetKeyword = "documentary";
            KadId keywordHash = KadId.fromKeyword(targetKeyword);

            // Prepare: peer replies with 3 files (1 clean, 1 child abuse, 1 fake)
            String cleanHash = "aabbccddeeff00112233445566778899";
            String illicitHash = "11223344556677889900aabbccddeeff";
            String fakeHash = "22334455667788990011aabbccddeeff";

            List<FileMetadata> peerFiles = List.of(
                    new FileMetadata(cleanHash, "nature-documentary.avi", 700_000_000L, "Video",
                            List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 5))),
                    new FileMetadata(illicitHash, "pthc collection.avi", 500_000L, "Video",
                            List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 3))),
                    new FileMetadata(fakeHash, "Documentary.mp4.exe", 1_000L, "Video",
                            List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 2)))
            );

            CountDownLatch reqLatch = new CountDownLatch(1);
            peerUdp.registerListener(KadOpCode.SEARCH_KEY_REQ, (packet, addr, port) -> {
                reqLatch.countDown();
                KadPacket resPkt = KadPacket.createSearchRes(keywordHash, peerFiles);
                try {
                    peerUdp.send(resPkt, addr, port);
                } catch (Exception e) {
                    fail(e);
                }
            });

            // Track illicit notifications
            List<String> bannedNotifications = new ArrayList<>();
            bridge.addIllicitFileListener((file, reason) ->
                    bannedNotifications.add(file.hash() + ":" + reason));

            CompletableFuture<List<FileMetadata>> future = bridge.forkSearch(targetKeyword);
            assertTrue(reqLatch.await(3, TimeUnit.SECONDS));

            List<FileMetadata> results = future.get(5, TimeUnit.SECONDS);

            // Only the clean file should be in results
            assertEquals(List.of(cleanHash + " nature-documentary.avi"),
                    results.stream().map(f -> f.hash() + " " + f.name()).toList(),
                    "Only clean file should pass through filters");

            // The illicit hash should be auto-banned
            assertTrue(bridge.isHashBanned(illicitHash));
            // The fake hash should NOT be auto-banned (heuristic-only rejection)
            assertFalse(bridge.isHashBanned(fakeHash));

            // IllicitFileListener should have been called for the child abuse file
            assertEquals(1, bannedNotifications.size());
            assertTrue(bannedNotifications.get(0).contains(illicitHash));
            assertTrue(bannedNotifications.get(0).contains(ChildAbuseFilter.BAN_REASON));

            // At least 2 files blocked (child abuse + fake)
            assertTrue(bridge.getIllicitFilesBlocked() >= 2);
        }
    }

    @Test
    void testRemoveIllicitFileListener() {
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        try (KadUdpService udp = new KadUdpService(0);
             KadSearchBridge bridge = new KadSearchBridge(table, udp,
                     ChildAbuseFilter.defaults(), new FakeFileDetector(), 3, 2000)) {

            AtomicReference<FileMetadata> captured = new AtomicReference<>();
            KadSearchBridge.IllicitFileListener listener = (file, reason) -> captured.set(file);

            bridge.addIllicitFileListener(listener);
            bridge.removeIllicitFileListener(listener);

            FileMetadata illicit = new FileMetadata("11223344556677889900aabbccddeeff",
                    "pthc collection.avi", 500_000L, "Video");
            bridge.acceptFile(illicit);

            // Listener was removed, so captured should still be null
            assertNull(captured.get());
        } catch (Exception e) {
            fail(e);
        }
    }

    // ===============================================================
    // Point 4.1 — Conditional H2 Ingestion & In-Memory Indexing
    // ===============================================================

    @Test
    void testFileMetadataSourceCountEvaluation() {
        // 1. Empty sources and no tags
        FileMetadata f1 = new FileMetadata("11111111111111111111111111111111", "test1.avi", 1000L, "Video");
        assertEquals(0, f1.sourceCount());

        // 2. Tag FT_SOURCES with 0
        FileMetadata f2 = new FileMetadata("22222222222222222222222222222222", "test2.avi", 1000L, "Video",
                List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 0)));
        assertEquals(0, f2.sourceCount());

        // 3. Tag FT_SOURCES with 7
        FileMetadata f3 = new FileMetadata("33333333333333333333333333333333", "test3.avi", 1000L, "Video",
                List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 7)));
        assertEquals(7, f3.sourceCount());
    }

    @Test
    void testConditionalIngestionSourcesZeroDiscarded() throws Exception {
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        String dbPath = "mem:kad_test_db_zero_" + System.currentTimeMillis();
        try (org.jemule.core.DatabaseManager db = new org.jemule.core.DatabaseManager(dbPath);
             KadUdpService serverUdp = new KadUdpService(0);
             KadUdpService peerUdp = new KadUdpService(0)) {

            org.jemule.core.FileIndex fileIndex = new org.jemule.core.FileIndex(db);

            try (KadSearchBridge bridge = new KadSearchBridge(
                    table, serverUdp, ChildAbuseFilter.defaults(), new FakeFileDetector(),
                    fileIndex, db, 3, 2000, true, 1)) {

                serverUdp.start();
                peerUdp.start();
                bridge.start();

                KadId peerId = table.generateRandomIdForBucket(1);
                KadContact peerContact = new KadContact(peerId, InetAddress.getLoopbackAddress(), peerUdp.getPort());
                table.addOrUpdate(peerContact);

                String targetKeyword = "ghostfile";
                KadId keywordHash = KadId.fromKeyword(targetKeyword);
                String ghostHash = "00000000000000000000000000000001";

                CountDownLatch reqLatch = new CountDownLatch(1);
                peerUdp.registerListener(KadOpCode.SEARCH_KEY_REQ, (packet, addr, port) -> {
                    reqLatch.countDown();
                    // Send file with sources = 0
                    FileMetadata ghostMeta = new FileMetadata(ghostHash, "ghostfile.iso", 1000L, "Archive",
                            List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 0)));
                    KadPacket resPkt = KadPacket.createSearchRes(keywordHash, List.of(ghostMeta));
                    try {
                        peerUdp.send(resPkt, addr, port);
                    } catch (Exception e) {
                        fail(e);
                    }
                });

                CompletableFuture<List<FileMetadata>> future = bridge.forkSearch(targetKeyword);
                assertTrue(reqLatch.await(3, TimeUnit.SECONDS), "Peer should receive search request");
                List<FileMetadata> results = future.get(5, TimeUnit.SECONDS);

                // File with 0 sources must be discarded from search results
                assertTrue(results.isEmpty(), "Ghost file with 0 sources should not be in search results");

                // File must NOT be indexed in FileIndex
                assertFalse(fileIndex.contains(ghostHash), "Ghost file with 0 sources should not be in FileIndex");

                // File must NOT be persisted in H2 DB
                List<FileMetadata> dbFiles = db.loadFiles();
                assertTrue(dbFiles.stream().noneMatch(f -> f.hash().equalsIgnoreCase(ghostHash)),
                        "Ghost file with 0 sources should not be saved in DB");

                assertEquals(0, bridge.getFilesIngested());
            }
        }
    }

    @Test
    void testConditionalIngestionSourcesOneOrMoreIngestedAndPersisted() throws Exception {
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        String dbPath = "mem:kad_test_db_persist_" + System.currentTimeMillis();
        try (org.jemule.core.DatabaseManager db = new org.jemule.core.DatabaseManager(dbPath);
             KadUdpService serverUdp = new KadUdpService(0);
             KadUdpService peerUdp = new KadUdpService(0)) {

            org.jemule.core.FileIndex fileIndex = new org.jemule.core.FileIndex(db);

            try (KadSearchBridge bridge = new KadSearchBridge(
                    table, serverUdp, ChildAbuseFilter.defaults(), new FakeFileDetector(),
                    fileIndex, db, 3, 2000, true, 1)) {

                serverUdp.start();
                peerUdp.start();
                bridge.start();

                KadId peerId = table.generateRandomIdForBucket(1);
                KadContact peerContact = new KadContact(peerId, InetAddress.getLoopbackAddress(), peerUdp.getPort());
                table.addOrUpdate(peerContact);

                String targetKeyword = "fedora";
                KadId keywordHash = KadId.fromKeyword(targetKeyword);
                String cleanHash = "fed01234567890abcdefed0123456789";

                CountDownLatch reqLatch = new CountDownLatch(1);
                peerUdp.registerListener(KadOpCode.SEARCH_KEY_REQ, (packet, addr, port) -> {
                    reqLatch.countDown();
                    // Send file with sources = 4
                    FileMetadata fedoraMeta = new FileMetadata(cleanHash, "fedora-workstation.iso", 2_500_000_000L, "Archive",
                            List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 4)));
                    KadPacket resPkt = KadPacket.createSearchRes(keywordHash, List.of(fedoraMeta));
                    try {
                        peerUdp.send(resPkt, addr, port);
                    } catch (Exception e) {
                        fail(e);
                    }
                });

                CompletableFuture<List<FileMetadata>> future = bridge.forkSearch(targetKeyword);
                assertTrue(reqLatch.await(3, TimeUnit.SECONDS), "Peer should receive search request");
                List<FileMetadata> results = future.get(5, TimeUnit.SECONDS);

                // File with 4 sources must be present in search results
                assertEquals(1, results.size());
                assertEquals(cleanHash, results.get(0).hash());
                assertEquals("fedora-workstation.iso", results.get(0).name());
                assertEquals(4, results.get(0).sourceCount());

                // File must be ingested into FileIndex memory index
                assertTrue(fileIndex.contains(cleanHash), "File with sources >= 1 must be present in FileIndex");

                // File must be searchable from FileIndex
                List<FileMetadata> memorySearch = fileIndex.search("fedora", 10);
                assertEquals(1, memorySearch.size());
                assertEquals(cleanHash, memorySearch.get(0).hash());

                // File must be persisted in H2 database
                List<FileMetadata> dbFiles = db.loadFiles();
                assertTrue(dbFiles.stream().anyMatch(f -> f.hash().equalsIgnoreCase(cleanHash)),
                        "File with sources >= 1 must be persisted in H2 database");

                assertEquals(1, bridge.getFilesIngested());
            }
        }
    }

    @Test
    void testConditionalIngestionDisabledConfig() throws Exception {
        KadId localId = KadId.random();
        KadRoutingTable table = new KadRoutingTable(localId);

        String dbPath = "mem:kad_test_db_noingest_" + System.currentTimeMillis();
        try (org.jemule.core.DatabaseManager db = new org.jemule.core.DatabaseManager(dbPath);
             KadUdpService serverUdp = new KadUdpService(0);
             KadUdpService peerUdp = new KadUdpService(0)) {

            org.jemule.core.FileIndex fileIndex = new org.jemule.core.FileIndex(db);

            // autoIngestToH2 = false
            try (KadSearchBridge bridge = new KadSearchBridge(
                    table, serverUdp, ChildAbuseFilter.defaults(), new FakeFileDetector(),
                    fileIndex, db, 3, 2000, false, 1)) {

                serverUdp.start();
                peerUdp.start();
                bridge.start();

                KadId peerId = table.generateRandomIdForBucket(1);
                KadContact peerContact = new KadContact(peerId, InetAddress.getLoopbackAddress(), peerUdp.getPort());
                table.addOrUpdate(peerContact);

                String targetKeyword = "archlinux";
                KadId keywordHash = KadId.fromKeyword(targetKeyword);
                String cleanHash = "a1b2c3d4e5f60718293a4b5c6d7e8f90";

                CountDownLatch reqLatch = new CountDownLatch(1);
                peerUdp.registerListener(KadOpCode.SEARCH_KEY_REQ, (packet, addr, port) -> {
                    reqLatch.countDown();
                    FileMetadata archMeta = new FileMetadata(cleanHash, "archlinux.iso", 1_000_000_000L, "Archive",
                            List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 2)));
                    KadPacket resPkt = KadPacket.createSearchRes(keywordHash, List.of(archMeta));
                    try {
                        peerUdp.send(resPkt, addr, port);
                    } catch (Exception e) {
                        fail(e);
                    }
                });

                CompletableFuture<List<FileMetadata>> future = bridge.forkSearch(targetKeyword);
                assertTrue(reqLatch.await(3, TimeUnit.SECONDS), "Peer should receive search request");
                List<FileMetadata> results = future.get(5, TimeUnit.SECONDS);

                // Result is returned to client search session
                assertEquals(1, results.size());
                assertEquals(cleanHash, results.get(0).hash());

                // But NOT ingested to FileIndex or DB because autoIngestToH2 is disabled
                assertFalse(fileIndex.contains(cleanHash));
                assertTrue(db.loadFiles().isEmpty());
                assertEquals(0, bridge.getFilesIngested());
            }
        }
    }
}
