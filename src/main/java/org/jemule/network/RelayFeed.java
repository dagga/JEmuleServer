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

import org.jemule.core.FileIndex;
import org.jemule.core.FileMetadata;
import org.jemule.core.ServerThreads;
import org.jemule.network.handler.HandlerUtils;
import org.jemule.protocol.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

/**
 * Relay mode: sends the files this server finds (Kad, other eD2K servers, its clients' OP_OFFERFILES) to the server it
 * relays to, whose index it uses. They wait in a bounded queue and leave by batches, posted to
 * {@code http://<relay server>:<relayFeedPort>/api/relay/files} with the shared {@code relayToken}. While the relay
 * server cannot be reached, the queue keeps the newest {@link #CAPACITY} files and the oldest are lost.
 * <p>
 * Body: gzip of {@code <count 4>(<hash 16><ID 4><port 2><tags>)[count]}, the OP_SEARCHRESULT format, with ID and
 * port 0: the files, never their sources.
 */
public final class RelayFeed implements FileIndex.RelayFeed, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RelayFeed.class);
    public static final String PATH = "/api/relay/files";
    /** Files waiting at most; beyond, the oldest are dropped. */
    static final int CAPACITY = 200_000;
    /** Files per post. */
    static final int BATCH = 2_000;
    /** Longest time a file waits for a batch to fill. */
    static final long FLUSH_MILLIS = 2_000;
    /** Time without a post after a failed one. */
    static final long RETRY_AFTER = 30_000;
    /** Slots of the cache of the files already queued (lossy: two files may share a slot). */
    static final int RECENT_SLOTS = 1 << 19;

    /** Posts a batch; returns the HTTP status (a fake one in the tests). */
    interface Transport {
        int post(URI target, String token, byte[] body) throws IOException;
    }

    /** Outcome of {@link #flushOnce()}. */
    enum Flush { IDLE, SENT, MORE, FAILED, WAITING }

    private final BooleanSupplier relayMode;
    private final Supplier<URI> target;
    private final Supplier<String> token;
    private final Transport transport;
    private final LongSupplier clock;
    private final int capacity;
    private final Object lock = new Object();
    private final ArrayDeque<FileMetadata> queue = new ArrayDeque<>();
    /** Fingerprint and highest source count of the files queued lately: a file is sent again when its count rose. */
    private final long[] recentKeys = new long[RECENT_SLOTS];
    private final int[] recentCounts = new int[RECENT_SLOTS];
    private volatile boolean running = true;
    private volatile long retryAt;
    private volatile String lastError = "";
    private volatile long lastSentAt;
    private Thread thread;

    private long offered;
    private long skipped;
    private long dropped;
    private long sent;
    private long batches;
    private long failures;

    /**
     * @param relayMode whether this server is in relay mode: the index then hands every file it gets to this feed
     * @param target    where to post the files, null when there is no feed (no relay server, port 0, no token)
     * @param token     the shared secret
     */
    RelayFeed(BooleanSupplier relayMode, Supplier<URI> target, Supplier<String> token) {
        this(relayMode, target, token, httpTransport(), System::currentTimeMillis, CAPACITY);
    }

    RelayFeed(BooleanSupplier relayMode, Supplier<URI> target, Supplier<String> token, Transport transport,
              LongSupplier clock, int capacity) {
        this.capacity = capacity;
        this.relayMode = relayMode;
        this.target = target;
        this.token = token;
        this.transport = transport;
        this.clock = clock;
    }

    /** The relay server's feed address, or null without a relay server, a port or a token. */
    static URI target(String relayServer, int port, String token) {
        return uri(relayServer, port, token, PATH);
    }

    /** {@code path} on the relay server's admin panel, or null without a relay server, a port or a token. */
    static URI uri(String relayServer, int port, String token, String path) {
        if (relayServer == null || relayServer.isBlank() || port <= 0 || token == null || token.isEmpty()) return null;
        int colon = relayServer.lastIndexOf(':');
        String host = colon > 0 ? relayServer.substring(0, colon).trim() : relayServer.trim();
        try {
            return new URI("http", null, host, port, path, null, null);
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    /** Starts the thread posting the batches. */
    synchronized void start() {
        if (thread != null) return;
        thread = ServerThreads.factory(ServerThreads.Tier.INGESTION, "relay-feed").newThread(this::run);
        thread.start();
    }

    @Override
    public boolean relayMode() {
        return running && relayMode.getAsBoolean();
    }

    /** Queues a file for the relay server, unless it was queued lately with as many sources. */
    @Override
    public void offer(FileMetadata file) {
        if (file == null || file.hash() == null || target.get() == null) return;
        int count = Math.max(1, file.sourceCount());
        long key = fingerprint(file.hash());
        int slot = (int) (key ^ (key >>> 32)) & (RECENT_SLOTS - 1);
        boolean wake;
        synchronized (lock) {
            offered++;
            if (recentKeys[slot] == key && recentCounts[slot] >= count) {
                skipped++;
                return;
            }
            recentKeys[slot] = key;
            recentCounts[slot] = count;
            queue.addLast(file);
            while (queue.size() > capacity) {
                queue.pollFirst();
                dropped++;
            }
            wake = queue.size() >= BATCH;
            if (wake) lock.notifyAll();
        }
    }

    /** A 64-bit fingerprint of a hash, whatever its case. */
    static long fingerprint(String hash) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < hash.length(); i++) {
            h ^= Character.toUpperCase(hash.charAt(i));
            h *= 0x100000001b3L;
        }
        return h == 0 ? 1 : h;
    }

    private void run() {
        while (running) {
            try {
                synchronized (lock) {
                    if (running && queue.size() < BATCH) lock.wait(FLUSH_MILLIS);
                }
                while (running && flushOnce() == Flush.MORE) {
                    // a full batch left: the next one may be full too
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                log.error("Relay feed failed: {}", e.getMessage(), e);
            }
        }
    }

    /** Posts one batch of the queue, if any and if the last failure is old enough. */
    Flush flushOnce() {
        long now = clock.getAsLong();
        if (now < retryAt) return Flush.WAITING;
        URI uri = target.get();
        String secret = token.get();
        if (uri == null || secret == null || secret.isEmpty()) return Flush.IDLE;
        List<FileMetadata> batch = new ArrayList<>();
        synchronized (lock) {
            while (batch.size() < BATCH && !queue.isEmpty()) batch.add(queue.pollFirst());
        }
        if (batch.isEmpty()) return Flush.IDLE;
        String error;
        try {
            int status = transport.post(uri, secret, gzip(encode(batch)));
            if (status == 200) {
                synchronized (lock) {
                    sent += batch.size();
                    batches++;
                }
                lastSentAt = now;
                if (!lastError.isEmpty()) {
                    log.info("Relay feed: {} reachable again", uri);
                    lastError = "";
                }
                return batch.size() == BATCH ? Flush.MORE : Flush.SENT;
            }
            error = "HTTP " + status;
        } catch (IOException e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        requeue(batch);
        retryAt = now + RETRY_AFTER;
        if (!error.equals(lastError)) {
            log.warn("Relay feed: {} refused {} files ({}); retried in {} s", uri, batch.size(), error, RETRY_AFTER / 1000);
        }
        lastError = error;
        synchronized (lock) {
            failures++;
        }
        return Flush.FAILED;
    }

    /** A failed batch goes back to the head of the queue, before the files queued since. */
    private void requeue(List<FileMetadata> batch) {
        synchronized (lock) {
            for (int i = batch.size() - 1; i >= 0; i--) queue.addFirst(batch.get(i));
            while (queue.size() > capacity) {
                queue.pollFirst();
                dropped++;
            }
        }
    }

    /** {@code <count 4>(<hash 16><ID 4><port 2><tags>)[count]}. */
    static byte[] encode(List<FileMetadata> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(files.size() * 96);
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(files.size()).array());
        ByteBuffer item = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN);
        for (FileMetadata file : files) {
            item.clear();
            item.put(HandlerUtils.hashToBytes(file.hash()));
            item.putInt(0);
            item.putShort((short) 0);
            Tag.writeList(item, tags(file));
            out.write(item.array(), 0, item.position());
        }
        return out.toByteArray();
    }

    /** Name, size, type, the media tags the index keeps, and the source counts. */
    static List<Tag> tags(FileMetadata file) {
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_FILENAME, file.name()));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_FILESIZE, (int) (file.size() & 0xFFFFFFFFL)));
        if (file.size() > 0xFFFFFFFFL) {
            tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_FILESIZE_HI, (int) (file.size() >>> 32)));
        }
        if (file.type() != null && !file.type().isEmpty()) {
            tags.add(new Tag(Tag.TYPE_STRING, Tag.NAME_FILETYPE, file.type()));
        }
        FileMetadata indexed = file.indexed();
        if (indexed.tags() != null) {
            for (Tag tag : indexed.tags()) {
                if (!Tag.NAME_SOURCES.equals(tag.name())) tags.add(tag);
            }
        }
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, Math.max(1, file.sourceCount())));
        int complete = file.completeSourceCount();
        if (complete > 0) tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_COMPLETE_SOURCES, complete));
        return tags;
    }

    static byte[] gzip(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 3 + 64);
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
            zip.write(data);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static Transport httpTransport() {
        // Its own client: no redirect may take the token elsewhere
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return (uri, secret, body) -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + secret)
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            try {
                return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        };
    }

    /** Counters for the admin panel. */
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        URI uri = target.get();
        stats.put("target", uri == null ? "" : uri.toString());
        synchronized (lock) {
            stats.put("queued", queue.size());
            stats.put("offered", offered);
            stats.put("alreadyQueued", skipped);
            stats.put("sent", sent);
            stats.put("batches", batches);
            stats.put("dropped", dropped);
            stats.put("failures", failures);
        }
        stats.put("lastError", lastError);
        stats.put("lastSentAt", lastSentAt);
        return stats;
    }

    /** Files waiting for the relay server. */
    int queued() {
        synchronized (lock) {
            return queue.size();
        }
    }

    /** Tries to post what waits (a few seconds at most), then stops; the files still waiting are lost. */
    @Override
    public void close() {
        if (!running) return;
        long until = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < until && queued() > 0) {
            Flush flush = flushOnce();
            if (flush != Flush.SENT && flush != Flush.MORE) break;
        }
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        Thread current = thread;
        if (current != null) current.interrupt();
        int left = queued();
        if (left > 0) log.warn("Relay feed stopped: {} files not sent to the relay server", left);
    }
}
