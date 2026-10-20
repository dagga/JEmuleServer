package org.jemule.network;

import org.jemule.core.FileIndex;
import org.jemule.core.FileMetadata;
import org.jemule.core.FileSource;
import org.jemule.core.SearchQuery;
import org.jemule.protocol.FileTypes;
import org.jemule.protocol.Tag;
import org.jemule.security.FakeFileDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Fans client searches out to verified eD2K servers and ingests their matching results. */
public final class ServerSearchBridge implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ServerSearchBridge.class);
    private static final int OP_GLOBSEARCHREQ3 = 0x90;
    private static final int OP_GLOBSEARCHRES = 0x99;
    private static final int MAX_PEERS_PER_SEARCH = 16;
    private static final int MAX_QUERY_BYTES = 1024;
    private static final long DEFAULT_SEARCH_TIMEOUT_MILLIS = 4000;
    /**
     * A server answers a UDP global search with its first 10 to 30 matches, and the UDP protocol has no
     * OP_QUERY_MORE_RESULT. Once a server returned this many results, the search is repeated for each file type:
     * the sub-searches are disjoint, so each one returns other files.
     */
    static final int PARTITION_THRESHOLD = 10;
    /** FT_FILETYPE values searched separately (ED2KFTSTR_* in eMule's opcodes.h). */
    static final List<String> PARTITION_TYPES = List.of(FileTypes.VIDEO, FileTypes.AUDIO, FileTypes.PROGRAM,
            "Arc", "Iso", FileTypes.DOCUMENT, FileTypes.IMAGE, FileTypes.EMULE_COLLECTION);
    /** Searches split per file type per server and per minute, to stay a light load for each server. */
    static final int MAX_PARTITIONS_PER_SERVER_PER_MINUTE = 4;

    private static final class SearchSession {
        private final SearchQuery query;
        private final byte[] searchTree;
        /** False when the client already restricts the file type: a split would not return other files. */
        private final boolean partitionable;
        private final Set<ServerGossip.Endpoint> peers = ConcurrentHashMap.newKeySet();
        private final Map<ServerGossip.Endpoint, Integer> received = new ConcurrentHashMap<>();
        private final Set<ServerGossip.Endpoint> partitioned = ConcurrentHashMap.newKeySet();
        private final Map<String, FileMetadata> results = new ConcurrentHashMap<>();
        private final CompletableFuture<List<FileMetadata>> future = new CompletableFuture<>();

        private SearchSession(SearchQuery query, byte[] searchTree) {
            this.query = query;
            this.searchTree = searchTree;
            this.partitionable = !filtersFileType(query);
        }

        /** Counts the results of a server; true once when the search should be split for that server. */
        private boolean shouldPartition(ServerGossip.Endpoint endpoint, int results) {
            int total = received.merge(endpoint, results, Integer::sum);
            return partitionable && total >= PARTITION_THRESHOLD && partitioned.add(endpoint);
        }

        private void complete() {
            future.complete(List.copyOf(results.values()));
        }
    }

    private final Supplier<Collection<ServerGossip.VerifiedPeer>> peers;
    private final Supplier<DatagramSocket> socket;
    private final FileIndex fileIndex;
    private final FakeFileDetector fakeFileDetector;
    private final long searchTimeoutMillis;
    private final Map<String, SearchSession> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger peerCursor = new AtomicInteger();
    private final Map<ServerGossip.Endpoint, Deque<Long>> partitionTimes = new HashMap<>();
    private final java.util.concurrent.atomic.AtomicLong searchesForked = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong requestsSent = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong resultsReceived = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong filesIngested = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean running = true;

    ServerSearchBridge(Supplier<Collection<ServerGossip.VerifiedPeer>> peers,
                       Supplier<DatagramSocket> socket,
                       FileIndex fileIndex,
                       FakeFileDetector fakeFileDetector) {
        this(peers, socket, fileIndex, fakeFileDetector, DEFAULT_SEARCH_TIMEOUT_MILLIS);
    }

    ServerSearchBridge(Supplier<Collection<ServerGossip.VerifiedPeer>> peers,
                       Supplier<DatagramSocket> socket,
                       FileIndex fileIndex,
                       FakeFileDetector fakeFileDetector,
                       long searchTimeoutMillis) {
        if (searchTimeoutMillis < 1) throw new IllegalArgumentException("Search timeout must be positive");
        this.peers = peers;
        this.socket = socket;
        this.fileIndex = fileIndex;
        this.fakeFileDetector = fakeFileDetector;
        this.searchTimeoutMillis = searchTimeoutMillis;
    }

    public CompletableFuture<List<FileMetadata>> forkSearch(SearchQuery query, byte[] searchTree) {
        if (!running || query == null || searchTree == null || searchTree.length == 0
                || searchTree.length > MAX_QUERY_BYTES) {
            return CompletableFuture.completedFuture(List.of());
        }
        String key = query.toString();
        SearchSession session = new SearchSession(query, searchTree);
        SearchSession existing = sessions.putIfAbsent(key, session);
        if (existing != null) return existing.future;
        searchesForked.incrementAndGet();
        if (sessions.size() > 128) {
            sessions.remove(key, session);
            return CompletableFuture.completedFuture(List.of());
        }

        DatagramSocket udpSocket = socket.get();
        if (udpSocket != null) {
            byte[] request = searchRequest(searchTree);
            List<ServerGossip.VerifiedPeer> verifiedPeers = new ArrayList<>(peers.get());
            int first = verifiedPeers.isEmpty() ? 0 : Math.floorMod(peerCursor.getAndIncrement(), verifiedPeers.size());
            for (int offset = 0; offset < Math.min(MAX_PEERS_PER_SEARCH, verifiedPeers.size()); offset++) {
                ServerGossip.VerifiedPeer peer = verifiedPeers.get((first + offset) % verifiedPeers.size());
                try {
                    ServerGossip.Endpoint endpoint = peer.endpoint();
                    udpSocket.send(new DatagramPacket(request, request.length, endpoint.address(), endpoint.udpPort()));
                    session.peers.add(endpoint);
                    requestsSent.incrementAndGet();
                } catch (java.io.IOException e) {
                    log.debug("Could not send server search to {}: {}", peer.endpoint(), e.getMessage());
                }
            }
        }
        if (session.peers.isEmpty()) {
            sessions.remove(key, session);
            session.complete();
        } else {
            CompletableFuture.delayedExecutor(searchTimeoutMillis, TimeUnit.MILLISECONDS).execute(() -> {
                sessions.remove(key, session);
                session.complete();
            });
        }
        return session.future;
    }

    public long getSearchTimeoutMs() {
        return searchTimeoutMillis;
    }

    public long getSearchesForked() {
        return searchesForked.get();
    }

    public long getRequestsSent() {
        return requestsSent.get();
    }

    public long getResultsReceived() {
        return resultsReceived.get();
    }

    public long getFilesIngested() {
        return filesIngested.get();
    }

    public int getActiveSessionCount() {
        return sessions.size();
    }

    boolean handle(InetAddress source, int sourcePort, byte[] packet) {
        ServerGossip.Endpoint endpoint = endpointForSource(source, sourcePort);
        if (endpoint == null || packet == null || packet.length < 2
                || (packet[0] & 0xFF) != (Packet.PROTOCOL_ED2K & 0xFF)
                || (packet[1] & 0xFF) != OP_GLOBSEARCHRES) {
            return false;
        }

        List<SearchSession> expectedSessions = sessions.values().stream()
                .filter(session -> session.peers.contains(endpoint))
                .toList();
        if (expectedSessions.isEmpty()) return false;
        List<FileMetadata> decoded = decodeResults(packet);
        if (decoded == null) return true;
        resultsReceived.addAndGet(decoded.size());
        for (SearchSession session : expectedSessions) {
            if (session.shouldPartition(endpoint, decoded.size())) {
                partition(session, endpoint);
            }
        }
        Map<String, FileMetadata> matchingFiles = new java.util.LinkedHashMap<>();
        for (FileMetadata file : decoded) {
            if (expectedSessions.stream().anyMatch(session -> session.query.test(file))) {
                matchingFiles.putIfAbsent(file.hash().toUpperCase(Locale.ROOT), file);
            }
        }
        List<FileMetadata> ingested = ingestAll(matchingFiles.values());
        filesIngested.addAndGet(ingested.size());
        for (SearchSession session : expectedSessions) {
            for (FileMetadata file : ingested) {
                if (session.query.test(file)) {
                    session.results.putIfAbsent(file.hash().toUpperCase(Locale.ROOT), file);
                }
            }
        }
        return true;
    }

    /**
     * Repeats the search of {@code session} on {@code endpoint} once per file type, within the per-server limit.
     */
    private void partition(SearchSession session, ServerGossip.Endpoint endpoint) {
        DatagramSocket udpSocket = socket.get();
        if (!running || udpSocket == null || !allowPartition(endpoint, System.currentTimeMillis())) return;
        for (String type : PARTITION_TYPES) {
            byte[] request = searchRequest(withFileType(session.searchTree, type));
            try {
                udpSocket.send(new DatagramPacket(request, request.length, endpoint.address(), endpoint.udpPort()));
                requestsSent.incrementAndGet();
            } catch (java.io.IOException e) {
                log.debug("Could not send a per-type server search to {}: {}", endpoint, e.getMessage());
                return;
            }
        }
        log.debug("Server {} returned a full answer for {}: search split per file type", endpoint, session.query);
    }

    private boolean allowPartition(ServerGossip.Endpoint endpoint, long now) {
        synchronized (partitionTimes) {
            partitionTimes.values().forEach(times -> times.removeIf(t -> now - t >= 60_000));
            partitionTimes.values().removeIf(Deque::isEmpty);
            Deque<Long> times = partitionTimes.computeIfAbsent(endpoint, ignored -> new ArrayDeque<>());
            if (times.size() >= MAX_PARTITIONS_PER_SERVER_PER_MINUTE) return false;
            times.add(now);
            return true;
        }
    }

    /** The search tree {@code tree AND FT_FILETYPE=type}. */
    static byte[] withFileType(byte[] searchTree, String type) {
        byte[] value = type.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(2 + searchTree.length + 3 + value.length + 3).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 0x00).put(SearchQuery.OP_AND)
                .put(searchTree)
                .put((byte) 0x02).putShort((short) value.length).put(value)
                .putShort((short) 1).put(SearchQuery.ID_FILETYPE)
                .array();
    }

    private static boolean filtersFileType(SearchQuery query) {
        return switch (query) {
            case SearchQuery.AndQuery and -> filtersFileType(and.left()) || filtersFileType(and.right());
            case SearchQuery.OrQuery or -> filtersFileType(or.left()) || filtersFileType(or.right());
            case SearchQuery.NotQuery not -> filtersFileType(not.left()) || filtersFileType(not.right());
            case SearchQuery.TermQuery t -> t.id() == SearchQuery.ID_FILETYPE;
            case null, default -> false;
        };
    }

    static byte[] searchRequest(byte[] searchTree) {
        ByteBuffer request = ByteBuffer.allocate(2 + 4 + 6 + searchTree.length).order(ByteOrder.LITTLE_ENDIAN);
        request.put((byte) Packet.PROTOCOL_ED2K).put((byte) OP_GLOBSEARCHREQ3);
        Tag.writeList(request, List.of(new Tag(Tag.TYPE_INTEGER, "\u000E", 1)));
        request.put(searchTree);
        return request.array();
    }

    static List<FileMetadata> decodeResults(byte[] packet) {
        if (packet == null || packet.length < 24) return null;
        ByteBuffer input = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        List<FileMetadata> files = new ArrayList<>();
        try {
            while (input.hasRemaining()) {
                if (input.remaining() < 24 || (input.get() & 0xFF) != (Packet.PROTOCOL_ED2K & 0xFF)
                        || (input.get() & 0xFF) != OP_GLOBSEARCHRES) return null;
                byte[] hashBytes = new byte[16];
                input.get(hashBytes);
                input.position(input.position() + 6); // server-assigned client ID and port
                if (input.remaining() < 4) return null;
                input.mark();
                int tagCount = input.getInt();
                input.reset();
                if (tagCount < 1 || tagCount > 128) return null;
                List<Tag> tags = Tag.readList(input);
                if (tags.size() != tagCount) return null;
                FileMetadata metadata = metadata(hashBytes, tags);
                if (metadata != null) files.add(metadata);
            }
        } catch (RuntimeException e) {
            return null;
        }
        return files;
    }

    private static FileMetadata metadata(byte[] hashBytes, List<Tag> tags) {
        String hash = org.jemule.network.handler.HandlerUtils.bytesToHashString(hashBytes);
        String name = null;
        String type = null;
        long size = -1;
        long sizeHigh = 0;
        long sources = 0;
        for (Tag tag : tags) {
            if (tag.name().equals(Tag.NAME_FILENAME) && tag.value() instanceof String value) name = value;
            // Servers send the type as an ED2KFT_* integer, which client queries compare with "Video", "Pro"...
            else if (tag.name().equals(Tag.NAME_FILETYPE)) type = FileTypes.fromTag(tag);
            else if (tag.name().equals(Tag.NAME_FILESIZE) && tag.isInt()) size = tag.unsignedValue();
            else if (tag.name().equals(Tag.NAME_FILESIZE_HI) && tag.isInt()) sizeHigh = tag.unsignedValue();
            else if (tag.name().equals(Tag.NAME_SOURCES) && tag.isInt()) sources = tag.unsignedValue();
        }
        if (!org.jemule.network.handler.HandlerUtils.isValidFilename(name)
                || size < 0 || sources == 0 || sources > Integer.MAX_VALUE || sizeHigh > 0x7FFFFFFFL) return null;
        size |= sizeHigh << 32;
        return FileMetadata.withoutSources(hash, name, size, type, List.copyOf(tags));
    }

    private List<FileMetadata> ingestAll(Collection<FileMetadata> files) {
        List<FileMetadata> accepted = files.stream()
                .filter(file -> file.hash() != null && !fileIndex.isChildAbuse(file.hash(), file.name()))
                .filter(file -> fakeFileDetector == null || !fakeFileDetector.isFake(file.hash(), file.name(), file.size()))
                .toList();
        return fileIndex.addFiles(accepted, FileSource.SERVER_SEARCH);
    }

    private static ServerGossip.Endpoint endpointForSource(InetAddress source, int sourcePort) {
        if (!(source instanceof Inet4Address) || sourcePort < 5) return null;
        try {
            return new ServerGossip.Endpoint(source, sourcePort - 4);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public void close() {
        running = false;
        sessions.values().forEach(SearchSession::complete);
        sessions.clear();
    }
}
