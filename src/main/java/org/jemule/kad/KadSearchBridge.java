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

import org.jemule.core.FileMetadata;
import org.jemule.core.Keywords;
import org.jemule.core.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jemule.security.ChildAbuseFilter;
import org.jemule.security.FakeFileDetector;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Asynchronous search bridge that forks incoming ed2k search queries to the Kademlia DHT.
 * Translates queries into Kad keyword lookups and dispatches SEARCH_KEY_REQ packets,
 * while enforcing strict "Secure by Construction" protective filtering on all results.
 */
public class KadSearchBridge implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(KadSearchBridge.class);

    public static final int DEFAULT_ALPHA_NODES = 3;
    public static final long DEFAULT_SEARCH_TIMEOUT_MS = 4000;

    private final KadRoutingTable routingTable;
    private final KadUdpService udpService;
    private final ChildAbuseFilter abuseFilter;
    private final FakeFileDetector fakeDetector;
    private final int alphaNodes;
    private final long searchTimeoutMs;

    private final ConcurrentHashMap<KadId, KadSearchSession> activeSessions = new ConcurrentHashMap<>();
    private final Set<String> bannedHashes = ConcurrentHashMap.newKeySet();
    private final List<IllicitFileListener> illicitListeners = new CopyOnWriteArrayList<>();

    private final AtomicLong searchesForked = new AtomicLong(0);
    private final AtomicLong resultsReceived = new AtomicLong(0);
    private final AtomicLong illicitFilesBlocked = new AtomicLong(0);

    private volatile boolean running = false;

    @FunctionalInterface
    public interface IllicitFileListener {
        void onIllicitFile(FileMetadata file, String reason);
    }

    public static class KadSearchSession {
        private final KadId targetId;
        private final String keyword;
        private final long createdAt;
        private final List<FileMetadata> results = new CopyOnWriteArrayList<>();
        private final CompletableFuture<List<FileMetadata>> future = new CompletableFuture<>();

        public KadSearchSession(KadId targetId, String keyword) {
            this.targetId = targetId;
            this.keyword = keyword;
            this.createdAt = System.currentTimeMillis();
        }

        public KadId targetId() {
            return targetId;
        }

        public String keyword() {
            return keyword;
        }

        public long createdAt() {
            return createdAt;
        }

        public List<FileMetadata> getResults() {
            return Collections.unmodifiableList(results);
        }

        public void addResult(FileMetadata meta) {
            results.add(meta);
        }

        public CompletableFuture<List<FileMetadata>> future() {
            return future;
        }

        public void complete() {
            future.complete(new ArrayList<>(results));
        }
    }

    public KadSearchBridge(KadRoutingTable routingTable, KadUdpService udpService,
                           ChildAbuseFilter abuseFilter, FakeFileDetector fakeDetector,
                           int alphaNodes, long searchTimeoutMs) {
        this.routingTable = Objects.requireNonNull(routingTable, "routingTable cannot be null");
        this.udpService = Objects.requireNonNull(udpService, "udpService cannot be null");
        this.abuseFilter = abuseFilter != null ? abuseFilter : ChildAbuseFilter.defaults();
        this.fakeDetector = fakeDetector != null ? fakeDetector : new FakeFileDetector();
        this.alphaNodes = Math.max(1, alphaNodes);
        this.searchTimeoutMs = searchTimeoutMs;
    }

    public KadSearchBridge(KadRoutingTable routingTable, KadUdpService udpService,
                           ChildAbuseFilter abuseFilter, FakeFileDetector fakeDetector) {
        this(routingTable, udpService, abuseFilter, fakeDetector, DEFAULT_ALPHA_NODES, DEFAULT_SEARCH_TIMEOUT_MS);
    }

    public KadSearchBridge(KadRoutingTable routingTable, KadUdpService udpService, int alphaNodes, long searchTimeoutMs) {
        this(routingTable, udpService, ChildAbuseFilter.defaults(), new FakeFileDetector(), alphaNodes, searchTimeoutMs);
    }

    public KadSearchBridge(KadRoutingTable routingTable, KadUdpService udpService) {
        this(routingTable, udpService, DEFAULT_ALPHA_NODES, DEFAULT_SEARCH_TIMEOUT_MS);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        udpService.registerListener(KadOpCode.SEARCH_RES, this::handleSearchRes);
        log.info("KadSearchBridge started (alpha: {}, timeout: {}ms)", alphaNodes, searchTimeoutMs);
    }

    /**
     * Extracts the primary text keyword from a SearchQuery tree.
     */
    public static String extractKeyword(SearchQuery query) {
        if (query == null) return null;
        if (query instanceof SearchQuery.TermQuery t && t.id() == SearchQuery.ID_FILENAME) {
            return t.term();
        }
        if (query instanceof SearchQuery.AndQuery and) {
            String left = extractKeyword(and.left());
            if (left != null && !left.isBlank()) return left;
            return extractKeyword(and.right());
        }
        if (query instanceof SearchQuery.OrQuery or) {
            String left = extractKeyword(or.left());
            if (left != null && !left.isBlank()) return left;
            return extractKeyword(or.right());
        }
        if (query instanceof SearchQuery.NotQuery not) {
            return extractKeyword(not.left());
        }
        return null;
    }

    /**
     * Forks a SearchQuery to the Kademlia DHT asynchronously.
     *
     * @param query The parsed SearchQuery.
     * @return A CompletableFuture providing discovered files, or completing with an empty list if not applicable.
     */
    public CompletableFuture<List<FileMetadata>> forkSearch(SearchQuery query) {
        String keyword = extractKeyword(query);
        return forkSearch(keyword);
    }

    /**
     * Forks a search query by raw keyword text.
     */
    public CompletableFuture<List<FileMetadata>> forkSearch(String keyword) {
        if (!running || keyword == null || keyword.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }

        String[] tokens = Keywords.split(keyword);
        if (tokens.length == 0) {
            return CompletableFuture.completedFuture(List.of());
        }

        // Use the first significant token (length >= 3 preferred)
        String primaryToken = tokens[0];
        for (String t : tokens) {
            if (t.length() >= 3) {
                primaryToken = t;
                break;
            }
        }

        KadId keywordHash = KadId.fromKeyword(primaryToken);

        // Check if an existing active search session is already running for this keyword hash
        KadSearchSession session = activeSessions.computeIfAbsent(keywordHash, k -> {
            KadSearchSession s = new KadSearchSession(k, keyword);
            dispatchSearchRequest(s);
            // Schedule completion after searchTimeoutMs
            Thread.ofVirtual().name("KadSearchTimeout-" + k.toHexString()).start(() -> {
                try {
                    Thread.sleep(searchTimeoutMs);
                } catch (InterruptedException ignored) {
                } finally {
                    s.complete();
                    activeSessions.remove(k);
                }
            });
            return s;
        });

        searchesForked.incrementAndGet();
        return session.future();
    }

    private void dispatchSearchRequest(KadSearchSession session) {
        List<KadContact> targetNodes = routingTable.findClosest(session.targetId(), alphaNodes);
        if (targetNodes.isEmpty()) {
            log.debug("No close Kad contacts available in routing table to query for '{}'", session.keyword());
            return;
        }

        KadPacket req = KadPacket.createSearchKeyReq(session.targetId(), 0);
        for (KadContact contact : targetNodes) {
            try {
                udpService.send(req, contact.address(), contact.udpPort());
                log.debug("Sent KADEMLIA2_SEARCH_KEY_REQ for '{}' ({}) to {}:{}",
                        session.keyword(), session.targetId(), contact.address().getHostAddress(), contact.udpPort());
            } catch (IOException e) {
                log.debug("Failed to send search request to {}:{} - {}", contact.address().getHostAddress(), contact.udpPort(), e.getMessage());
            }
        }
    }

    protected void handleSearchRes(KadPacket packet, InetAddress remoteAddress, int remotePort) {
        KadPacket.SearchRes parsed = KadPacket.parseSearchRes(packet.data());
        if (parsed == null) return;

        KadSearchSession session = activeSessions.get(parsed.targetId());
        if (session == null) {
            log.debug("Received SEARCH_RES for unknown or expired target: {}", parsed.targetId());
            return;
        }

        List<FileMetadata> files = parsed.files();
        resultsReceived.addAndGet(files.size());
        log.debug("Received SEARCH_RES with {} files from {}:{} for target {}",
                files.size(), remoteAddress.getHostAddress(), remotePort, parsed.targetId());

        for (FileMetadata file : files) {
            if (acceptFile(file)) {
                session.addResult(file);
            }
        }
    }

    /**
     * Hook to validate incoming files before ingestion into session results.
     * Evaluates against banned hashes, ChildAbuseFilter, and FakeFileDetector.
     */
    protected boolean acceptFile(FileMetadata file) {
        if (file == null || file.hash() == null) {
            return false;
        }

        String upperHash = file.hash().toUpperCase(Locale.ROOT);

        // 1. Check banned hashes
        if (bannedHashes.contains(upperHash)) {
            log.debug("Kad file rejected (blacklisted hash): {} ({})", file.name(), file.hash());
            illicitFilesBlocked.incrementAndGet();
            return false;
        }

        // 2. ChildAbuseFilter screening
        if (abuseFilter != null && abuseFilter.matches(file.name())) {
            log.warn("Kad file rejected by ChildAbuseFilter: {} ({})", file.name(), file.hash());
            illicitFilesBlocked.incrementAndGet();
            bannedHashes.add(upperHash);
            notifyIllicitFile(file, ChildAbuseFilter.BAN_REASON);
            return false;
        }

        // 3. FakeFileDetector screening
        if (fakeDetector != null && fakeDetector.isFake(file.hash(), file.name(), file.size())) {
            log.info("Kad file rejected by FakeFileDetector: {} ({})", file.name(), file.hash());
            illicitFilesBlocked.incrementAndGet();
            return false;
        }

        return true;
    }

    private void notifyIllicitFile(FileMetadata file, String reason) {
        for (IllicitFileListener listener : illicitListeners) {
            try {
                listener.onIllicitFile(file, reason);
            } catch (Exception e) {
                log.error("Error notifying illicit file listener for {}: {}", file.hash(), e.getMessage());
            }
        }
    }

    public void banHash(String hash) {
        if (hash != null) {
            bannedHashes.add(hash.toUpperCase(Locale.ROOT));
        }
    }

    public boolean isHashBanned(String hash) {
        return hash != null && bannedHashes.contains(hash.toUpperCase(Locale.ROOT));
    }

    public void addIllicitFileListener(IllicitFileListener listener) {
        if (listener != null) {
            illicitListeners.add(listener);
        }
    }

    public void removeIllicitFileListener(IllicitFileListener listener) {
        illicitListeners.remove(listener);
    }

    public long getIllicitFilesBlocked() {
        return illicitFilesBlocked.get();
    }

    public ChildAbuseFilter getAbuseFilter() {
        return abuseFilter;
    }

    public FakeFileDetector getFakeDetector() {
        return fakeDetector;
    }

    public KadSearchSession getSession(KadId targetId) {
        return activeSessions.get(targetId);
    }

    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    public long getSearchesForked() {
        return searchesForked.get();
    }

    public long getResultsReceived() {
        return resultsReceived.get();
    }

    @Override
    public synchronized void close() {
        running = false;
        activeSessions.values().forEach(KadSearchSession::complete);
        activeSessions.clear();
        log.info("KadSearchBridge stopped");
    }
}
