package org.jemule.network;

import org.jemule.kad.KadPacket;
import org.jemule.network.handler.FoundSource;
import org.jemule.network.handler.HandlerUtils;
import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IPv6 source records (ID 0xFFFFFFFF followed by 16 bytes, doc/ipv6.md) read from another server: this server never
 * asks for them, and one coming anyway must not pass as the HighID 255.255.255.255 nor shift the records after it.
 */
class Ipv6SourceIsolationTest {
    private static final byte[] FILE = HandlerUtils.hashToBytes("0123456789ABCDEF0123456789ABCDEF");
    private static final long HIGH_ID = 42L << 24 | 110L << 16 | 216L << 8 | 91L;
    private static final byte[] ADDRESS = java.util.HexFormat.of().parseHex("20010db8000000000000000000000001");

    /** {@code <hash 16><count 1>}, a HighID, an IPv6 record, another HighID. */
    private static byte[] withIpv6Record() {
        ByteBuffer b = ByteBuffer.allocate(16 + 1 + 6 + 22 + 6).order(ByteOrder.LITTLE_ENDIAN);
        b.put(FILE).put((byte) 3);
        b.putInt((int) HIGH_ID).putShort((short) 4662);
        b.putInt(0xFFFFFFFF).putShort((short) 4663).put(ADDRESS);
        b.putInt((int) HIGH_ID + 1).putShort((short) 4664);
        return b.array();
    }

    @Test
    void theRelayKeepsTheHighIdsBeforeAnIpv6Record() {
        List<FoundSource> sources = ServerRelay.highIds(withIpv6Record());
        assertEquals(1, sources.size(), "the records after the sentinel are no longer aligned: dropped");
        assertEquals(HIGH_ID, sources.get(0).id());
        assertEquals(4662, sources.get(0).port());
    }

    @Test
    void theUdpSourceSearchKeepsTheHighIdsBeforeAnIpv6Record() throws Exception {
        try (DatagramSocket local = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
             DatagramSocket server = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            assertTrue(server.getLocalPort() >= 5);
            server.setSoTimeout(2000);
            List<Runnable> batchTimers = new ArrayList<>();
            List<Runnable> answerTimers = new ArrayList<>();
            ServerGossip.VerifiedPeer peer = new ServerGossip.VerifiedPeer(new ServerGossip.Endpoint(
                    InetAddress.getLoopbackAddress(), server.getLocalPort() - 4), "peer", 0, 0);
            ServerSourceSearcher searcher = new ServerSourceSearcher(() -> List.of(peer), () -> local,
                    batchTimers::add, answerTimers::add);
            CompletableFuture<List<KadPacket.KadSource>> found = searcher.search(FILE, 1000);
            batchTimers.forEach(Runnable::run);
            server.receive(new DatagramPacket(new byte[512], 512));

            byte[] block = withIpv6Record();
            byte[] answer = new byte[2 + block.length];
            answer[0] = (byte) 0xE3;
            answer[1] = (byte) 0x9B;
            System.arraycopy(block, 0, answer, 2, block.length);
            assertTrue(searcher.handle(InetAddress.getLoopbackAddress(), server.getLocalPort(), answer));
            answerTimers.forEach(Runnable::run);

            List<KadPacket.KadSource> sources = found.join();
            assertEquals(1, sources.size(), "never 255.255.255.255, nor the misaligned records after it");
            assertEquals(Integer.reverseBytes((int) HIGH_ID), sources.get(0).ip());
        }
    }
}
