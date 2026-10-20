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

import org.jemule.core.DatabaseManager;
import org.jemule.core.FileIndex;
import org.jemule.core.FileSource;
import org.jemule.core.FileMetadata;
import org.jemule.core.Keywords;
import org.jemule.core.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.jemule.security.ChildAbuseFilter;
import org.jemule.security.FakeFileDetector;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** KADEMLIA_FIND_VALUE: asks the peer for the contacts closest to a key. */
    private static final byte FIND_VALUE_TYPE = 0x02;
    /** Parallel queries per lookup round (eMule's ALPHA_QUERY). */
    private static final int LOOKUP_ALPHA = 5;
    /** Iterative lookup rounds before querying the keyword index holders. */
    private static final int LOOKUP_ROUNDS = 3;
    /** Time allowed for one round of REQ answers. */
    private static final long LOOKUP_ROUND_TIMEOUT_MS = 600;
    /** Number of nodes queried for the keyword once the lookup has converged. */
    private static final int SEARCH_NODES = 8;
    /** Minimum time left to the keyword index holders to answer, even when the lookup used up the timeout. */
    private static final long MIN_RESULT_WAIT_MS = 1000;

    private final KadRoutingTable routingTable;
    private final KadUdpService udpService;
    private final ChildAbuseFilter abuseFilter;
    private final FakeFileDetector fakeDetector;
    private final FileIndex fileIndex;
    private final DatabaseManager databaseManager;
    private final int alphaNodes;
    private final long searchTimeoutMs;
    private final boolean autoIngestToH2;
    private final int minSourcesForIngest;

    private final ConcurrentHashMap<KadId, KadSearchSession> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<KadId, NodeLookup> pendingLookups = new ConcurrentHashMap<>();
    private final Set<String> bannedHashes = ConcurrentHashMap.newKeySet();
    private final List<IllicitFileListener> illicitListeners = new CopyOnWriteArrayList<>();

    private final AtomicLong searchesForked = new AtomicLong(0);
    private final AtomicLong requestsSent = new AtomicLong(0);
    private final AtomicLong resultsReceived = new AtomicLong(0);
    private final AtomicLong illicitFilesBlocked = new AtomicLong(0);
    private final AtomicLong filesIngested = new AtomicLong(0);

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
        /** Contacts the search was sent to: the only ones whose answers are accepted. */
        private final Set<InetSocketAddress> queried = ConcurrentHashMap.newKeySet();
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

        void addQueried(InetAddress address, int port) {
            queried.add(new InetSocketAddress(address, port));
        }

        public boolean wasQueried(InetAddress address, int port) {
            return queried.contains(new InetSocketAddress(address, port));
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
                           FileIndex fileIndex, DatabaseManager databaseManager,
                           int alphaNodes, long searchTimeoutMs,
                           boolean autoIngestToH2, int minSourcesForIngest) {
        this.routingTable = Objects.requireNonNull(routingTable, "routingTable cannot be null");
        this.udpService = Objects.requireNonNull(udpService, "udpService cannot be null");
        this.abuseFilter = abuseFilter != null ? abuseFilter : ChildAbuseFilter.defaults();
        this.fakeDetector = fakeDetector != null ? fakeDetector : new FakeFileDetector();
        this.fileIndex = fileIndex;
        this.databaseManager = databaseManager;
        this.alphaNodes = Math.max(1, alphaNodes);
        this.searchTimeoutMs = searchTimeoutMs;
        this.autoIngestToH2 = autoIngestToH2;
        this.minSourcesForIngest = Math.max(0, minSourcesForIngest);
    }

    public KadSearchBridge(KadRoutingTable routingTable, KadUdpService udpService,
                           ChildAbuseFilter abuseFilter, FakeFileDetector fakeDetector,
                           FileIndex fileIndex, DatabaseManager databaseManager) {
        this(routingTable, udpService, abuseFilter, fakeDetector, fileIndex, databaseManager,
                DEFAULT_ALPHA_NODES, DEFAULT_SEARCH_TIMEOUT_MS, true, 1);
    }

    public KadSearchBridge(KadRoutingTable routingTable, KadUdpService udpService,
                           ChildAbuseFilter abuseFilter, FakeFileDetector fakeDetector,
                           int alphaNodes, long searchTimeoutMs) {
        this(routingTable, udpService, abuseFilter, fakeDetector, null, null,
                alphaNodes, searchTimeoutMs, true, 1);
    }

    public KadSearchBridge(KadRoutingTable routingTable, KadUdpService udpService,
                           ChildAbuseFilter abuseFilter, FakeFileDetector fakeDetector) {
        this(routingTable, udpService, abuseFilter, fakeDetector, null, null,
                DEFAULT_ALPHA_NODES, DEFAULT_SEARCH_TIMEOUT_MS, true, 1);
    }

    public KadSearchBridge(KadRoutingTable routingTable, KadUdpService udpService, int alphaNodes, long searchTimeoutMs) {
        this(routingTable, udpService, ChildAbuseFilter.defaults(), new FakeFileDetector(), null, null,
                alphaNodes, searchTimeoutMs, true, 1);
    }

    public KadSearchBridge(KadRoutingTable routingTable, KadUdpService udpService) {
        this(routingTable, udpService, DEFAULT_ALPHA_NODES, DEFAULT_SEARCH_TIMEOUT_MS);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        udpService.registerListener(KadOpCode.SEARCH_RES, this::handleSearchRes);
        udpService.registerListener(KadOpCode.RES, this::handleFindNodeRes);
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

        // Searches running at the same time for this keyword hash share one session. The session is registered
        // before the requests are sent, so that an immediate answer finds it.
        KadSearchSession created = new KadSearchSession(keywordHash, keyword);
        KadSearchSession existing = activeSessions.putIfAbsent(keywordHash, created);
        KadSearchSession session = existing != null ? existing : created;
        if (existing == null) {
            if (routingTable.findClosest(keywordHash, 1).isEmpty()) {
                // Isolated DHT: no contact can be queried, the search is over at once
                created.complete();
                activeSessions.remove(keywordHash, created);
            } else {
                // Keyword searches must first walk the DHT towards the keyword hash: the nodes responsible for the
                // keyword index are the K closest to it, which are almost never already in our routing table.
                Thread.ofVirtual().name("KadSearch-" + keywordHash.toHexString()).start(() -> {
                    try {
                        // The lookup is part of the search timeout, which bounds how long the client waits
                        long deadline = System.currentTimeMillis() + searchTimeoutMs;
                        List<KadContact> nodes = lookupClosestNodes(created.targetId());
                        if (dispatchSearchRequest(created, nodes) > 0) {
                            Thread.sleep(Math.max(MIN_RESULT_WAIT_MS, deadline - System.currentTimeMillis()));
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException e) {
                        log.debug("Kad search for '{}' failed: {}", keyword, e.getMessage());
                    } finally {
                        created.complete();
                        activeSessions.remove(keywordHash, created);
                    }
                });
            }
        }

        searchesForked.incrementAndGet();
        return session.future();
    }

    /**
     * Iteratively walks the DHT towards {@code target}, collecting the contacts closest to it.
     * Each KADEMLIA2_REQ carries the contacted node's own ID as the sanity check expected by eMule/aMule.
     *
     * @return the closest live contacts known after the lookup (possibly empty).
     */
    private List<KadContact> lookupClosestNodes(KadId target) {
        List<KadContact> candidates = new ArrayList<>(routingTable.findClosest(target, SEARCH_NODES));
        Set<KadId> queried = new HashSet<>();

        for (int round = 0; round < LOOKUP_ROUNDS; round++) {
            List<KadContact> toQuery = candidates.stream()
                    .filter(c -> !c.isDead() && !queried.contains(c.id()))
                    .sorted(Comparator.comparing(c -> c.id().distance(target)))
                    .limit(LOOKUP_ALPHA)
                    .toList();
            if (toQuery.isEmpty()) break;

            NodeLookup lookup = new NodeLookup();
            pendingLookups.put(target, lookup);
            try {
                for (KadContact contact : toQuery) {
                    queried.add(contact.id());
                    lookup.queried.add(new InetSocketAddress(contact.address(), contact.udpPort()));
                    lookup.outstanding.incrementAndGet();
                    try {
                        udpService.send(KadPacket.createFindNodeReq(contact.id(), FIND_VALUE_TYPE, target),
                                contact.address(), contact.udpPort());
                    } catch (IOException e) {
                        lookup.outstanding.decrementAndGet();
                        log.debug("Failed to send Kad find-node to {}: {}", contact, e.getMessage());
                    }
                }
                if (lookup.outstanding.get() > 0) {
                    lookup.await(LOOKUP_ROUND_TIMEOUT_MS);
                }
            } finally {
                pendingLookups.remove(target, lookup);
            }

            boolean discovered = false;
            for (KadContact contact : lookup.contacts) {
                if (candidates.stream().noneMatch(c -> c.id().equals(contact.id()))) {
                    candidates.add(contact);
                    discovered = true;
                }
            }
            if (!discovered) break;
            log.debug("Kad lookup for {} round {} discovered closer contacts (now {})", target, round + 1, candidates.size());
        }

        Map<KadId, KadContact> unique = new LinkedHashMap<>();
        candidates.stream()
                .filter(c -> !c.isDead())
                .sorted(Comparator.comparing(c -> c.id().distance(target)))
                .forEach(c -> unique.putIfAbsent(c.id(), c));
        return new ArrayList<>(unique.values());
    }

    /**
     * Handles a KADEMLIA2_RES (closest contacts) answering one of our lookup requests.
     */
    protected void handleFindNodeRes(KadPacket packet, InetAddress remoteAddress, int remotePort) {
        KadPacket.FindNodeResult parsed = KadPacket.parseFindNodeRes(packet.data());
        if (parsed == null) return;

        NodeLookup lookup = pendingLookups.get(parsed.targetId());
        if (lookup == null) return;
        if (!lookup.queried.contains(new InetSocketAddress(remoteAddress, remotePort))) {
            log.debug("Ignored unsolicited KADEMLIA2_RES from {}:{} for target {}",
                    remoteAddress.getHostAddress(), remotePort, parsed.targetId());
            return;
        }

        for (KadContact contact : parsed.contacts()) {
            routingTable.addOrUpdate(contact);
            lookup.contacts.add(contact);
        }
        if (lookup.outstanding.decrementAndGet() <= 0) {
            lookup.done.complete(null);
        }
    }

    /**
     * Sends KADEMLIA2_SEARCH_KEY_REQ to the nodes responsible for the keyword.
     *
     * @return The number of requests actually sent.
     */
    private int dispatchSearchRequest(KadSearchSession session, List<KadContact> targetNodes) {
        if (targetNodes.isEmpty()) {
            log.debug("No Kad contacts available to query for '{}'", session.keyword());
            return 0;
        }

        KadPacket req = KadPacket.createSearchKeyReq(session.targetId(), 0);
        int sent = 0;
        for (KadContact contact : targetNodes.subList(0, Math.min(SEARCH_NODES, targetNodes.size()))) {
            try {
                session.addQueried(contact.address(), contact.udpPort()); // before the answer can arrive
                udpService.send(req, contact.address(), contact.udpPort());
                sent++;
                log.debug("Sent KADEMLIA2_SEARCH_KEY_REQ for '{}' ({}) to {}:{}",
                        session.keyword(), session.targetId(), contact.address().getHostAddress(), contact.udpPort());
            } catch (IOException e) {
                log.debug("Failed to send search request to {}:{} - {}", contact.address().getHostAddress(), contact.udpPort(), e.getMessage());
            }
        }
        requestsSent.addAndGet(sent);
        return sent;
    }

    protected void handleSearchRes(KadPacket packet, InetAddress remoteAddress, int remotePort) {
        KadPacket.SearchRes parsed = KadPacket.parseSearchRes(packet.data());
        if (parsed == null) return;

        KadSearchSession session = activeSessions.get(parsed.targetId());
        if (session == null) {
            log.debug("Received SEARCH_RES for unknown or expired target: {}", parsed.targetId());
            return;
        }
        if (!session.wasQueried(remoteAddress, remotePort)) {
            // Unsolicited answer: accepting it would let anyone fill the database with the files of their choice
            log.debug("Ignored SEARCH_RES from {}:{}, not queried for target {}",
                    remoteAddress.getHostAddress(), remotePort, parsed.targetId());
            return;
        }

        List<FileMetadata> files = parsed.files();
        resultsReceived.addAndGet(files.size());
        log.debug("Received SEARCH_RES with {} files from {}:{} for target {}",
                files.size(), remoteAddress.getHostAddress(), remotePort, parsed.targetId());

        List<FileMetadata> acceptedFiles = new ArrayList<>();
        List<FileMetadata> filesToIngest = new ArrayList<>();
        for (FileMetadata file : files) {
            if (acceptFile(file)) {
                int sources = file.sourceCount();
                if (sources >= minSourcesForIngest) {
                    if (autoIngestToH2) {
                        if (fileIndex != null) {
                            filesToIngest.add(file);
                        } else if (databaseManager != null) {
                            filesToIngest.add(file);
                        }
                    }
                    acceptedFiles.add(file);
                } else {
                    log.debug("Kad file ignored (sources {} < minSources {}): {} ({})",
                            sources, minSourcesForIngest, file.name(), file.hash());
                }
            }
        }
        if (!filesToIngest.isEmpty()) {
            if (fileIndex != null) {
                fileIndex.addFiles(filesToIngest, FileSource.KAD_SEARCH);
            } else if (databaseManager != null) {
                databaseManager.saveFiles(filesToIngest);
            }
            filesIngested.addAndGet(filesToIngest.size());
            for (FileMetadata file : filesToIngest) {
                log.debug("Ingested Kad file into FileIndex & H2: {} ({}, sources={})",
                        file.name(), file.hash(), file.sourceCount());
            }
        }
        acceptedFiles.forEach(session::addResult);
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

    public long getRequestsSent() {
        return requestsSent.get();
    }

    public long getSearchTimeoutMs() {
        return searchTimeoutMs;
    }

    public long getResultsReceived() {
        return resultsReceived.get();
    }

    public long getFilesIngested() {
        return filesIngested.get();
    }

    public boolean isAutoIngestToH2() {
        return autoIngestToH2;
    }

    public int getMinSourcesForIngest() {
        return minSourcesForIngest;
    }

    public FileIndex getFileIndex() {
        return fileIndex;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    @Override
    public synchronized void close() {
        running = false;
        activeSessions.values().forEach(KadSearchSession::complete);
        activeSessions.clear();
        pendingLookups.values().forEach(lookup -> lookup.done.complete(null));
        pendingLookups.clear();
        log.info("KadSearchBridge stopped");
    }

    /**
     * State of one iterative closest-node lookup (one keyword target at a time).
     */
    private static final class NodeLookup {
        final Set<InetSocketAddress> queried = ConcurrentHashMap.newKeySet();
        final List<KadContact> contacts = new CopyOnWriteArrayList<>();
        final AtomicInteger outstanding = new AtomicInteger();
        final CompletableFuture<Void> done = new CompletableFuture<>();

        void await(long timeoutMs) {
            try {
                done.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // Round timed out: continue with the contacts collected so far.
            }
        }
    }
}
