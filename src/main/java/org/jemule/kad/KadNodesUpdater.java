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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads a {@code nodes.dat} contact list from the configured sources and uses it to bootstrap the Kad node.
 * <p>
 * The behaviour mirrors the IP filter updater: an immediate download at startup, a daily refresh, a fallback on
 * the last successfully downloaded list when every source is unreachable, and an atomic replacement of the cache.
 * The contacts are only used as bootstrap targets; the routing table is filled by their answers, with the correct
 * wire identifiers.
 */
public final class KadNodesUpdater implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(KadNodesUpdater.class);
    private static final int MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024;
    private static final long REFRESH_PERIOD_HOURS = 24;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);

    private final KadBootstrapManager bootstrapManager;
    private final Path cache;
    private final List<URI> sources;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler =
            org.jemule.core.ServerThreads.maintenanceScheduler("kad-nodes-updater");

    private volatile long lastRefresh;
    private volatile int lastContactCount;
    private volatile String lastSource = "";

    public KadNodesUpdater(KadBootstrapManager bootstrapManager, Path cache, List<URI> sources) {
        this(bootstrapManager, cache, sources, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    KadNodesUpdater(KadBootstrapManager bootstrapManager, Path cache, List<URI> sources, HttpClient httpClient) {
        this.bootstrapManager = bootstrapManager;
        this.cache = cache;
        this.sources = List.copyOf(sources);
        this.httpClient = httpClient;
    }

    /** Starts an immediate refresh followed by daily updates. */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::refreshSafely, 0, REFRESH_PERIOD_HOURS, TimeUnit.HOURS);
    }

    /**
     * Downloads the first usable contact list, or falls back on the cached one.
     *
     * @return whether a list was loaded and handed to the bootstrap manager
     */
    public boolean refreshNow() {
        for (URI source : sources) {
            try {
                byte[] bytes = download(source);
                List<KadContact> contacts = KadNodesFile.parse(bytes);
                if (contacts.isEmpty()) {
                    throw new IllegalArgumentException("no usable Kad contact");
                }
                saveCache(bytes);
                apply(contacts, source.toString());
                return true;
            } catch (IOException | InterruptedException | IllegalArgumentException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("Failed to update Kad nodes from {}: {}", source, e.getMessage());
            }
        }
        return loadCache();
    }

    private void refreshSafely() {
        try {
            refreshNow();
        } catch (RuntimeException e) {
            log.error("Unexpected error updating the Kad nodes list: {}", e.getMessage(), e);
        }
    }

    private void apply(List<KadContact> contacts, String source) {
        int queued = bootstrapManager.bootstrapFromNodes(contacts);
        lastRefresh = System.currentTimeMillis();
        lastContactCount = contacts.size();
        lastSource = source;
        log.info("Loaded {} Kad contacts from {} ({} bootstrap requests queued)", contacts.size(), source, queued);
    }

    private boolean loadCache() {
        if (cache == null || !Files.isRegularFile(cache)) return false;
        try {
            List<KadContact> contacts = KadNodesFile.read(cache);
            if (contacts.isEmpty()) return false;
            apply(contacts, cache.toString());
            log.info("Using the cached Kad nodes list {} ({} contacts)", cache, contacts.size());
            return true;
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Cannot read the cached Kad nodes list {}: {}", cache, e.getMessage());
            return false;
        }
    }

    private void saveCache(byte[] bytes) {
        if (cache == null) return;
        try {
            Path parent = cache.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, cache.getFileName().toString(), ".tmp");
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Cannot cache the Kad nodes list {}: {}", cache, e.getMessage());
        }
    }

    private byte[] download(URI source) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(source).timeout(REQUEST_TIMEOUT).GET().build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP status " + response.statusCode());
            }
            byte[] bytes = body.readNBytes(MAX_DOWNLOAD_BYTES + 1);
            if (bytes.length > MAX_DOWNLOAD_BYTES) {
                throw new IOException("Downloaded nodes.dat exceeds " + MAX_DOWNLOAD_BYTES + " bytes");
            }
            if (isGzip(bytes)) bytes = gunzip(bytes);
            if (isZip(bytes)) bytes = unzip(bytes);
            return bytes;
        }
    }

    private static boolean isGzip(byte[] bytes) {
        return bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
    }

    private static boolean isZip(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K' && bytes[2] == 3 && bytes[3] == 4;
    }

    private static byte[] gunzip(byte[] archive) throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(archive))) {
            return readBounded(gzip);
        }
    }

    private static byte[] unzip(byte[] archive) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory()) return readBounded(zip);
            }
        }
        throw new IOException("Downloaded ZIP contains no files");
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        byte[] content = input.readNBytes(MAX_DOWNLOAD_BYTES + 1);
        if (content.length > MAX_DOWNLOAD_BYTES) {
            throw new IOException("Uncompressed nodes.dat exceeds " + MAX_DOWNLOAD_BYTES + " bytes");
        }
        return content;
    }

    /** Timestamp of the last list applied to the bootstrap manager, or 0 before the first success. */
    public long getLastRefresh() {
        return lastRefresh;
    }

    /** Number of contacts of the last applied list. */
    public int getLastContactCount() {
        return lastContactCount;
    }

    /** Source (URL or cache file) of the last applied list. */
    public String getLastSource() {
        return lastSource;
    }

    public Path getCache() {
        return cache;
    }

    public List<URI> getSources() {
        return sources;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
