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

package org.jemule.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Periodically downloads and safely applies the configured eMule IP filter sources. */
public final class IPFilterUpdater implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(IPFilterUpdater.class);
    private static final int MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);

    private final IPFilter filter;
    private final Path destination;
    private final List<URI> sources;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ipfilter-updater");
        thread.setDaemon(true);
        return thread;
    });

    public IPFilterUpdater(IPFilter filter, Path destination, List<URI> sources) {
        this(filter, destination, sources, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    IPFilterUpdater(IPFilter filter, Path destination, List<URI> sources, HttpClient httpClient) {
        this.filter = filter;
        this.destination = destination;
        this.sources = List.copyOf(sources);
        this.httpClient = httpClient;
    }

    /** Starts an immediate refresh followed by daily updates. */
    public void start() {
        if (sources.isEmpty()) {
            log.warn("IP filter updates are enabled for {}, but no update sources are configured", destination);
            return;
        }
        scheduler.scheduleWithFixedDelay(this::refreshSafely, 0, 1, TimeUnit.DAYS);
    }

    boolean refreshNow() {
        for (URI source : sources) {
            try {
                List<String> lines = download(source);
                if (filter.applyUpdate(destination, lines, source.toString())) return true;
            } catch (IOException | InterruptedException | IllegalArgumentException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                String detail = "Failed to update from " + source + ": " + e.getMessage();
                log.warn("{}", detail);
                filter.traceRejectedUpdate(destination, detail);
            }
        }
        return false;
    }

    private void refreshSafely() {
        try {
            refreshNow();
        } catch (RuntimeException e) {
            log.error("Unexpected error updating IP filter {}: {}", destination, e.getMessage(), e);
        }
    }

    private List<String> download(URI source) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(source).timeout(REQUEST_TIMEOUT).GET().build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP status " + response.statusCode());
            }
            byte[] bytes = body.readNBytes(MAX_DOWNLOAD_BYTES + 1);
            if (bytes.length > MAX_DOWNLOAD_BYTES) {
                throw new IOException("Downloaded filter exceeds " + MAX_DOWNLOAD_BYTES + " bytes");
            }
            if (isZip(bytes)) bytes = unzip(bytes);
            return new String(bytes, StandardCharsets.ISO_8859_1).lines().toList();
        }
    }

    private static boolean isZip(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
                && bytes[2] == 3 && bytes[3] == 4;
    }

    private static byte[] unzip(byte[] archive) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                byte[] content = zip.readNBytes(MAX_DOWNLOAD_BYTES + 1);
                if (content.length > MAX_DOWNLOAD_BYTES) {
                    throw new IOException("Uncompressed IP filter exceeds " + MAX_DOWNLOAD_BYTES + " bytes");
                }
                return content;
            }
        }
        throw new IOException("Downloaded ZIP contains no files");
    }

    /** Loads the source URLs bundled with the application. */
    public static List<URI> bundledSources() throws IOException {
        try (InputStream input = IPFilterUpdater.class.getResourceAsStream("/ipfilter.dat.urls")) {
            if (input == null) throw new IOException("Missing bundled resource ipfilter.dat.urls");
            List<URI> sources = new ArrayList<>();
            for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) continue;
                URI uri = URI.create(value);
                String scheme = uri.getScheme();
                if (scheme == null || !scheme.equalsIgnoreCase("https")) {
                    throw new IOException("Unsupported IP filter update URL: " + value);
                }
                sources.add(uri);
            }
            return List.copyOf(sources);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
