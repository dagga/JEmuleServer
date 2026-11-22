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

import org.jemule.config.ConfigParameter;
import org.jemule.config.RelayFeedConfig;
import org.jemule.config.ServerConfig;
import org.jemule.core.ClientState;
import org.jemule.core.FileIndex;
import org.jemule.core.FileMetadata;
import org.jemule.core.FileSource;
import org.jemule.core.IngestionExecutor;
import org.jemule.protocol.Tag;
import org.jemule.security.AdminAccessFilter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Relay mode: the files the relay server finds go to the server it relays to, which indexes them. */
class RelayFeedTest {
    private static final String TOKEN = "0123456789abcdef-relay-secret";
    private static final URI TARGET = URI.create("http://192.0.2.1:8080" + RelayFeed.PATH);

    private static FileMetadata file(String hash, String name, int sources) {
        return FileMetadata.withoutSources(hash, name, 700_000_000L, "Video",
                List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, sources),
                        new Tag(Tag.TYPE_STRING, Tag.idName((char) 0xD0), "Some Artist")));
    }

    private static String hash(int i) {
        return String.format("%032x", i + 1);
    }

    /** A feed posting to a fake transport that records the files and answers {@code status}. */
    private static final class Fake {
        final List<List<FileMetadata>> posts = new CopyOnWriteArrayList<>();
        final AtomicInteger status = new AtomicInteger(200);
        final AtomicLong clock = new AtomicLong(1_000_000L);
        final AtomicReference<URI> target = new AtomicReference<>(TARGET);

        RelayFeed feed(int capacity) {
            return new RelayFeed(() -> true, target::get, () -> TOKEN, (uri, token, body) -> {
                assertEquals(TOKEN, token);
                byte[] plain = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(body)).readAllBytes();
                posts.add(ServerSearchBridge.decodeSearchResult(plain));
                return status.get();
            }, clock::get, capacity);
        }
    }

    @Test
    void theFilesLeaveByBatchesWithTheirMediaTagsAndSourceCounts() {
        Fake fake = new Fake();
        try (RelayFeed feed = fake.feed(RelayFeed.CAPACITY)) {
            for (int i = 0; i < RelayFeed.BATCH + 10; i++) feed.offer(file(hash(i), "concert " + i + ".avi", 7));
            assertEquals(RelayFeed.Flush.MORE, feed.flushOnce());
            assertEquals(RelayFeed.Flush.SENT, feed.flushOnce());
            assertEquals(RelayFeed.Flush.IDLE, feed.flushOnce());
        }
        assertEquals(List.of(RelayFeed.BATCH, 10), fake.posts.stream().map(List::size).toList());
        FileMetadata sent = fake.posts.getFirst().getFirst();
        assertEquals(hash(0).toUpperCase(), sent.hash().toUpperCase());
        assertEquals("concert 0.avi", sent.name());
        assertEquals(700_000_000L, sent.size());
        assertEquals(7, sent.sourceCount());
        assertTrue(sent.tags().stream().anyMatch(t -> "Some Artist".equals(t.value())), "media tags kept: " + sent.tags());
    }

    @Test
    void aFileIsSentAgainOnlyWhenItHasMoreSources() {
        Fake fake = new Fake();
        try (RelayFeed feed = fake.feed(RelayFeed.CAPACITY)) {
            feed.offer(file(hash(1), "concert.avi", 5));
            feed.offer(file(hash(1).toUpperCase(), "concert.avi", 5));
            feed.offer(file(hash(1), "concert.avi", 3));
            assertEquals(1, feed.queued());
            feed.offer(file(hash(1), "concert.avi", 9));
            assertEquals(2, feed.queued());
            assertEquals(2L, feed.stats().get("alreadyQueued"));
        }
    }

    @Test
    void aRefusedBatchIsKeptAndPostedAgainLater() {
        Fake fake = new Fake();
        fake.status.set(503);
        try (RelayFeed feed = fake.feed(RelayFeed.CAPACITY)) {
            feed.offer(file(hash(1), "first.avi", 5));
            assertEquals(RelayFeed.Flush.FAILED, feed.flushOnce());
            assertEquals(1, feed.queued(), "kept for later");
            assertEquals("HTTP 503", feed.stats().get("lastError"));
            feed.offer(file(hash(2), "second.avi", 5));
            fake.status.set(200);
            assertEquals(RelayFeed.Flush.WAITING, feed.flushOnce(), "nothing posted within the retry delay");
            fake.clock.addAndGet(RelayFeed.RETRY_AFTER);
            assertEquals(RelayFeed.Flush.SENT, feed.flushOnce());
            assertEquals("", feed.stats().get("lastError"));
        }
        assertEquals(List.of("first.avi", "second.avi"), fake.posts.getLast().stream().map(FileMetadata::name).toList(),
                "the refused files first");
    }

    @Test
    void theQueueKeepsTheNewestFilesWhileTheRelayServerIsAway() {
        Fake fake = new Fake();
        fake.status.set(500);
        try (RelayFeed feed = fake.feed(3)) {
            for (int i = 0; i < 5; i++) feed.offer(file(hash(i), "file " + i + ".avi", 5));
            assertEquals(3, feed.queued());
            assertEquals(2L, feed.stats().get("dropped"));
            fake.status.set(200);
            assertEquals(RelayFeed.Flush.SENT, feed.flushOnce());
        }
        assertEquals(List.of("file 2.avi", "file 3.avi", "file 4.avi"),
                fake.posts.getLast().stream().map(FileMetadata::name).toList());
    }

    @Test
    void withoutATargetNothingIsQueued() {
        Fake fake = new Fake();
        fake.target.set(null);
        try (RelayFeed feed = fake.feed(RelayFeed.CAPACITY)) {
            feed.offer(file(hash(1), "concert.avi", 5));
            assertEquals(0, feed.queued());
            assertEquals(RelayFeed.Flush.IDLE, feed.flushOnce());
        }
        assertNull(RelayFeed.target("main.example.net:4661", 0, TOKEN), "port 0: no feed");
        assertNull(RelayFeed.target("main.example.net:4661", 8080, ""), "no token: no feed");
        assertNull(RelayFeed.target("", 8080, TOKEN), "no relay server: no feed");
        assertEquals(URI.create("http://main.example.net:8080/api/relay/files"), RelayFeed.target("main.example.net:4661", 8080, TOKEN));
    }

    @Test
    void inRelayModeTheLocalIndexIsNoLongerWrittenButKeepsTheClientsSources() throws Exception {
        FileIndex index = new FileIndex(null);
        index.addFile(file(hash(1), "already indexed.avi", 3));
        index.setCapacity(() -> 1, () -> false);
        assertTrue(index.isFull());
        Fake fake = new Fake();
        try (RelayFeed feed = fake.feed(RelayFeed.CAPACITY)) {
            index.setRelayFeed(feed);
            assertTrue(index.relaying());
            assertFalse(index.isFull(), "never full in relay mode: the relay server decides");

            ClientState client = new ClientState(InetAddress.getByName("203.0.113.5"), 4662, 0xCB007105L,
                    System.currentTimeMillis(), new AtomicLong(System.currentTimeMillis()));
            FileMetadata published = new FileMetadata(hash(2), "published here.avi", 1000, "Video");
            published.sources().put(FileIndex.sourceKey(client), client);
            index.addFile(published, FileSource.CLIENT_PUSH);
            index.addFiles(List.of(file(hash(3), "found on kad.avi", 12)), FileSource.KAD_SEARCH);

            assertEquals(1, index.fileCount(), "nothing new in the local index");
            assertFalse(index.contains(hash(2)));
            assertEquals(List.of(client), index.getSources(hash(2), null, 10), "the client's source is answered here");
            assertEquals(2, feed.queued(), "both files go to the relay server");
            client.publishedHashes().add(hash(2)); // as PublishHandler records it
            index.removeSource(client);
            assertEquals(List.of(), index.getSources(hash(2), null, 10));
        }
    }

    @Test
    void theRelayServerIndexesThePostedFilesThroughItsAdminPanel() throws Exception {
        FileIndex index = new FileIndex(null);
        AtomicReference<RelayFeedConfig> receiving = new AtomicReference<>(new RelayFeedConfig(8080, TOKEN, "127.0.0.1"));
        RelayFeedReceiver receiver = new RelayFeedReceiver(receiving::get, index, null, IngestionExecutor.direct());
        Server server = mock(Server.class);
        when(server.getRelayFeedReceiver()).thenReturn(receiver);
        // The relay server is not an admin: the rest of the panel stays closed to it
        when(server.getAdminAccessFilter()).thenReturn(new AdminAccessFilter("198.51.100.7", ""));

        try (AdminWebPanel panel = new AdminWebPanel(server)) {
            panel.start(0);
            URI target = RelayFeed.target("127.0.0.1:4661", panel.port(), TOKEN);
            try (RelayFeed feed = new RelayFeed(() -> true, () -> target, () -> TOKEN)) {
                feed.offer(file(hash(1), "concert live.avi", 8));
                feed.offer(file(hash(2), "pthc collection.avi", 8));
                assertEquals(RelayFeed.Flush.SENT, feed.flushOnce());
            }
            assertTrue(index.contains(hash(1)));
            assertEquals(8, index.search("concert", 10).getFirst().sourceCount());
            assertFalse(index.contains(hash(2)), "never a child abuse file, whoever sends it");
            assertEquals(1L, index.newFileCount(FileSource.RELAY_FEED));
            assertEquals(1L, receiver.stats().get("rejected"));

            HttpResponse<String> admin = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + panel.port() + "/api/overview")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, admin.statusCode(), "the admin API stays closed");

            assertEquals(403, post(panel.port(), "Bearer wrong-token-wrong-token", RelayFeed.gzip(RelayFeed.encode(List.of()))));
            assertEquals(403, post(panel.port(), null, RelayFeed.gzip(RelayFeed.encode(List.of()))));
            assertEquals(400, post(panel.port(), "Bearer " + TOKEN, "not gzip".getBytes()));
            receiving.set(new RelayFeedConfig(8080, TOKEN, ""));
            assertEquals(403, post(panel.port(), "Bearer " + TOKEN, RelayFeed.gzip(RelayFeed.encode(List.of()))),
                    "no relayClients: nobody, not even the loopback");
            receiving.set(new RelayFeedConfig(8080, "", "127.0.0.1"));
            assertEquals(403, post(panel.port(), "Bearer ", RelayFeed.gzip(RelayFeed.encode(List.of()))),
                    "no token: no feed");
        }
    }

    private static int post(int port, String authorization, byte[] body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + RelayFeed.PATH))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (authorization != null) request.header("Authorization", authorization);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void theTokenIsReadFromTheFileButNeverShownByTheAdminPanel() {
        Properties props = new Properties();
        props.setProperty("relayServer", "main.example.net:4661");
        props.setProperty("relayFeedPort", "8080");
        props.setProperty("relayToken", TOKEN);
        props.setProperty("relayClients", "relay.example.net");
        ServerConfig config = ServerConfig.fromProperties(props);
        assertEquals(TOKEN, config.relayFeed().effectiveToken());
        assertEquals("relay.example.net", config.relayFeed().clients());
        assertEquals(TOKEN, ServerConfig.fromProperties(toProperties(config)).relayFeed().token(),
                "kept through a settings change");
        assertNull(ConfigParameter.byKey("relayToken"), "never listed nor editable in the admin panel");
        assertFalse(config.relayFeed().toString().contains(TOKEN));
        assertEquals(RelayFeedConfig.DEFAULT, ServerConfig.DEFAULT.relayFeed());
        props.setProperty("relayToken", "short");
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.fromProperties(props));
    }

    private static Properties toProperties(ServerConfig config) {
        Properties props = new Properties();
        config.toProperties().forEach(props::setProperty);
        return props;
    }

    @Test
    void inRelayModeTheClientsAreToldTheFilesOfTheRelayServer() {
        assertEquals(7_457_511, Server.announcedFiles(3_430_000, 7_457_511L), "the index the searches use");
        assertEquals(3_430_000, Server.announcedFiles(3_430_000, null), "relay server's status not known yet");
        assertEquals(Integer.MAX_VALUE, Server.announcedFiles(1, 5_000_000_000L), "an ed2k count is 32 bits");
    }

    @Test
    void theRelayShowsTheRelayServerHistoryNeverItsOwnIndex() throws Exception {
        FileIndex index = new FileIndex(null);
        index.addFile(file(hash(1), "concert live.avi", 8));
        org.jemule.core.MetricsHistory history = new org.jemule.core.MetricsHistory(null);
        long now = System.currentTimeMillis();
        history.record(now - 2 * 86_400_000L, 7_000_000, 50);
        history.record(now - 3_600_000L, 7_400_000, 60);
        RelayFeedReceiver receiver = new RelayFeedReceiver(() -> new RelayFeedConfig(8080, TOKEN, "127.0.0.1"), index,
                null, IngestionExecutor.direct());
        Server server = mock(Server.class);
        when(server.getRelayFeedReceiver()).thenReturn(receiver);
        when(server.getFileIndex()).thenReturn(index);
        when(server.getMetricsHistory()).thenReturn(history);
        when(server.getConfig()).thenReturn(ServerConfig.DEFAULT);

        try (AdminWebPanel panel = new AdminWebPanel(server)) {
            panel.start(0);
            RelayIndexView view = new RelayIndexView(
                    () -> RelayFeed.uri("127.0.0.1:4661", panel.port(), TOKEN, RelayIndexView.PATH), () -> TOKEN);
            view.refresh();
            RelayIndexView.Snapshot snapshot = view.snapshot();
            assertNotNull(snapshot, view.lastError());
            assertEquals(1L, snapshot.files());
            assertEquals((long) ServerConfig.DEFAULT.maxFiles(), snapshot.max());
            assertNull(snapshot.filesAt(now - 3 * 86_400_000L), "before its history");
            assertEquals(7_000_000L, snapshot.filesAt(now - 86_400_000L));
            assertEquals(7_400_000L, snapshot.filesAt(now));

            RelayIndexView stranger = new RelayIndexView(
                    () -> RelayFeed.uri("127.0.0.1:4661", panel.port(), TOKEN + "x", RelayIndexView.PATH), () -> TOKEN + "x");
            stranger.refresh();
            assertNull(stranger.snapshot(), "a wrong token gets nothing");
            assertEquals("HTTP 403", stranger.lastError());

            view.refresh(); // kept when the relay server cannot answer
            assertNotNull(view.snapshot());
        }
    }

    @Test
    void inRelayModeTheChannelsCountTheFilesSentToTheRelayServer() {
        FileIndex index = new FileIndex(null);
        Fake fake = new Fake();
        try (RelayFeed feed = fake.feed(RelayFeed.CAPACITY)) {
            index.setRelayFeed(feed);
            index.addFiles(List.of(file(hash(1), "a.avi", 5), file(hash(2), "b.avi", 5)), FileSource.KAD_SEARCH);
            index.addFile(file(hash(3), "c.avi", 5), FileSource.SERVER_SEARCH);
        }
        assertEquals(2L, index.relayedFileCounts().get(FileSource.KAD_SEARCH));
        assertEquals(1L, index.relayedFileCounts().get(FileSource.SERVER_SEARCH));
        assertEquals(0L, index.totalNewFiles(), "nothing indexed here");
    }

    @Test
    void theFingerprintIgnoresTheCase() {
        assertEquals(RelayFeed.fingerprint("abcdef0123456789abcdef0123456789"),
                RelayFeed.fingerprint("ABCDEF0123456789ABCDEF0123456789"));
        assertNotEquals(RelayFeed.fingerprint(hash(1)), RelayFeed.fingerprint(hash(2)));
        List<Long> many = new ArrayList<>();
        for (int i = 0; i < 1000; i++) many.add(RelayFeed.fingerprint(hash(i)));
        assertEquals(1000, many.stream().distinct().count());
    }

    // #84: the real source count and the channel, the relay server's own thresholds

    /** A relay server's config with kadMinSourcesForIngest=1 and serverIndexUpdateMinSources=5. */
    private static ServerConfig thresholds() {
        Properties props = new Properties();
        props.setProperty("kadMinSourcesForIngest", "1");
        props.setProperty("serverIndexUpdateMinSources", "5");
        return ServerConfig.fromProperties(props);
    }

    private static Tag tag(FileMetadata file, String name) {
        return file.tags().stream().filter(t -> name.equals(t.name())).findFirst().orElse(null);
    }

    @Test
    void theFeedSendsTheRealSourceCountAndTheChannel() throws Exception {
        FileIndex index = new FileIndex(null);
        List<byte[]> bodies = new CopyOnWriteArrayList<>();
        try (RelayFeed feed = new RelayFeed(() -> true, () -> TARGET, () -> TOKEN, (uri, token, body) -> {
            bodies.add(new java.util.zip.GZIPInputStream(new ByteArrayInputStream(body)).readAllBytes());
            return 200;
        }, System::currentTimeMillis, RelayFeed.CAPACITY)) {
            index.setRelayFeed(feed);
            index.addFiles(List.of(file(hash(1), "kad without sources.avi", 0)), FileSource.KAD_SEARCH);
            index.addFiles(List.of(file(hash(2), "index update.avi", 4)), FileSource.SERVER_INDEX_UPDATE);
            assertEquals(RelayFeed.Flush.SENT, feed.flushOnce());
        }
        List<FileMetadata> files = ServerSearchBridge.decodeSearchResult(bodies.getFirst(), true);
        assertEquals(2, files.size());
        assertEquals(0, files.get(0).sourceCount(), "0 is sent as it is, no longer as 1");
        assertEquals(3L, tag(files.get(0), RelayFeed.ORIGIN_TAG).unsignedValue(), "Kad search");
        assertEquals(4, files.get(1).sourceCount());
        assertEquals(5L, tag(files.get(1), RelayFeed.ORIGIN_TAG).unsignedValue(), "index update");
        // A relay server before 1.7.2 decodes the batch as a search result: the file without sources is dropped there
        assertEquals(List.of(hash(2)), ServerSearchBridge.decodeSearchResult(bodies.getFirst()).stream()
                .map(FileMetadata::hash).toList());
    }

    @Test
    void theChannelCodesAreFixed() {
        for (FileSource source : FileSource.values()) {
            assertEquals(source, RelayFeed.origin(RelayFeed.originCode(source)));
        }
        assertEquals(FileSource.UNKNOWN, RelayFeed.origin(99), "a channel of a later version");
        assertEquals(1, RelayFeed.originCode(FileSource.CLIENT_PUSH));
        assertEquals(3, RelayFeed.originCode(FileSource.KAD_SEARCH));
        assertEquals(5, RelayFeed.originCode(FileSource.SERVER_INDEX_UPDATE));
    }

    @Test
    void theRelayServerAppliesItsOwnThresholdsByChannel() throws Exception {
        FileIndex index = new FileIndex(null);
        ServerConfig config = thresholds();
        RelayFeedReceiver receiver = new RelayFeedReceiver(() -> new RelayFeedConfig(8080, TOKEN, "127.0.0.1"),
                origin -> RelayFeedReceiver.minSources(config, origin), index, null, IngestionExecutor.direct());
        List<RelayFeed.Queued> batch = List.of(
                new RelayFeed.Queued(file(hash(1), "kad none.avi", 0), FileSource.KAD_SEARCH),
                new RelayFeed.Queued(file(hash(2), "kad two.avi", 2), FileSource.KAD_SEARCH),
                new RelayFeed.Queued(file(hash(3), "update three.avi", 3), FileSource.SERVER_INDEX_UPDATE),
                new RelayFeed.Queued(file(hash(4), "update six.avi", 6), FileSource.SERVER_INDEX_UPDATE),
                new RelayFeed.Queued(file(hash(5), "published.avi", 0), FileSource.CLIENT_PUSH),
                new RelayFeed.Queued(file(hash(6), "server search none.avi", 0), FileSource.SERVER_SEARCH),
                new RelayFeed.Queued(file(hash(7), "server search one.avi", 1), FileSource.SERVER_SEARCH));
        RelayFeedReceiver.Answer answer = receiver.receive(InetAddress.getLoopbackAddress(), "Bearer " + TOKEN,
                new ByteArrayInputStream(RelayFeed.gzip(RelayFeed.encode(batch))));
        assertEquals(200, answer.status());
        assertFalse(index.contains(hash(1)), "below this server's kadMinSourcesForIngest, whatever the relay's");
        assertTrue(index.contains(hash(2)));
        assertFalse(index.contains(hash(3)), "below serverIndexUpdateMinSources");
        assertTrue(index.contains(hash(4)));
        assertTrue(index.contains(hash(5)), "a file a relay's client published is always kept");
        assertFalse(index.contains(hash(6)));
        assertTrue(index.contains(hash(7)));
        assertEquals(3L, receiver.stats().get("belowThreshold"));
        assertEquals(0L, receiver.stats().get("withoutOrigin"));
        FileMetadata stored = index.search("update", 10).getFirst();
        assertNull(tag(stored, RelayFeed.ORIGIN_TAG), "the channel is not indexed");
        assertEquals(6, stored.sourceCount());
    }

    @Test
    void theFilesOfAnOlderRelayAreKeptAsBefore() throws Exception {
        // A relay before 1.7.2: no channel tag, at least one source
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.writeBytes(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(1).array());
        java.nio.ByteBuffer item = java.nio.ByteBuffer.allocate(4096).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        item.put(org.jemule.network.handler.HandlerUtils.hashToBytes(hash(1))).putInt(0).putShort((short) 0);
        Tag.writeList(item, List.of(new Tag(Tag.TYPE_STRING, Tag.NAME_FILENAME, "old relay.avi"),
                new Tag(Tag.TYPE_INTEGER, Tag.NAME_FILESIZE, 1000), new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 1)));
        body.write(item.array(), 0, item.position());
        FileIndex index = new FileIndex(null);
        ServerConfig config = thresholds();
        RelayFeedReceiver receiver = new RelayFeedReceiver(() -> new RelayFeedConfig(8080, TOKEN, "127.0.0.1"),
                origin -> RelayFeedReceiver.minSources(config, origin), index, null, IngestionExecutor.direct());
        assertEquals(200, receiver.receive(InetAddress.getLoopbackAddress(), "Bearer " + TOKEN,
                new ByteArrayInputStream(RelayFeed.gzip(body.toByteArray()))).status());
        assertTrue(index.contains(hash(1)));
        assertEquals(1L, receiver.stats().get("withoutOrigin"));
    }
}
