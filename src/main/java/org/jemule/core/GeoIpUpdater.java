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

package org.jemule.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * Keeps the {@link GeoIp} table of the web panel: read from its cache at start, downloaded again when the cache is
 * older than {@link #MAX_AGE}, checked daily. The source URL may hold {@code {month}}, replaced by the current month
 * (yyyy-MM) and, when that file is not published yet, by the month before. Without a cache (database in memory) the
 * file is kept in a temporary file only while it is read.
 */
public final class GeoIpUpdater implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(GeoIpUpdater.class);
    /** DB-IP publishes a new file at the start of each month. */
    static final Duration MAX_AGE = Duration.ofDays(30);
    /** Largest file downloaded (DB-IP's gzipped CSV is about 5 MB). */
    static final long MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);

    private final String url;
    private final Path cache;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler = ServerThreads.maintenanceScheduler("geoip-updater");
    private volatile GeoIp table;

    public GeoIpUpdater(String url, Path cache) {
        this(url, cache, SharedHttpClient.get());
    }

    GeoIpUpdater(String url, Path cache, HttpClient httpClient) {
        this.url = url;
        this.cache = cache;
        this.httpClient = httpClient;
    }

    /** Reads the cache, then downloads the table when it is missing or old, and checks again every day. */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::refreshSafely, 0, 24, TimeUnit.HOURS);
    }

    /** The country code of an address, or null when unknown or while the table is not loaded. */
    public String country(InetAddress address) {
        GeoIp current = table;
        return current == null ? null : current.country(address);
    }

    GeoIp table() {
        return table;
    }

    private void refreshSafely() {
        try {
            refreshNow(System.currentTimeMillis());
        } catch (RuntimeException e) {
            log.warn("Country database update failed: {}", e.getMessage(), e);
        }
    }

    /** Loads the cache when nothing is loaded yet, then downloads a newer table when the cache is missing or old. */
    void refreshNow(long now) {
        if (table == null && cache != null && Files.isRegularFile(cache)) {
            try {
                table = load(cache);
                log.info("Country database: {} ranges from {}", table.size(), cache);
            } catch (IOException e) {
                log.warn("Cannot read the cached country database {}: {}", cache, e.getMessage());
            }
        }
        if (table != null && !stale(now)) return;
        YearMonth month = YearMonth.from(java.time.Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC));
        List<YearMonth> months = url.contains("{month}") ? List.of(month, month.minusMonths(1)) : List.of(month);
        for (YearMonth m : months) {
            String source = url.replace("{month}", m.toString());
            try {
                download(URI.create(source));
                log.info("Country database: {} ranges from {}", table.size(), source);
                return;
            } catch (IOException | InterruptedException | IllegalArgumentException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("Cannot download the country database {}: {}", source, e.getMessage());
            }
        }
    }

    private boolean stale(long now) {
        if (cache == null) return false; // downloaded once per start
        try {
            return !Files.isRegularFile(cache)
                    || now - Files.getLastModifiedTime(cache).toMillis() > MAX_AGE.toMillis();
        } catch (IOException e) {
            return true;
        }
    }

    private void download(URI source) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(source).timeout(REQUEST_TIMEOUT).GET().build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        Path directory = cache == null ? null : cache.toAbsolutePath().getParent();
        if (directory != null) Files.createDirectories(directory);
        Path temporary = directory == null ? Files.createTempFile("geoip", ".tmp")
                : Files.createTempFile(directory, cache.getFileName().toString(), ".tmp");
        try {
            try (InputStream body = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("HTTP status " + response.statusCode());
                }
                copyBounded(body, temporary);
            }
            GeoIp loaded = load(temporary);
            if (cache != null) {
                try {
                    Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    Files.move(temporary, cache, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            table = loaded;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void copyBounded(InputStream in, Path target) throws IOException {
        try (var out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_DOWNLOAD_BYTES) throw new IOException("larger than " + MAX_DOWNLOAD_BYTES + " bytes");
                out.write(buffer, 0, read);
            }
        }
    }

    /** Reads a table file, gzipped or not. */
    static GeoIp load(Path file) throws IOException {
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file))) {
            raw.mark(2);
            int b1 = raw.read(), b2 = raw.read();
            raw.reset();
            InputStream in = b1 == 0x1F && b2 == 0x8B ? new GZIPInputStream(raw, 64 * 1024) : raw;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
                return GeoIp.read(reader);
            }
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
