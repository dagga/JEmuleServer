package org.jemule.network;

import org.jemule.kad.KadPacket;
import org.jemule.network.handler.HandlerUtils;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ServerSourceSearcherTest {
    private static final byte[] FILE_A = HandlerUtils.hashToBytes("0123456789ABCDEF0123456789ABCDEF");
    private static final byte[] FILE_B = HandlerUtils.hashToBytes("FEDCBA9876543210FEDCBA9876543210");

    private final List<Runnable> batchTimers = new ArrayList<>();
    private final List<Runnable> answerTimers = new ArrayList<>();

    private static DatagramSocket bind() throws Exception {
        // A server's UDP port is its TCP port + 4
        for (int attempt = 0; attempt < 20; attempt++) {
            DatagramSocket socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            if (socket.getLocalPort() >= 5) return socket;
            socket.close();
        }
        throw new IllegalStateException("no UDP port");
    }

    private static ServerGossip.VerifiedPeer peer(DatagramSocket socket) {
        return new ServerGossip.VerifiedPeer(new ServerGossip.Endpoint(InetAddress.getLoopbackAddress(),
                socket.getLocalPort() - 4), "peer", 0, 0);
    }

    /** OP_GLOBFOUNDSOURCES of one file: <E3 9B><hash 16><count 1>(<ID 4><port 2>)*. */
    private static byte[] found(byte[] hash, long[]... sources) {
        ByteBuffer b = ByteBuffer.allocate(19 + sources.length * 6).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 0xE3).put((byte) 0x9B).put(hash).put((byte) sources.length);
        for (long[] s : sources) b.putInt((int) s[0]).putShort((short) s[1]);
        return b.array();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] all = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }

    @Test
    void theFilesOfABatchAreAskedInOneDatagramAndTheHighIdSourcesKept() throws Exception {
        try (DatagramSocket local = bind(); DatagramSocket server = bind(); DatagramSocket stranger = bind()) {
            server.setSoTimeout(2000);
            ServerSourceSearcher searcher = new ServerSourceSearcher(() -> List.of(peer(server)), () -> local,
                    batchTimers::add, answerTimers::add);

            CompletableFuture<List<KadPacket.KadSource>> a = searcher.search(FILE_A, 700_000_000L);
            CompletableFuture<List<KadPacket.KadSource>> b = searcher.search(FILE_B, 5_000_000_000L);
            assertSame(a, searcher.search(FILE_A, 700_000_000L), "the same file shares the request");
            assertEquals(1, batchTimers.size(), "one timer per batch");
            batchTimers.get(0).run();

            DatagramPacket request = new DatagramPacket(new byte[512], 512);
            server.receive(request);
            ByteBuffer r = ByteBuffer.wrap(request.getData(), 0, request.getLength()).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals((byte) 0xE3, r.get());
            assertEquals((byte) 0x94, r.get());
            byte[] hash = new byte[16];
            r.get(hash);
            assertArrayEquals(FILE_A, hash);
            assertEquals(700_000_000, r.getInt());
            r.get(hash);
            assertArrayEquals(FILE_B, hash);
            assertEquals(0, r.getInt(), "a large file: 0, then its 64-bit size");
            assertEquals(5_000_000_000L, r.getLong());
            assertFalse(r.hasRemaining());

            // Two blocks in one datagram: 91.216.110.42 (HighID), a LowID, a duplicate
            long highId = 42L << 24 | 110L << 16 | 216L << 8 | 91L;
            byte[] answer = concat(found(FILE_A, new long[]{highId, 4662}, new long[]{1234, 4662}),
                    found(FILE_B, new long[]{highId, 4663}, new long[]{highId, 4663}));
            InetAddress loopback = InetAddress.getLoopbackAddress();
            assertTrue(searcher.handle(loopback, stranger.getLocalPort(), answer), "an OP_GLOBFOUNDSOURCES");
            assertTrue(searcher.handle(loopback, server.getLocalPort(), answer));

            answerTimers.forEach(Runnable::run);
            List<KadPacket.KadSource> sourcesA = a.join();
            assertEquals(1, sourcesA.size(), "the LowID is dropped, the stranger ignored");
            KadPacket.KadSource s = sourcesA.get(0);
            assertTrue(s.isDirect());
            assertEquals(0x5BD86E2A, s.ip(), "the IP in host order, as Kad gives it");
            assertEquals(4662, s.tcpPort());
            assertEquals(1, b.join().size(), "duplicates are kept once");
            assertEquals(2L, searcher.stats().get("highIdSources"));
            assertEquals(2L, searcher.stats().get("filesAsked"));
        }
    }

    @Test
    void withoutVerifiedServerNothingIsAsked() {
        ServerSourceSearcher searcher = new ServerSourceSearcher(List::of, () -> null, batchTimers::add,
                answerTimers::add);
        assertTrue(searcher.search(FILE_A, 1000).join().isEmpty());
        assertTrue(batchTimers.isEmpty());
    }

    @Test
    void aFullBatchIsSentAtOnce() throws Exception {
        try (DatagramSocket local = bind(); DatagramSocket server = bind()) {
            server.setSoTimeout(2000);
            ServerSourceSearcher searcher = new ServerSourceSearcher(() -> List.of(peer(server)), () -> local,
                    batchTimers::add, answerTimers::add);
            for (int i = 0; i < ServerSourceSearcher.MAX_FILES_PER_REQUEST; i++) {
                searcher.search(HandlerUtils.hashToBytes(String.format("%032X", i)), 1000);
            }
            DatagramPacket request = new DatagramPacket(new byte[2048], 2048);
            server.receive(request);
            assertEquals(2 + ServerSourceSearcher.MAX_FILES_PER_REQUEST * 20, request.getLength());
            assertEquals(1L, searcher.stats().get("requestsSent"));
        }
    }
}
