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

package org.jemule.network;

import org.jemule.config.KadConfig;
import org.jemule.config.ServerConfig;
import org.jemule.core.ClientRegistry;
import org.jemule.core.ClientState;
import org.jemule.core.DatabaseManager;
import org.jemule.core.FileIndex;
import org.jemule.core.FileMetadata;
import org.jemule.kad.KadBootstrapManager;
import org.jemule.kad.KadContact;
import org.jemule.kad.KadId;
import org.jemule.kad.KadNode;
import org.jemule.kad.KadOpCode;
import org.jemule.kad.KadPacket;
import org.jemule.kad.KadUdpService;
import org.jemule.network.handler.SearchHandler;
import org.jemule.protocol.Tag;
import org.jemule.security.ChildAbuseFilter;
import org.jemule.security.FakeFileDetector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.jemule.network.EmuleTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end acceptance of SFD 1.2 (section 8.1, TC-01 to TC-08, and the acceptance criteria of section 8.2): a
 * real {@link KadNode} backed by H2 and the {@link FileIndex}, eMule clients going through {@link SearchHandler},
 * and a remote Kad peer answering on the loopback.
 */
class KadAcceptanceTest {

    /** Kad search timeout of the node under test (kadSearchTimeoutSeconds). */
    private static final int SEARCH_TIMEOUT_SECONDS = 1;

    DatabaseManager db;
    FileIndex index;
    ClientRegistry registry;
    KadNode node;
    KadUdpService peer;
    /** Files the remote peer returns, per keyword hash. */
    final Map<KadId, List<FileMetadata>> peerCatalog = new ConcurrentHashMap<>();

    @BeforeEach
    void setup() throws Exception {
        db = new DatabaseManager("mem:kad_acceptance_" + System.nanoTime());
        index = new FileIndex(db);
        registry = new ClientRegistry();
        node = new KadNode(new KadConfig(true, freeUdpPort(), true, 500, true, SEARCH_TIMEOUT_SECONDS, true, 1),
                4661, db, index, ChildAbuseFilter.defaults(), new FakeFileDetector());
        node.start();

        peer = new KadUdpService(0);
        peer.start();
        peer.registerListener(KadOpCode.SEARCH_KEY_REQ, (packet, addr, port) -> {
            KadPacket.SearchKeyReq req = KadPacket.parseSearchKeyReq(packet.data());
            List<FileMetadata> files = req != null ? peerCatalog.get(req.keywordHash()) : null;
            if (files != null) {
                try {
                    peer.send(KadPacket.createSearchRes(req.keywordHash(), files), addr, port);
                } catch (Exception e) {
                    fail(e);
                }
            }
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        peer.close();
        node.close();
        db.close();
    }

    private static int freeUdpPort() throws Exception {
        try (DatagramSocket s = new DatagramSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static FileMetadata kadFile(String hash, String name, int sources) {
        return new FileMetadata(hash, name, 700_000_000L, "Video",
                List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, sources)));
    }

    private static void await(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    /** Adds the remote peer to the routing table of the node, so that searches are forked to it. */
    private void peerJoinsTheRoutingTable() {
        node.getRoutingTable().addOrUpdate(new KadContact(node.getRoutingTable().generateRandomIdForBucket(1),
                InetAddress.getLoopbackAddress(), peer.getPort()));
    }

    /** An eMule client logged in on a server whose Kad node forks the searches. */
    private FakeContext client(String ip) throws Exception {
        FakeContext ctx = loggedInContext(ip, 50000, ServerConfig.DEFAULT, registry, index);
        ctx.setKadSearchBridge(node.getSearchBridge());
        return ctx;
    }

    private static SearchAnswer search(FakeContext ctx, String keyword) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new SearchHandler().handleSearch(ctx, new SearchTree().keyword(keyword).toBytes(), out);
        return answer(out);
    }

    private static SearchAnswer queryMore(FakeContext ctx) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new SearchHandler().handleQueryMoreResult(ctx, out);
        return answer(out);
    }

    private static SearchAnswer answer(ByteArrayOutputStream out) throws Exception {
        Packet p = Packet.read(new ByteArrayInputStream(out.toByteArray()), 1 << 20);
        assertEquals(org.jemule.protocol.OpCode.SEARCH_RESULT.value, p.opcode());
        return readSearchAnswer(p.data());
    }

    private static List<String> hashes(SearchAnswer a) {
        return a.results().stream().map(r -> r.hash().toLowerCase()).sorted().toList();
    }

    private List<String> storedHashes() {
        return db.loadFiles().stream().map(f -> f.hash().toLowerCase()).sorted().toList();
    }

    @Test
    void tc01_highIdClientLoginBootstrapsTheRoutingTable() throws Exception {
        ClientState client = new ClientState(InetAddress.getByName("203.0.113.42"), 50000,
                ClientState.ipToLong(InetAddress.getByName("203.0.113.42")), System.currentTimeMillis(),
                new AtomicLong(System.currentTimeMillis()));
        client.setListenPort(4662);

        node.onClientConnected(client);
        assertEquals(1, node.getBootstrapManager().getBootstrapAttempts(), "BOOTSTRAP_REQ sent to listenPort + 10");
        assertEquals(KadBootstrapManager.Status.BOOTSTRAPPING, node.status());

        // The client's Kad node answers with its own contacts
        List<KadContact> contacts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            contacts.add(new KadContact(KadId.random(), InetAddress.getByName("198.51.100." + (i + 1)), 4672, 4662, (byte) 8));
        }
        peer.send(KadPacket.createBootstrapRes(contacts), InetAddress.getLoopbackAddress(), node.getUdpPort());

        await(() -> node.getRoutingTable().size() == 6, "returned contacts are inserted into the routing table");
        // The status is updated once the whole answer is processed
        await(() -> node.status() == KadBootstrapManager.Status.CONNECTED, "CONNECTED from 5 active contacts");
    }

    @Test
    void tc02_pureLocalSearchIsAnsweredFromH2() throws Exception {
        index.addFile(kadFile("11111111111111111111111111111111", "local holiday.avi", 2));
        assertEquals(1, storedHashes().size());
        peerJoinsTheRoutingTable(); // the peer knows nothing about "holiday"

        SearchAnswer a = search(client("10.1.1.1"), "holiday");
        assertEquals(List.of("11111111111111111111111111111111"), hashes(a));
        assertEquals(0, a.more());
    }

    @Test
    void tc03_tc04_forkedSearchDeliversKadFilesAndIngestsOnlyThoseWithSources() throws Exception {
        String withSources = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String ghost = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        peerCatalog.put(KadId.fromKeyword("documentary"), List.of(
                kadFile(withSources, "documentary ocean.mkv", 3),
                kadFile(ghost, "documentary ghost.mkv", 0)));
        peerJoinsTheRoutingTable();
        assertTrue(index.searchComplex(org.jemule.core.SearchQuery.parse(java.nio.ByteBuffer.wrap(
                new SearchTree().keyword("documentary").toBytes())), 100).isEmpty(), "nothing is known locally");

        SearchAnswer a = search(client("10.1.1.1"), "documentary");

        // TC-03: the Kad file is delivered; TC-04: the file without sources is neither delivered nor stored
        assertEquals(List.of(withSources), hashes(a));
        assertEquals(List.of(withSources), storedHashes(), "only the file with >= 1 source is persisted in 'files'");
        FileMetadata stored = db.loadFiles().getFirst();
        assertTrue(stored.tags().stream().anyMatch(t -> Tag.NAME_SOURCES.equals(t.name())),
                "its FT_SOURCES tag is persisted in 'file_tags'");
        assertTrue(index.contains(withSources));
        assertFalse(index.contains(ghost));
    }

    @Test
    void tc05_illicitKadFileIsNeverSentAndItsHashIsBannedForGood() throws Exception {
        String clean = "cccccccccccccccccccccccccccccccc";
        String illicit = "dddddddddddddddddddddddddddddddd";
        peerCatalog.put(KadId.fromKeyword("collection"), List.of(
                kadFile(clean, "collection stamps.avi", 2),
                kadFile(illicit, "pthc collection.avi", 9)));
        peerJoinsTheRoutingTable();

        SearchAnswer a = search(client("10.1.1.1"), "collection");
        assertEquals(List.of(clean), hashes(a), "the illicit file never reaches the client");
        await(() -> db.loadBannedHashes().contains(illicit.toUpperCase()), "the illicit hash is recorded in 'banned_hashes'");
        assertFalse(storedHashes().contains(illicit), "the illicit file is never stored");
        assertFalse(index.contains(illicit), "the illicit file is never indexed");
        assertEquals(1, node.getSearchBridge().getIllicitFilesBlocked());

        // The banned hash stays rejected, even announced later under an innocent name
        peerCatalog.put(KadId.fromKeyword("garden"), List.of(kadFile(illicit, "garden party.avi", 5)));
        SearchAnswer later = search(client("10.2.2.2"), "garden");
        assertTrue(later.results().isEmpty(), "a banned hash is rejected whatever its name");
        assertFalse(index.contains(illicit));
    }

    @Test
    void tc06_coldSearchOverFiftyResultsIsPaginatedWithQueryMore() throws Exception {
        List<FileMetadata> files = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            files.add(kadFile(String.format("%032x", i + 1), "lecture part " + i + ".mkv", 2));
        }
        peerCatalog.put(KadId.fromKeyword("lecture"), files);
        peerJoinsTheRoutingTable();

        FakeContext ctx = client("10.1.1.1");
        SearchAnswer first = search(ctx, "lecture");
        assertEquals(50, first.results().size());
        assertEquals(1, first.more(), "more=1 when results remain");

        SearchAnswer second = queryMore(ctx);
        assertEquals(10, second.results().size());
        assertEquals(0, second.more());
        assertEquals(60, storedHashes().size(), "the whole cold discovery is ingested");
    }

    /** Runs a search and returns its answer time in milliseconds, checking the returned hashes. */
    private long timedSearch(FakeContext ctx, String keyword, List<String> expectedHashes) throws Exception {
        long start = System.nanoTime();
        SearchAnswer a = search(ctx, keyword);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertEquals(expectedHashes, hashes(a));
        assertEquals(0, a.more());
        return elapsedMs;
    }

    @Test
    void tc07_closedKadSocketDoesNotDelayAnySearch() throws Exception {
        index.addFile(kadFile("11111111111111111111111111111111", "local concert.avi", 2));
        peerJoinsTheRoutingTable();
        node.getUdpService().close(); // the Kad socket is gone, the bridge is still attached
        FakeContext ctx = client("10.1.1.1");

        long local = timedSearch(ctx, "concert", List.of("11111111111111111111111111111111"));
        assertTrue(local < 100, "local results at once: " + local + " ms");
        // Cold search: no request can be sent, so there is nothing to wait for
        long cold = timedSearch(ctx, "opera", List.of());
        assertTrue(cold < SEARCH_TIMEOUT_SECONDS * 1000L, "no wait for the Kad timeout: " + cold + " ms");
    }

    @Test
    void tc07_unreachableDhtBoundsColdSearchesByTheKadTimeout() throws Exception {
        index.addFile(kadFile("11111111111111111111111111111111", "local concert.avi", 2));
        // A contact that never answers (nobody listens on its port)
        node.getRoutingTable().addOrUpdate(new KadContact(node.getRoutingTable().generateRandomIdForBucket(1),
                InetAddress.getLoopbackAddress(), freeUdpPort()));
        FakeContext ctx = client("10.1.1.1");

        long local = timedSearch(ctx, "concert", List.of("11111111111111111111111111111111"));
        assertTrue(local < 100, "local results at once: " + local + " ms");
        long cold = timedSearch(ctx, "opera", List.of());
        assertTrue(cold < SEARCH_TIMEOUT_SECONDS * 1000L + 1500, "bounded by kadSearchTimeoutSeconds: " + cold + " ms");
    }

    @Test
    void tc08_warmSearchIsServedAtOnceFromH2WithoutQueryMore() throws Exception {
        String desktop = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
        String server = "ffffffffffffffffffffffffffffffff";
        peerCatalog.put(KadId.fromKeyword("ubuntu"), List.of(
                kadFile(desktop, "ubuntu desktop.iso", 5),
                kadFile(server, "ubuntu server.iso", 3)));
        peerJoinsTheRoutingTable();

        // Cold search: discovered on Kad and ingested
        assertEquals(List.of(desktop, server), hashes(search(client("10.1.1.1"), "ubuntu")));
        assertEquals(List.of(desktop, server), storedHashes());

        // Warm search by another client, the Kad peer still being reachable
        long start = System.nanoTime();
        SearchAnswer warm = search(client("10.2.2.2"), "ubuntu");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(List.of(desktop, server), hashes(warm), "the whole catalog is in the first batch");
        assertEquals(0, warm.more(), "no OP_QUERY_MORE_RESULT needed");
        assertTrue(elapsedMs < 100, "served from H2 without waiting for Kad: " + elapsedMs + " ms");
    }

    @Test
    void searchesAnswerUnderTenMillisecondsWhenResultsAreKnownLocally() throws Exception {
        String[] words = {"alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel"};
        for (int i = 0; i < 2000; i++) {
            index.addFile(kadFile(String.format("%032x", i + 1),
                    words[i % words.length] + " " + words[(i / 8) % words.length] + " " + i + ".avi", 1));
        }
        peerJoinsTheRoutingTable(); // a live Kad contact: every search is forked
        FakeContext ctx = client("10.1.1.1");
        for (int i = 0; i < 50; i++) {
            search(ctx, words[i % words.length]); // JIT warm-up
        }

        // Half of the searches fill a batch, the other half find a single file (warm search)
        long[] durations = new long[400];
        for (int i = 0; i < durations.length; i++) {
            boolean fullBatch = i % 2 == 0;
            long start = System.nanoTime();
            SearchAnswer a = search(ctx, fullBatch ? words[i % words.length] : String.valueOf(1000 + i));
            durations[i] = System.nanoTime() - start;
            assertEquals(fullBatch ? 50 : 1, a.results().size());
        }
        Arrays.sort(durations);
        long p95Ms = durations[(int) (durations.length * 0.95) - 1] / 1_000_000;
        assertTrue(p95Ms < 10, "95% of the searches must be answered under 10 ms, p95 = " + p95Ms + " ms");
    }
}
