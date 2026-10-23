package org.jemule.network;

import org.jemule.config.KadSourcesConfig;
import org.jemule.config.ServerConfig;
import org.jemule.core.ClientRegistry;
import org.jemule.core.ClientState;
import org.jemule.kad.KadPacket;
import org.jemule.network.handler.FoundSource;
import org.jemule.network.handler.HandlerUtils;
import org.jemule.protocol.OpCode;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class KadSourceServiceTest {
    private static final byte[] FILE = HandlerUtils.hashToBytes("0123456789ABCDEF0123456789ABCDEF");
    private static final long SIZE = 700_000_000L;
    /** 91.216.110.42 in host order, as Kad sends it. */
    private static final int PUBLIC_IP = 0x5BD86E2A;

    private final ClientRegistry registry = new ClientRegistry();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<CompletableFuture<List<KadPacket.KadSource>>> searches = new ArrayList<>();
    private ServerConfig config = config(true, 10, 30);
    private final KadSourceService service = new KadSourceService((hash, size) -> {
        CompletableFuture<List<KadPacket.KadSource>> search = new CompletableFuture<>();
        searches.add(search);
        return search;
    }, registry, a -> a.getHostAddress().equals("91.216.110.99"), () -> config, now::get);

    private static ServerConfig config(boolean enabled, int minLocal, int perMinute) {
        return new ServerConfig(14665, 2 * 1024 * 1024, 300, 50, 1000, 100000, 1000, 200,
                null, null, false, 50f, 10, 60, 1800, 0, "127.0.0.1", 0)
                .withKadSources(new KadSourcesConfig(enabled, minLocal, perMinute));
    }

    /** A connected client; its packets are collected in {@code received}. */
    private ClientState client(String ip, long id, String userHash, List<byte[]> received) throws Exception {
        ClientState s = new ClientState(InetAddress.getByName(ip), 50000, id, 0, new AtomicLong());
        s.setListenPort(4662);
        s.setUserHash(HandlerUtils.hashToBytes(userHash));
        registry.add(s, p -> {
            if (received != null) {
                received.add(new byte[]{p.opcode()});
                received.add(p.data());
            }
        });
        return s;
    }

    private static byte[] hash(int n) {
        return HandlerUtils.hashToBytes(String.format("%032X", n));
    }

    private static KadPacket.KadSource direct(int user, int ip) {
        return new KadPacket.KadSource(hash(user), 4, ip, 4662, 0x01);
    }

    /** <ID 4><port 2> of each source of an OP_FOUNDSOURCES payload. */
    private static List<long[]> plainSources(byte[] payload) {
        ByteBuffer b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        b.position(16);
        int count = Byte.toUnsignedInt(b.get());
        List<long[]> sources = new ArrayList<>();
        for (int i = 0; i < count; i++) sources.add(new long[]{Integer.toUnsignedLong(b.getInt()), Short.toUnsignedInt(b.getShort())});
        return sources;
    }

    @Test
    void kadSourcesAreSentInASecondAnswerWithTheirIpAsHighId() throws Exception {
        List<byte[]> received = new ArrayList<>();
        ClientState requester = client("82.1.2.3", 0x0302_0152L, "11111111111111111111111111111111", received);

        assertEquals(List.of(), service.onGetSources(requester, FILE, SIZE, false, List.of()), "local answer first");
        assertEquals(1, searches.size());
        searches.get(0).complete(List.of(direct(2, PUBLIC_IP)));

        assertEquals(OpCode.FOUND_SOURCES.value, received.get(0)[0]);
        List<long[]> sources = plainSources(received.get(1));
        assertEquals(1, sources.size());
        assertEquals(0x2A6ED85BL, sources.get(0)[0], "eD2K ID of 91.216.110.42: ntohl of the Kad IP");
        assertEquals(4662, sources.get(0)[1]);
    }

    @Test
    void firewalledKadSourcesConnectedHereAreSentWithTheirLowId() throws Exception {
        List<byte[]> received = new ArrayList<>();
        ClientState requester = client("82.1.2.3", 0x0302_0152L, "11111111111111111111111111111111", received);
        client("85.4.5.6", 42, "22222222222222222222222222222222", null); // LowID here, firewalled on Kad

        service.onGetSources(requester, FILE, SIZE, false, List.of());
        searches.get(0).complete(List.of(
                new KadPacket.KadSource(HandlerUtils.hashToBytes("22222222222222222222222222222222"), 3, 0, 0, 0),
                new KadPacket.KadSource(hash(9), 5, 0, 0, 0)));

        List<long[]> sources = plainSources(received.get(1));
        assertEquals(1, sources.size(), "the firewalled source unknown here is useless");
        assertEquals(42, sources.get(0)[0], "reached through this server's callback");
        assertEquals(1L, service.stats().get("firewalledMatched"));
        assertEquals(1L, service.stats().get("firewalledDropped"));
    }

    @Test
    void unusableSourcesAreDropped() throws Exception {
        List<byte[]> received = new ArrayList<>();
        ClientState requester = client("82.1.2.3", 0x0302_0152L, "11111111111111111111111111111111", received);

        service.onGetSources(requester, FILE, SIZE, false, List.of());
        searches.get(0).complete(List.of(
                direct(2, 0xC0A80001),                     // 192.168.0.1: private
                direct(3, 0x5BD86E63),                     // 91.216.110.99: IP filter
                direct(4, 0x52010203),                     // 82.1.2.3: the requester's IP
                new KadPacket.KadSource(hash(5), 4, PUBLIC_IP, 0, 0), // no TCP port
                new KadPacket.KadSource(HandlerUtils.hashToBytes("11111111111111111111111111111111"), 4, 0x5BD86E2B, 4662, 0)));
        assertTrue(received.isEmpty(), "nothing usable, nothing sent");
    }

    @Test
    void obfuscatedAnswersCarryTheCryptOptionsAndTheUserHash() throws Exception {
        List<byte[]> received = new ArrayList<>();
        ClientState requester = client("82.1.2.3", 0x0302_0152L, "11111111111111111111111111111111", received);

        service.onGetSources(requester, FILE, SIZE, true, List.of());
        searches.get(0).complete(List.of(direct(2, PUBLIC_IP)));

        assertEquals(OpCode.SOURCES_RESULT_OBFU.value, received.get(0)[0]);
        ByteBuffer b = ByteBuffer.wrap(received.get(1)).order(ByteOrder.LITTLE_ENDIAN);
        b.position(16 + 1 + 6);
        assertEquals((byte) 0x81, b.get(), "crypt layer supported, user hash follows");
        byte[] user = new byte[16];
        b.get(user);
        assertArrayEquals(hash(2), user);
    }

    @Test
    void theKadSourcesOfAFileAreReusedForTwentyMinutes() throws Exception {
        ClientState first = client("82.1.2.3", 0x0302_0152L, "11111111111111111111111111111111", null);
        ClientState second = client("82.1.2.4", 0x0402_0152L, "33333333333333333333333333333333", null);

        service.onGetSources(first, FILE, SIZE, false, List.of());
        searches.get(0).complete(List.of(direct(2, PUBLIC_IP)));

        List<FoundSource> answer = service.onGetSources(second, FILE, SIZE, false, List.of());
        assertEquals(1, answer.size(), "added to the immediate answer");
        assertEquals(1, searches.size(), "no new Kad search");

        now.addAndGet(KadSourceService.CACHE_TTL_MS);
        service.onGetSources(second, FILE, SIZE, false, List.of());
        assertEquals(2, searches.size(), "expired: searched again");
    }

    @Test
    void enoughLocalSourcesOrDisabledMeansNoSearch() throws Exception {
        ClientState requester = client("82.1.2.3", 0x0302_0152L, "11111111111111111111111111111111", null);
        List<FoundSource> local = new ArrayList<>();
        for (int i = 0; i < 10; i++) local.add(new FoundSource(0x01000000L + i, 4662, 0, null));
        assertSame(local, service.onGetSources(requester, FILE, SIZE, false, local));

        config = config(false, 10, 30);
        service.onGetSources(requester, FILE, SIZE, false, List.of());
        assertTrue(searches.isEmpty());
    }

    @Test
    void budgetsBoundTheSearches() throws Exception {
        ClientState requester = client("82.1.2.3", 0x0302_0152L, "11111111111111111111111111111111", null);
        service.onGetSources(requester, FILE, SIZE, false, List.of());
        now.addAndGet(60_000); // asked again while the search runs: nothing cached yet
        service.onGetSources(requester, FILE, SIZE, false, List.of());
        assertEquals(1, searches.size(), "one search per file and client every 15 minutes");
        now.addAndGet(KadSourceService.CLIENT_REASK_MS);

        config = config(true, 10, 2);
        for (int i = 1; i <= 3; i++) service.onGetSources(requester, hash(100 + i), SIZE, false, List.of());
        assertEquals(3, searches.size(), "then 2 per minute for all clients: the third file is not searched");
        assertEquals(2L, service.stats().get("skippedBudget"));
    }
}
