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
import org.jemule.network.handler.FoundSource;
import org.jemule.security.FakeFileDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Hands over to another eD2K server (the relay server, {@code relayServer}) once this server's index is full. The
 * clients stay connected here and get the relay server in their server list at login; their searches and source
 * requests, and the UDP global searches of other servers' clients, are played on the relay server through client
 * sessions of this server (TCP, LowID), and the answers return to them. This server then only acts as a proxy for
 * the relay server.
 */
public final class ServerRelay implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ServerRelay.class);
    static final String CLIENT_NAME = "JEmuleServer relay";
    /** Searches played on the relay server at the same time: one session each. */
    static final int SESSIONS = 4;
    /** Pages of 300 results asked per client search: the first answer and two "More", as an eMule client would. */
    public static final int MAX_PAGES = 3;
    /** Time a login, a search or a page may take. */
    static final int ANSWER_TIMEOUT = 10_000;
    /** Time without a new session after the relay server could not be reached or warned. */
    static final long RETRY_AFTER = 30_000;
    private static final int MAX_QUERY_BYTES = 1024;

    /** Opens a session on the relay server (a fake one in the tests). */
    interface Connector {
        Session open(ServerGossip.Endpoint endpoint, byte[] userHash) throws IOException;
    }

    /** A logged-in session on the relay server. */
    interface Session extends AutoCloseable {
        ServerUpdateSession.Answer search(byte[] tree) throws IOException;

        ServerUpdateSession.Answer more() throws IOException;

        byte[] sources(byte[] request) throws IOException;

        @Override
        void close();
    }

    private final Supplier<String> relayServer;
    private final BooleanSupplier full;
    private final FileIndex fileIndex;
    private final FakeFileDetector fakeDetector;
    private final Connector connector;
    private final LongSupplier clock;
    private final ExecutorService executor;
    private final ConcurrentLinkedQueue<Session> idle = new ConcurrentLinkedQueue<>();
    private final byte[] userHash = userHash();
    private volatile boolean running = true;
    private volatile long retryAt;
    private volatile String parsedFrom;
    private volatile ServerGossip.Endpoint endpoint;

    private final AtomicLong searches = new AtomicLong();
    private final AtomicLong pages = new AtomicLong();
    private final AtomicLong results = new AtomicLong();
    private final AtomicLong sourceRequests = new AtomicLong();
    private final AtomicLong sourcesFound = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong announced = new AtomicLong();

    ServerRelay(Supplier<String> relayServer, BooleanSupplier full, FileIndex fileIndex, FakeFileDetector fakeDetector) {
        this(relayServer, full, fileIndex, fakeDetector, (endpoint, hash) -> {
            ServerUpdateSession session = ServerUpdateSession.open(endpoint, hash, CLIENT_NAME, ANSWER_TIMEOUT);
            return new Session() {
                public ServerUpdateSession.Answer search(byte[] tree) throws IOException {
                    return session.search(tree);
                }

                public ServerUpdateSession.Answer more() throws IOException {
                    return session.more();
                }

                public byte[] sources(byte[] request) throws IOException {
                    return session.sources(request);
                }

                public void close() {
                    session.close();
                }
            };
        }, System::currentTimeMillis);
    }

    ServerRelay(Supplier<String> relayServer, BooleanSupplier full, FileIndex fileIndex, FakeFileDetector fakeDetector,
                Connector connector, LongSupplier clock) {
        this.relayServer = relayServer;
        this.full = full;
        this.fileIndex = fileIndex;
        this.fakeDetector = fakeDetector;
        this.connector = connector;
        this.clock = clock;
        this.executor = Executors.newFixedThreadPool(SESSIONS, ServerThreads.factory(ServerThreads.Tier.SEARCH, "relay"));
    }

    /** The relay server, or null when none is configured or its address does not resolve to IPv4. */
    ServerGossip.Endpoint endpoint() {
        String value = relayServer.get();
        if (value == null || value.isBlank()) return null;
        if (!value.equals(parsedFrom)) {
            ServerGossip.Endpoint parsed = null;
            try {
                int colon = value.lastIndexOf(':');
                parsed = new ServerGossip.Endpoint(InetAddress.getByName(value.substring(0, colon).trim()),
                        Integer.parseInt(value.substring(colon + 1).trim()));
            } catch (IOException | RuntimeException e) {
                log.warn("relayServer {} is not usable: {}", value, e.getMessage());
            }
            endpoint = parsed;
            parsedFrom = value;
        }
        return endpoint;
    }

    /** True while the index is full and a relay server is configured: the requests are played there. */
    public boolean active() {
        return running && full.getAsBoolean() && endpoint() != null;
    }

    /** True when {@code address} is the relay server's: its own requests are never played back on it. */
    public boolean isRelayServer(InetAddress address) {
        ServerGossip.Endpoint relay = endpoint();
        return relay != null && relay.address().equals(address);
    }

    /**
     * The relay server as {@code <IP 4><port 2>}, for the OP_SERVERLIST sent to a client at login while
     * {@link #active()}; empty otherwise.
     */
    public byte[] announce() {
        if (!active()) return new byte[0];
        announced.incrementAndGet();
        return serverListEntry();
    }

    private byte[] serverListEntry() {
        ServerGossip.Endpoint relay = endpoint();
        if (relay == null) return new byte[0];
        byte[] ip = relay.address().getAddress();
        return new byte[]{ip[0], ip[1], ip[2], ip[3], (byte) relay.tcpPort(), (byte) (relay.tcpPort() >>> 8)};
    }

    /** The relay server as {@code IP:port}, for the messages to the clients. */
    public String describe() {
        ServerGossip.Endpoint relay = endpoint();
        return relay == null ? "" : relay.address().getHostAddress() + ":" + relay.tcpPort();
    }

    /**
     * Plays a search on the relay server while {@link #active()}; the future gets the clean files of up to
     * {@code maxPages} pages of 300, or nothing when the relay server cannot be reached.
     *
     * @return null when the relay is not active
     */
    public CompletableFuture<List<FileMetadata>> forkSearch(byte[] searchTree, int maxPages) {
        if (!active() || searchTree == null || searchTree.length == 0 || searchTree.length > MAX_QUERY_BYTES) return null;
        searches.incrementAndGet();
        return submit(session -> search(session, searchTree, maxPages), List.of());
    }

    /**
     * Asks the relay server for the sources of a file while {@link #active()} (the payload of the client's
     * OP_GETSOURCES); the future gets its HighID sources, the only ones a client of this server can reach.
     *
     * @return null when the relay is not active
     */
    public CompletableFuture<List<FoundSource>> forkSources(byte[] request) {
        if (!active() || request == null || request.length < 20) return null;
        sourceRequests.incrementAndGet();
        return submit(session -> {
            List<FoundSource> found = highIds(session.sources(request));
            sourcesFound.addAndGet(found.size());
            return found;
        }, List.of());
    }

    /** {@code <hash 16><count 1>(<ID 4><port 2>)*}: the sources with an IP as ID. */
    static List<FoundSource> highIds(byte[] foundSources) {
        List<FoundSource> sources = new ArrayList<>();
        if (foundSources == null || foundSources.length < 17) return sources;
        java.nio.ByteBuffer input = java.nio.ByteBuffer.wrap(foundSources).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        input.position(16);
        int count = Math.min(input.get() & 0xFF, input.remaining() / 6);
        for (int i = 0; i < count; i++) {
            long id = Integer.toUnsignedLong(input.getInt());
            int port = Short.toUnsignedInt(input.getShort());
            if (id > 0x00FFFFFFL && port > 0) sources.add(new FoundSource(id, port, 0, null));
        }
        return sources;
    }

    /** One request on the relay server. */
    private interface Request<T> {
        T run(Session session) throws IOException;
    }

    private <T> CompletableFuture<T> submit(Request<T> request, T none) {
        if (clock.getAsLong() < retryAt) return CompletableFuture.completedFuture(none);
        try {
            return CompletableFuture.supplyAsync(() -> play(request, none), executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(none);
        }
    }

    /** Runs a request on an idle session, or a new one; a session the relay server closed is opened again once. */
    private <T> T play(Request<T> request, T none) {
        ServerGossip.Endpoint relay = endpoint();
        if (relay == null) return none;
        for (int attempt = 0; attempt < 2; attempt++) {
            Session session = idle.poll();
            boolean fresh = session == null;
            try {
                if (fresh) session = connector.open(relay, userHash);
                T answer = request.run(session);
                idle.add(session);
                return answer;
            } catch (IOException e) {
                if (session != null) session.close();
                if (fresh || e instanceof ServerUpdateSession.WarningException) {
                    failures.incrementAndGet();
                    retryAt = clock.getAsLong() + RETRY_AFTER;
                    log.warn("Relay server {} failed: {}; requests stay local for {} s", relay, e.getMessage(),
                            RETRY_AFTER / 1000);
                    return none;
                }
            }
        }
        return none;
    }

    /** The clean files of up to {@code maxPages} pages of a search. */
    private List<FileMetadata> search(Session session, byte[] searchTree, int maxPages) throws IOException {
        Map<String, FileMetadata> files = new LinkedHashMap<>();
        ServerUpdateSession.Answer answer = session.search(searchTree);
        for (int page = 1; ; page++) {
            pages.incrementAndGet();
            List<FileMetadata> decoded = ServerSearchBridge.decodeSearchResult(answer.payload());
            if (decoded != null) decoded.forEach(file -> files.putIfAbsent(file.hash().toUpperCase(Locale.ROOT), file));
            if (page >= maxPages || decoded == null || !ServerSearchBridge.moreAvailable(answer.payload())) break;
            answer = session.more();
        }
        List<FileMetadata> kept = clean(files.values());
        results.addAndGet(kept.size());
        return kept;
    }

    /** Files clean of child abuse and fakes: the only ones a client may see. */
    private List<FileMetadata> clean(java.util.Collection<FileMetadata> files) {
        List<FileMetadata> kept = new ArrayList<>();
        for (FileMetadata file : files) {
            if (fileIndex.isChildAbuse(file.hash(), file.name())) continue;
            if (fakeDetector != null && fakeDetector.isFake(file.hash(), file.name(), file.size())) continue;
            kept.add(file);
        }
        return kept;
    }

    private static byte[] userHash() {
        byte[] hash = new byte[16];
        new SecureRandom().nextBytes(hash);
        hash[5] = 14; // eMule's user hash markers
        hash[14] = 111;
        return hash;
    }

    /** Counters for the admin panel. */
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("server", describe());
        stats.put("active", active());
        stats.put("searches", searches.get());
        stats.put("pages", pages.get());
        stats.put("results", results.get());
        stats.put("sourceRequests", sourceRequests.get());
        stats.put("sourcesFound", sourcesFound.get());
        stats.put("failures", failures.get());
        stats.put("announcedLogins", announced.get());
        stats.put("sessions", idle.size());
        return stats;
    }

    @Override
    public void close() {
        running = false;
        executor.shutdownNow();
        Session session;
        while ((session = idle.poll()) != null) session.close();
    }
}
