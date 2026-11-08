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

import org.jemule.config.RelayFeedConfig;
import org.jemule.core.FileIndex;
import org.jemule.core.FileMetadata;
import org.jemule.core.FileSource;
import org.jemule.core.IngestionExecutor;
import org.jemule.security.AdminAccessFilter;
import org.jemule.security.FakeFileDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;

/**
 * Receiving side of the relay mode: the files a relay server posts to {@link RelayFeed#PATH}. Only the addresses of
 * {@code relayClients} with the shared {@code relayToken} may post; nothing else of the admin panel is open to them.
 * The files clean of child abuse and fakes are indexed on the ingestion thread, as the files found by a search.
 */
final class RelayFeedReceiver {
    private static final Logger log = LoggerFactory.getLogger(RelayFeedReceiver.class);
    /** Largest body accepted, compressed and uncompressed: a batch of {@link RelayFeed#BATCH} files is far below. */
    static final int MAX_BODY_BYTES = 16 * 1024 * 1024;
    static final int MAX_FILES_BYTES = 32 * 1024 * 1024;

    /** An answer: its HTTP status and JSON body. */
    record Answer(int status, Map<String, Object> body) {
    }

    private final Supplier<RelayFeedConfig> config;
    private final FileIndex fileIndex;
    private final FakeFileDetector fakeDetector;
    private final IngestionExecutor ingestion;
    private volatile String filterOf;
    private volatile AdminAccessFilter filter;

    private final AtomicLong batches = new AtomicLong();
    private final AtomicLong files = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong denied = new AtomicLong();
    private final AtomicLong busy = new AtomicLong();
    private volatile String lastFrom = "";
    private volatile long lastAt;

    RelayFeedReceiver(Supplier<RelayFeedConfig> config, FileIndex fileIndex, FakeFileDetector fakeDetector,
                      IngestionExecutor ingestion) {
        this.config = config;
        this.fileIndex = fileIndex;
        this.fakeDetector = fakeDetector;
        this.ingestion = ingestion;
    }

    /** Whether {@code address} is one of the relay servers of {@code relayClients}. */
    boolean allowed(InetAddress address) {
        String clients = config.get().clients();
        if (clients.isEmpty() || address == null) return false; // an empty list allows nobody, not the loopback
        if (!clients.equals(filterOf)) {
            filter = new AdminAccessFilter(clients, "", ignored -> null);
            filterOf = clients;
        }
        return filter.isAllowed(address);
    }

    /** Checks the sender and the token, then queues the files of the body for indexing. */
    Answer receive(InetAddress from, String authorization, InputStream body) throws IOException {
        String token = config.get().effectiveToken();
        if (token.isEmpty() || !allowed(from) || !validToken(authorization, token)) {
            if (denied.incrementAndGet() % 100 == 1) {
                log.warn("Relay feed refused from {} (not in relayClients, or wrong relayToken)",
                        from == null ? "?" : from.getHostAddress());
            }
            return new Answer(403, Map.of("error", "Not an allowed relay server"));
        }
        byte[] payload = gunzip(body);
        if (payload == null) return new Answer(413, Map.of("error", "Body too large"));
        List<FileMetadata> decoded = ServerSearchBridge.decodeSearchResult(payload);
        if (decoded == null) return new Answer(400, Map.of("error", "Malformed file list"));
        List<FileMetadata> kept = new ArrayList<>(decoded.size());
        for (FileMetadata file : decoded) {
            if (fileIndex.isChildAbuse(file.hash(), file.name())
                    || (fakeDetector != null && fakeDetector.isFake(file.hash(), file.name(), file.size()))) {
                rejected.incrementAndGet();
            } else {
                kept.add(file);
            }
        }
        if (!kept.isEmpty() && !ingestion.offer(() -> fileIndex.addFiles(kept, FileSource.RELAY_FEED))) {
            // The relay server keeps the batch and posts it again later
            busy.incrementAndGet();
            return new Answer(503, Map.of("error", "Ingestion queue full"));
        }
        batches.incrementAndGet();
        files.addAndGet(kept.size());
        lastFrom = from.getHostAddress();
        lastAt = System.currentTimeMillis();
        return new Answer(200, Map.of("received", decoded.size(), "accepted", kept.size()));
    }

    private static boolean validToken(String authorization, String token) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return false;
        byte[] given = authorization.substring("Bearer ".length()).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, token.getBytes(StandardCharsets.UTF_8));
    }

    /** The uncompressed body, or null when it is larger than allowed. */
    private static byte[] gunzip(InputStream body) throws IOException {
        byte[] compressed = readAtMost(body, MAX_BODY_BYTES);
        if (compressed == null) return null;
        try (GZIPInputStream zip = new GZIPInputStream(new java.io.ByteArrayInputStream(compressed))) {
            return readAtMost(zip, MAX_FILES_BYTES);
        } catch (java.util.zip.ZipException | java.io.EOFException e) {
            return new byte[0]; // not gzip: rejected as a malformed list
        }
    }

    private static byte[] readAtMost(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > max) return null;
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** Counters for the admin panel. */
    Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("clients", config.get().clients());
        stats.put("batches", batches.get());
        stats.put("files", files.get());
        stats.put("rejected", rejected.get());
        stats.put("denied", denied.get());
        stats.put("busy", busy.get());
        stats.put("lastFrom", lastFrom);
        stats.put("lastAt", lastAt);
        return stats;
    }
}
