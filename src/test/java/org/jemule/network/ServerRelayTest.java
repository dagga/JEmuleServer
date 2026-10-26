package org.jemule.network;

import org.jemule.config.ServerConfig;
import org.jemule.core.FileIndex;
import org.jemule.core.FileMetadata;
import org.jemule.network.handler.FoundSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ServerRelayTest {

    /** A relay server answering with {@code pages} pages of 300 files, then sources. */
    private static final class FakeRelay {
        final List<String> requests = new CopyOnWriteArrayList<>();
        final AtomicInteger logins = new AtomicInteger();
        final AtomicLong clock = new AtomicLong(1_000_000L);
        volatile int pages = 5;
        volatile boolean unreachable;
        volatile boolean cutNextRequest;
        volatile byte[] sources = new byte[17];

        ServerRelay.Session open() throws IOException {
            if (unreachable) throw new IOException("connection refused");
            logins.incrementAndGet();
            AtomicInteger page = new AtomicInteger();
            return new ServerRelay.Session() {
                public ServerUpdateSession.Answer search(byte[] tree) throws IOException {
                    cut();
                    requests.add("search");
                    page.set(1);
                    return new ServerUpdateSession.Answer(page(1), 10);
                }

                public ServerUpdateSession.Answer more() {
                    requests.add("more");
                    return new ServerUpdateSession.Answer(page(page.incrementAndGet()), 10);
                }

                public byte[] sources(byte[] request) throws IOException {
                    cut();
                    requests.add("sources");
                    return sources;
                }

                public void close() {
                }
            };
        }

        private void cut() throws IOException {
            if (cutNextRequest) {
                cutNextRequest = false;
                throw new IOException("session closed by the server");
            }
        }

        /** Page {@code n}: 300 distinct files, with the "more" flag while pages remain. */
        byte[] page(int n) {
            byte[] page = ServerIndexUpdaterTest.searchResult(300, 5, "page" + n);
            page[page.length - 1] = (byte) (n < pages ? 1 : 0);
            return page;
        }
    }

    private static ServerRelay relay(FakeRelay fake, AtomicBoolean full, String server) {
        return new ServerRelay(() -> server, full::get, new FileIndex(null), null,
                (endpoint, hash) -> fake.open(), fake.clock::get);
    }

    private static byte[] tree() {
        return ServerIndexUpdater.SearchTrees.build("concert", null, null);
    }

    @Test
    void nothingIsRelayedWhileTheIndexHasRoom() {
        FakeRelay fake = new FakeRelay();
        AtomicBoolean full = new AtomicBoolean(false);
        try (ServerRelay relay = relay(fake, full, "192.0.2.1:4661")) {
            assertFalse(relay.active());
            assertNull(relay.forkSearch(tree(), ServerRelay.MAX_PAGES));
            assertNull(relay.forkSources(new byte[20]));
            assertEquals(0, relay.announce().length, "no server pushed to the clients");
        }
        try (ServerRelay relay = relay(fake, new AtomicBoolean(true), "")) {
            assertFalse(relay.active(), "no relay server configured");
        }
        assertEquals(0, fake.logins.get());
    }

    @Test
    void aFullServerPlaysTheSearchOnTheRelayServerWithTheMorePages() throws Exception {
        FakeRelay fake = new FakeRelay();
        try (ServerRelay relay = relay(fake, new AtomicBoolean(true), "192.0.2.1:4661")) {
            assertTrue(relay.active());
            List<FileMetadata> found = relay.forkSearch(tree(), ServerRelay.MAX_PAGES).get(5, TimeUnit.SECONDS);
            assertEquals(900, found.size(), "three pages of 300");
            assertEquals(List.of("search", "more", "more"), fake.requests);

            fake.requests.clear();
            fake.pages = 1;
            assertEquals(300, relay.forkSearch(tree(), ServerRelay.MAX_PAGES).get(5, TimeUnit.SECONDS).size());
            assertEquals(List.of("search"), fake.requests, "no More once the relay server has nothing more");
            assertEquals(1, fake.logins.get(), "the session is kept for the next searches");

            fake.requests.clear();
            fake.pages = 5;
            assertEquals(300, relay.forkSearch(tree(), 1).get(5, TimeUnit.SECONDS).size(), "a UDP search: one page");
        }
    }

    @Test
    void theClientsLearnTheRelayServerAtLogin() throws Exception {
        try (ServerRelay relay = relay(new FakeRelay(), new AtomicBoolean(true), "192.0.2.1:4661")) {
            assertArrayEquals(new byte[]{(byte) 192, 0, 2, 1, (byte) (4661 & 0xFF), (byte) (4661 >> 8)}, relay.announce());
            assertEquals(1L, relay.stats().get("announcedLogins"));
            assertTrue(relay.isRelayServer(InetAddress.getByName("192.0.2.1")));
            assertFalse(relay.isRelayServer(InetAddress.getByName("192.0.2.2")));
            assertEquals("192.0.2.1:4661", relay.describe());
        }
    }

    @Test
    void onlyTheHighIdSourcesOfTheRelayServerAreKept() throws Exception {
        FakeRelay fake = new FakeRelay();
        ByteBuffer answer = ByteBuffer.allocate(16 + 1 + 3 * 6).order(ByteOrder.LITTLE_ENDIAN);
        answer.put(new byte[16]).put((byte) 3);
        answer.putInt(0x0A0B0C0D).putShort((short) 4662);   // HighID
        answer.putInt(1234).putShort((short) 4662);         // LowID on the relay server: unreachable from here
        answer.putInt(0x01020304).putShort((short) 0);      // no port
        fake.sources = answer.array();
        try (ServerRelay relay = relay(fake, new AtomicBoolean(true), "192.0.2.1:4661")) {
            List<FoundSource> found = relay.forkSources(new byte[20]).get(5, TimeUnit.SECONDS);
            assertEquals(1, found.size());
            assertEquals(0x0A0B0C0DL, found.getFirst().id());
            assertEquals(4662, found.getFirst().port());
        }
    }

    @Test
    void aSessionClosedByTheRelayServerIsOpenedAgainOnce() throws Exception {
        FakeRelay fake = new FakeRelay();
        fake.pages = 1;
        try (ServerRelay relay = relay(fake, new AtomicBoolean(true), "192.0.2.1:4661")) {
            relay.forkSearch(tree(), 1).get(5, TimeUnit.SECONDS);
            fake.cutNextRequest = true;
            assertEquals(300, relay.forkSearch(tree(), 1).get(5, TimeUnit.SECONDS).size());
            assertEquals(2, fake.logins.get());
            assertEquals(0L, relay.stats().get("failures"));
        }
    }

    @Test
    void anUnreachableRelayServerIsLeftAloneForAWhile() throws Exception {
        FakeRelay fake = new FakeRelay();
        fake.unreachable = true;
        try (ServerRelay relay = relay(fake, new AtomicBoolean(true), "192.0.2.1:4661")) {
            assertEquals(List.of(), relay.forkSearch(tree(), 1).get(5, TimeUnit.SECONDS));
            assertEquals(1L, relay.stats().get("failures"));
            fake.unreachable = false;
            assertEquals(List.of(), relay.forkSearch(tree(), 1).get(5, TimeUnit.SECONDS), "within the retry delay");
            assertEquals(0, fake.logins.get());
            fake.clock.addAndGet(ServerRelay.RETRY_AFTER);
            fake.pages = 1;
            assertEquals(300, relay.forkSearch(tree(), 1).get(5, TimeUnit.SECONDS).size());
        }
    }

    @Test
    void theMoreFlagIsReadAfterTheResults() {
        FakeRelay fake = new FakeRelay();
        fake.pages = 2;
        assertTrue(ServerSearchBridge.moreAvailable(fake.page(1)));
        assertFalse(ServerSearchBridge.moreAvailable(fake.page(2)));
        assertFalse(ServerSearchBridge.moreAvailable(new byte[]{0, 0, 0}));
    }

    @Test
    void theRelayServerIsAHostAndPortInTheConfiguration() {
        Properties props = new Properties();
        props.setProperty("relayServer", " 54.37.74.32:4661 ");
        ServerConfig config = ServerConfig.fromProperties(props);
        assertEquals("54.37.74.32:4661", config.relayServer());
        assertEquals("54.37.74.32:4661", config.toProperties().get("relayServer"));
        assertEquals("54.37.74.32:4661", config.withPort(4662).relayServer(), "kept by the other settings");
        assertEquals("", ServerConfig.DEFAULT.relayServer());
        props.setProperty("relayServer", "54.37.74.32");
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.fromProperties(props));
    }
}
