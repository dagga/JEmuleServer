package org.jemule.network;

import org.jemule.config.ServerIndexUpdateConfig;
import org.jemule.core.FileIndex;
import org.jemule.core.FileMetadata;
import org.jemule.core.FileSource;
import org.jemule.core.SearchQuery;
import org.jemule.network.handler.HandlerUtils;
import org.jemule.protocol.Tag;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ServerIndexUpdaterTest {

    // ---------- search trees ----------

    private static FileMetadata file(String name, long size, String type) {
        return new FileMetadata("a".repeat(32), name, size, type);
    }

    @Test
    void theTreesCombineKeywordsTypeAndSize() {
        long mb = 1024L * 1024;
        SearchQuery query = SearchQuery.parse(ByteBuffer.wrap(
                ServerIndexUpdater.SearchTrees.build("big movie", "Video", ServerIndexUpdater.SIZES[1])));
        assertTrue(query.test(file("Big Movie.avi", 1000 * mb, "Video")));
        assertFalse(query.test(file("big movie.avi", 800 * mb, "Video")), "below 900 MB");
        assertFalse(query.test(file("big movie.avi", 2000 * mb, "Video")), "2000 MB and more");
        assertFalse(query.test(file("big movie.mp3", 1000 * mb, "Audio")));
        assertFalse(query.test(file("big film.avi", 1000 * mb, "Video")));

        SearchQuery small = SearchQuery.parse(ByteBuffer.wrap(
                ServerIndexUpdater.SearchTrees.build("movie", "Video", ServerIndexUpdater.SIZES[0])));
        assertTrue(small.test(file("movie.avi", 1 * mb, "Video")));
        assertFalse(small.test(file("movie.avi", mb - 1, "Video")));
        SearchQuery large = SearchQuery.parse(ByteBuffer.wrap(
                ServerIndexUpdater.SearchTrees.build("movie", "Video", ServerIndexUpdater.SIZES[2])));
        assertTrue(large.test(file("movie.avi", 2000 * mb, "Video")));
        assertFalse(large.test(file("movie.avi", 1999 * mb, "Video")));
    }

    @Test
    void onlyTheKeywordsOfAClientSearchAreKept() {
        SearchQuery query = SearchQuery.parse(ByteBuffer.wrap(
                ServerIndexUpdater.SearchTrees.build("Holiday Video", "Video", ServerIndexUpdater.SIZES[0])));
        assertEquals("holiday video", ServerIndexUpdater.keywords(query), "type and size dropped");
        SearchQuery or = new SearchQuery.OrQuery(new SearchQuery.TermQuery("a", SearchQuery.ID_FILENAME),
                new SearchQuery.TermQuery("b", SearchQuery.ID_FILENAME));
        assertNull(ServerIndexUpdater.keywords(or), "OR searches are left out");
    }

    // ---------- the session, against a fake server ----------

    private static byte[] packet(int opcode, byte[] payload) {
        return ByteBuffer.allocate(6 + payload.length).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 0xE3).putInt(payload.length + 1).put((byte) opcode).put(payload).array();
    }

    private static byte[][] readPacket(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        byte[] header = data.readNBytes(6);
        int length = ByteBuffer.wrap(header, 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        return new byte[][]{{header[5]}, data.readNBytes(length - 1)};
    }

    /** OP_SEARCHRESULT with {@code count} files of {@code sources} sources each. */
    static byte[] searchResult(int count, int sources, String prefix) {
        ByteBuffer buf = ByteBuffer.allocate(64 + count * 128).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(count);
        for (int i = 0; i < count; i++) {
            buf.put(HandlerUtils.hashToBytes(String.format("%08X", prefix.hashCode()) + String.format("%024X", i)));
            buf.putInt(0).putShort((short) 0);
            Tag.writeList(buf, List.of(new Tag(Tag.TYPE_STRING, Tag.NAME_FILENAME, prefix + " file " + i + ".avi"),
                    new Tag(Tag.TYPE_INTEGER, Tag.NAME_FILESIZE, 1000 + i),
                    new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, sources)));
        }
        buf.put((byte) 0);
        return java.util.Arrays.copyOf(buf.array(), buf.position());
    }

    @Test
    void theSessionLogsInAsAClientAndSearches() throws Exception {
        try (ServerSocket fake = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            List<byte[]> received = new CopyOnWriteArrayList<>();
            Thread server = Thread.ofVirtual().start(() -> {
                try (Socket s = fake.accept(); InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {
                    byte[][] login = readPacket(in);
                    received.add(login[1]);
                    out.write(packet(0x38, new byte[]{5, 0, 'h', 'e', 'l', 'l', 'o'})); // a welcome message
                    out.write(packet(0x40, new byte[]{1, 2, 0, 0, 0, 0, 0, 0}));       // OP_IDCHANGE
                    readPacket(in);                                                     // the empty OP_OFFERFILES
                    byte[][] search = readPacket(in);
                    received.add(search[1]);
                    // The rest of the welcome, read during the first search: not a warning (production, 2026-10-26)
                    out.write(packet(0x38, message("No traffic limit. No time limit - Unlimited and free server switches")));
                    out.write(packet(0x33, searchResult(2, 5, "found")));
                    byte[][] second = readPacket(in);
                    out.write(packet(0x38, message("Warning: flood detected, slow down")));
                    in.read(); // until the client closes
                } catch (IOException ignored) {
                }
            });
            ServerGossip.Endpoint endpoint = new ServerGossip.Endpoint(InetAddress.getLoopbackAddress(), fake.getLocalPort());
            byte[] hash = new byte[16];
            hash[5] = 14;
            hash[14] = 111;
            try (ServerUpdateSession session = ServerUpdateSession.open(endpoint, hash, "JEmuleServer", 5000)) {
                ServerUpdateSession.Answer answer = session.search(ServerIndexUpdater.SearchTrees.build("found", null, null));
                assertEquals(2, ServerSearchBridge.decodeSearchResult(answer.payload()).size());
                assertThrows(ServerUpdateSession.WarningException.class,
                        () -> session.search(ServerIndexUpdater.SearchTrees.build("again", null, null)));
            }
            server.join(5000);

            ByteBuffer login = ByteBuffer.wrap(received.get(0)).order(ByteOrder.LITTLE_ENDIAN);
            byte[] sentHash = new byte[16];
            login.get(sentHash);
            assertArrayEquals(hash, sentHash);
            assertEquals(0, login.getInt(), "client ID 0");
            assertEquals(ServerUpdateSession.CLIENT_PORT, Short.toUnsignedInt(login.getShort()));
            List<Tag> tags = Tag.readList(login);
            assertEquals("JEmuleServer", tags.getFirst().value());
            assertArrayEquals(ServerIndexUpdater.SearchTrees.build("found", null, null), received.get(1));
        }
    }

    @Test
    void onlyRealWarningsCount() {
        assertFalse(ServerUpdateSession.isWarning("No traffic limit. No time limit - Unlimited and free server switches"));
        assertFalse(ServerUpdateSession.isWarning("Banana split server, welcome"));
        assertTrue(ServerUpdateSession.isWarning("Flood detected: slow down"));
        assertTrue(ServerUpdateSession.isWarning("You have been banned for 1 hour"));
        assertTrue(ServerUpdateSession.isWarning("Too many searches, please wait"));
        assertTrue(ServerUpdateSession.isWarning("Search limit reached"));
    }

    private static byte[] message(String text) {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ByteBuffer.allocate(2 + bytes.length).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) bytes.length).put(bytes).array();
    }

    // ---------- the updater, with fake sessions and a simulated clock ----------

    /** Answers every search with {@code answer.apply(search keywords + type + slice)}. */
    private static final class FakeServers {
        final List<String> searches = new CopyOnWriteArrayList<>();
        final AtomicLong clock = new AtomicLong(1_000_000_000L);
        Function<String, byte[]> answer = s -> searchResult(10, 5, s);
        int logins;

        ServerIndexUpdater.Session open() {
            logins++;
            return new ServerIndexUpdater.Session() {
                public ServerUpdateSession.Answer search(byte[] tree) {
                    String text = describe(SearchQuery.parse(ByteBuffer.wrap(tree)));
                    searches.add(text);
                    return new ServerUpdateSession.Answer(answer.apply(text), 200);
                }

                public void close() {
                }
            };
        }

        /** "words|type|size>|size<" from a parsed tree. */
        static String describe(SearchQuery query) {
            List<String> parts = new ArrayList<>();
            describe(query, parts);
            return String.join("|", parts);
        }

        private static void describe(SearchQuery query, List<String> parts) {
            switch (query) {
                case SearchQuery.AndQuery and -> {
                    describe(and.left(), parts);
                    describe(and.right(), parts);
                }
                case SearchQuery.TermQuery t -> parts.add(t.id() == SearchQuery.ID_FILETYPE ? "type " + t.term() : t.term());
                default -> parts.add("size");
            }
        }
    }

    private ServerIndexUpdater updater(FileIndex index, FakeServers servers, ServerIndexUpdateConfig config,
                                       int maxSearches) throws Exception {
        ServerGossip.VerifiedPeer peer = new ServerGossip.VerifiedPeer(
                new ServerGossip.Endpoint(InetAddress.getByName("192.0.2.1"), 4661), "peer", 0, 0);
        Collection<ServerGossip.VerifiedPeer> peers = List.of(peer);
        return new ServerIndexUpdater(() -> peers, index, null, () -> config, e -> false,
                (endpoint, hash) -> servers.open(), servers.clock::get,
                time -> {
                    if (servers.searches.size() >= maxSearches) return false;
                    servers.clock.set(Math.max(servers.clock.get(), time));
                    return true;
                },
                new Random(7));
    }

    private static void runUntilDone(ServerIndexUpdater updater) throws Exception {
        updater.refreshWorkers();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            @SuppressWarnings("unchecked")
            List<java.util.Map<String, Object>> perServer = (List<java.util.Map<String, Object>>) updater.stats().get("perServer");
            if (perServer.stream().allMatch(v -> "stopped".equals(v.get("state")))) return;
            Thread.sleep(20);
        }
        fail("the worker did not stop");
    }

    private static FileIndex indexWithWords(String... names) {
        FileIndex index = new FileIndex(null);
        int i = 0;
        for (String name : names) index.addFile(new FileMetadata(String.format("%032x", ++i), name, 100, "Video"));
        return index;
    }

    @Test
    void aFullPageIsRefinedPerTypeThenPerSizeWithinBatchesOfSix() throws Exception {
        FileIndex index = indexWithWords("concert live 1", "concert live 2", "concert 3");
        FakeServers servers = new FakeServers();
        // "concert" fills a page, and so does its Video slice
        servers.answer = s -> s.equals("concert") || s.equals("concert|type Video") ? searchResult(300, 5, s)
                : searchResult(5, 5, s);
        ServerIndexUpdater updater = updater(index, servers,
                new ServerIndexUpdateConfig(true, 120, 2000, 3), 9);

        runUntilDone(updater);

        List<String> s = servers.searches;
        assertEquals("concert", s.get(0), "the word with the most files first");
        assertEquals("concert|type Video", s.get(1));
        assertEquals(List.of("concert|type Video|size|size", "concert|type Video|size|size", "concert|type Video|size"),
                s.subList(2, 5), "the size slices of the full type come next");
        assertEquals("concert|type Audio", s.get(5));
        assertEquals("live", s.get(6), "a batch holds 6 searches: the other types wait a day");
        assertEquals(1, servers.logins, "one session");
        assertTrue(s.stream().noneMatch(x -> x.contains("type Pro")), "deferred");
    }

    @Test
    void theClientsKeywordsComeFirstWithoutTheirFiltersAndFilesNeedSources() throws Exception {
        FileIndex index = indexWithWords("alpha beta.avi");
        FakeServers servers = new FakeServers();
        servers.answer = s -> s.equals("holiday|video") ? searchResult(4, 5, "holiday") : searchResult(3, 1, s);
        ServerIndexUpdater updater = updater(index, servers,
                new ServerIndexUpdateConfig(true, 120, 2000, 3), 2);
        updater.noteClientSearch(SearchQuery.parse(ByteBuffer.wrap(
                ServerIndexUpdater.SearchTrees.build("Holiday Video", "Audio", ServerIndexUpdater.SIZES[2]))));

        runUntilDone(updater);

        assertEquals("holiday|video", servers.searches.get(0), "keywords only, no type or size");
        assertEquals(4, index.newFileCount(FileSource.SERVER_INDEX_UPDATE), "files of 5 sources indexed");
        assertEquals(5, index.fileCount(), "the files of 1 source are not");
    }

    @Test
    void anIncidentClosesTheSessionAndPausesTheServer() throws Exception {
        FileIndex index = indexWithWords("alpha beta.avi");
        FakeServers servers = new FakeServers();
        ServerGossip.VerifiedPeer peer = new ServerGossip.VerifiedPeer(
                new ServerGossip.Endpoint(InetAddress.getByName("192.0.2.1"), 4661), "peer", 0, 0);
        List<Long> sleeps = new CopyOnWriteArrayList<>();
        ServerIndexUpdater updater = new ServerIndexUpdater(() -> List.of(peer), index, null,
                () -> new ServerIndexUpdateConfig(true, 120, 2000, 3), e -> false,
                (endpoint, hash) -> new ServerIndexUpdater.Session() {
                    public ServerUpdateSession.Answer search(byte[] tree) throws IOException {
                        servers.searches.add("x");
                        throw new IOException("cut");
                    }

                    public void close() {
                        servers.logins++;
                    }
                },
                servers.clock::get,
                time -> {
                    sleeps.add(time - servers.clock.get());
                    if (servers.searches.size() >= 1 && sleeps.size() >= 2) return false;
                    servers.clock.set(Math.max(servers.clock.get(), time));
                    return true;
                }, new Random(8));

        runUntilDone(updater);

        assertEquals(1, servers.searches.size());
        assertEquals(1L, updater.stats().get("incidents"));
        assertEquals(1, servers.logins, "the session is closed");
        assertTrue(sleeps.stream().anyMatch(s -> s >= UpdateSchedule.FIRST_STOP - UpdateSchedule.MAX_PAUSE),
                "then hours without search: " + sleeps);
    }

    @Test
    void nothingIsSentWhileDisabledOrTheIndexIsFull() throws Exception {
        FileIndex index = indexWithWords("alpha beta.avi");
        FakeServers servers = new FakeServers();
        ServerIndexUpdater disabled = updater(index, servers, new ServerIndexUpdateConfig(false, 40, 300, 3), 0);
        runUntilDone(disabled);
        assertTrue(servers.searches.isEmpty());

        index.setCapacity(() -> 1, () -> false);
        ServerIndexUpdater full = updater(index, servers, new ServerIndexUpdateConfig(true, 40, 300, 3), 0);
        runUntilDone(full);
        assertTrue(servers.searches.isEmpty());
        assertEquals(0, servers.logins);
    }
}
