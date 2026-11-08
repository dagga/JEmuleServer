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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Relay mode: what the relay server's index holds, as its {@link #PATH} tells it (the same relayClients and
 * relayToken as the feed): its file count, its maxFiles and its file history, so that the relay shows the index the
 * searches use, never its own fallback index. Fetched every {@link #REFRESH_MILLIS}.
 * <p>
 * Body: text lines {@code files <n>}, {@code max <n>}, then {@code <time millis> <files>} by increasing time.
 */
public final class RelayIndexView {
    private static final Logger log = LoggerFactory.getLogger(RelayIndexView.class);
    public static final String PATH = "/api/relay/index";
    /** The relay server records its history every 5 minutes. */
    static final long REFRESH_MILLIS = 300_000;
    /** History asked for, in days (the admin panel shows 30 at most). */
    static final int DAYS = 31;

    /** Gets the body of the relay server's answer (a fake one in the tests). */
    interface Transport {
        String get(URI target, String token) throws IOException;
    }

    /** One fetch of the relay server's index figures. */
    record Snapshot(long files, Long max, long[] times, long[] counts, long fetchedAt) {
        /** Files at {@code time}: the last sample at or before it, null when the history starts later. */
        Long filesAt(long time) {
            int i = Arrays.binarySearch(times, time);
            if (i < 0) i = -i - 2;
            return i < 0 ? null : counts[i];
        }
    }

    private final Supplier<URI> target;
    private final Supplier<String> token;
    private final Transport transport;
    private final LongSupplier clock;
    private volatile Snapshot snapshot;
    private volatile String lastError = "";

    RelayIndexView(Supplier<URI> target, Supplier<String> token) {
        this(target, token, httpTransport(), System::currentTimeMillis);
    }

    RelayIndexView(Supplier<URI> target, Supplier<String> token, Transport transport, LongSupplier clock) {
        this.target = target;
        this.token = token;
        this.transport = transport;
        this.clock = clock;
    }

    /** Fetches the relay server's figures; keeps the previous ones when it cannot answer. */
    void refresh() {
        URI uri = target.get();
        String secret = token.get();
        if (uri == null || secret == null || secret.isEmpty()) {
            snapshot = null;
            return;
        }
        try {
            snapshot = parse(transport.get(uri, secret), clock.getAsLong());
            if (!lastError.isEmpty()) log.info("Relay index: {} answers again", uri);
            lastError = "";
        } catch (IOException | RuntimeException e) {
            String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (!error.equals(lastError)) log.warn("Relay index: {} did not answer ({})", uri, error);
            lastError = error;
        }
    }

    static Snapshot parse(String body, long now) {
        long files = -1;
        Long max = null;
        long[] times = new long[64];
        long[] counts = new long[64];
        int n = 0;
        for (String line : body.split("\n")) {
            String[] parts = line.trim().split(" ");
            if (parts.length != 2) continue;
            switch (parts[0]) {
                case "files" -> files = Long.parseLong(parts[1]);
                case "max" -> max = Long.parseLong(parts[1]);
                default -> {
                    long time = Long.parseLong(parts[0]);
                    if (n > 0 && time <= times[n - 1]) continue; // by increasing time only
                    if (n == times.length) {
                        times = Arrays.copyOf(times, n * 2);
                        counts = Arrays.copyOf(counts, n * 2);
                    }
                    times[n] = time;
                    counts[n++] = Long.parseLong(parts[1]);
                }
            }
        }
        if (files < 0) throw new IllegalArgumentException("no file count in the answer");
        return new Snapshot(files, max, Arrays.copyOf(times, n), Arrays.copyOf(counts, n), now);
    }

    /** The last figures, null before the first answer or without a relay server. */
    Snapshot snapshot() {
        return snapshot;
    }

    /** The relay server's file count, null when unknown. */
    Long files() {
        Snapshot s = snapshot;
        return s == null ? null : s.files();
    }

    String lastError() {
        return lastError;
    }

    private static Transport httpTransport() {
        // Its own client: no redirect may take the token elsewhere
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return (uri, secret) -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + secret)
                    .GET()
                    .build();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        };
    }
}
