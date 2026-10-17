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
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class KadSearchBridgeTest {

    private InetAddress testIp(String ip) {
        try {
            return InetAddress.getByName(ip);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

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
}
