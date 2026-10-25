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

import org.jemule.config.ServerIndexUpdateConfig;
import org.jemule.core.FileIndex;
import org.jemule.core.FileMetadata;
import org.jemule.core.FileSource;
import org.jemule.core.IngestionExecutor;
import org.jemule.core.SearchQuery;
import org.jemule.core.ServerThreads;
import org.jemule.protocol.FileTypes;
import org.jemule.security.FakeFileDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Updates the index from the eD2K index peers (the verified eD2K servers): logged in to each of them as a client,
 * it searches there the words its index holds most often, and the keywords of the clients' searches without their
 * filters, then indexes the files found. A search answered with a full page is searched again per file type, and a
 * full type per size (1–900 MB, 900–2000 MB, above 2000 MB). Each peer has its own session, thread and
 * {@link UpdateSchedule}; Kad is never searched this way.
 */
public final class ServerIndexUpdater implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ServerIndexUpdater.class);

    static final String CLIENT_NAME = "JEmuleServer";
    /** Words of the index offered to the servers, refreshed every hour. */
    static final int TOP_WORDS = 20_000;
    static final long WORDS_REFRESH = UpdateSchedule.HOUR;
    /** A word is searched again on the same server after this delay at the earliest. */
    static final long WORD_REPEAT = 7 * UpdateSchedule.DAY;
    /** Refinements left over by a full batch wait this long. */
    static final long DEFERRED_DELAY = UpdateSchedule.DAY;
    /** A page of fewer results is never full, whatever the server's page size. */
    static final int MIN_FULL_PAGE = 100;
    static final int ANSWER_TIMEOUT = 60_000;
    static final long MIN_RECONNECT = UpdateSchedule.MINUTE;
    static final long MAX_RECONNECT = UpdateSchedule.HOUR;
    private static final int MAX_DEFERRED = 2_000;
    private static final int MAX_REMEMBERED_WORDS = 20_000;
    private static final int MAX_CLIENT_KEYWORDS = 500;
    /** File types of the refinements, the most shared first. */
    static final List<String> TYPES = List.of(FileTypes.VIDEO, FileTypes.AUDIO, FileTypes.PROGRAM, FileTypes.IMAGE,
            FileTypes.DOCUMENT, "Arc", "Iso");
    private static final long MB = 1024L * 1024;
    /** Size slices of a full type: {lowest, highest} in bytes, 0 for no bound. */
    static final long[][] SIZES = {{MB, 900 * MB}, {900 * MB, 2000 * MB}, {2000 * MB, 0}};

    /** Opens a session on a server (a fake one in the tests). */
    interface Connector {
        Session open(ServerGossip.Endpoint endpoint, byte[] userHash) throws IOException;
    }

    /** A logged-in session. */
    interface Session extends AutoCloseable {
        ServerUpdateSession.Answer search(byte[] tree) throws IOException;

        @Override
        void close();
    }

    /** Sleeps until a time; returns false when interrupted (the updater is closing). */
    interface Sleeper {
        boolean sleepUntil(long time) throws InterruptedException;
    }

    /** One search: keywords, and an optional file type and size slice. */
    record Search(String keywords, String type, int slice) {
        Search(String keywords) {
            this(keywords, null, -1);
        }

        byte[] tree() {
            return SearchTrees.build(keywords, type, slice < 0 ? null : SIZES[slice]);
        }

        @Override
        public String toString() {
            return keywords + (type == null ? "" : " type " + type) + (slice < 0 ? "" : " size " + slice);
        }
    }

    private record Deferred(Search search, long notBefore) {}

    private final Supplier<Collection<ServerGossip.VerifiedPeer>> peers;
    private final FileIndex fileIndex;
    private final FakeFileDetector fakeDetector;
    private final Supplier<ServerIndexUpdateConfig> config;
    private final Predicate<ServerGossip.Endpoint> busy;
    private final Connector connector;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private final RandomGenerator random;
    private volatile IngestionExecutor ingestion = IngestionExecutor.direct();

    private final Map<ServerGossip.Endpoint, Worker> workers = new ConcurrentHashMap<>();
    /** Keywords of the clients' searches, without their filters, newest last. */
    private final Deque<String> clientKeywords = new ArrayDeque<>();
    private volatile List<String> words = List.of();
    private volatile long wordsAt = Long.MIN_VALUE / 2;
    private volatile boolean running = true;

    private final AtomicLong searches = new AtomicLong();
    private final AtomicLong refinements = new AtomicLong();
    private final AtomicLong fullPages = new AtomicLong();
    private final AtomicLong results = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong incidents = new AtomicLong();
    private final AtomicLong loginFailures = new AtomicLong();

    ServerIndexUpdater(Supplier<Collection<ServerGossip.VerifiedPeer>> peers, FileIndex fileIndex,
                       FakeFileDetector fakeDetector, Supplier<ServerIndexUpdateConfig> config,
                       Predicate<ServerGossip.Endpoint> busy) {
        this(peers, fileIndex, fakeDetector, config, busy,
                (endpoint, hash) -> {
                    ServerUpdateSession session = ServerUpdateSession.open(endpoint, hash, CLIENT_NAME, ANSWER_TIMEOUT);
                    return new Session() {
                        public ServerUpdateSession.Answer search(byte[] tree) throws IOException {
                            return session.search(tree);
                        }

                        public void close() {
                            session.close();
                        }
                    };
                },
                System::currentTimeMillis,
                time -> {
                    long wait = time - System.currentTimeMillis();
                    if (wait > 0) Thread.sleep(Math.min(wait, 30_000)); // the settings are read again at least every 30 s
                    return true;
                },
                new SecureRandom());
    }

    ServerIndexUpdater(Supplier<Collection<ServerGossip.VerifiedPeer>> peers, FileIndex fileIndex,
                       FakeFileDetector fakeDetector, Supplier<ServerIndexUpdateConfig> config,
                       Predicate<ServerGossip.Endpoint> busy, Connector connector, LongSupplier clock, Sleeper sleeper,
                       RandomGenerator random) {
        this.peers = peers;
        this.fileIndex = fileIndex;
        this.fakeDetector = fakeDetector;
        this.config = config;
        this.busy = busy;
        this.connector = connector;
        this.clock = clock;
        this.sleeper = sleeper;
        this.random = random;
    }

    /** Indexes and stores the files found on the ingestion thread. */
    void setIngestionExecutor(IngestionExecutor executor) {
        this.ingestion = executor == null ? IngestionExecutor.direct() : executor;
    }

    /**
     * Starts a worker for each verified server that has none and stops those of the servers no longer verified.
     * Called regularly by the server (maintenance tier).
     */
    void refreshWorkers() {
        if (!running) return;
        List<ServerGossip.Endpoint> current = peers.get().stream().map(ServerGossip.VerifiedPeer::endpoint).toList();
        workers.entrySet().removeIf(entry -> {
            if (current.contains(entry.getKey())) return false;
            entry.getValue().stop();
            return true;
        });
        for (ServerGossip.Endpoint endpoint : current) {
            workers.computeIfAbsent(endpoint, e -> {
                Worker worker = new Worker(e);
                worker.thread = ServerThreads.start(ServerThreads.Tier.SEARCH,
                        "IndexUpdate-" + e.address().getHostAddress() + ":" + e.tcpPort(), worker::run);
                return worker;
            });
        }
    }

    /**
     * Offers the keywords of a client's search, without its filters, to the next batches of every server. Searches
     * with OR or NOT, or with no keyword, are left out; the clients' filters are never replayed.
     */
    public void noteClientSearch(SearchQuery query) {
        String keywords = keywords(query);
        if (keywords == null || keywords.length() < 3 || fileIndex.matchesChildAbuse(keywords)) return;
        synchronized (clientKeywords) {
            clientKeywords.remove(keywords);
            clientKeywords.addLast(keywords);
            while (clientKeywords.size() > MAX_CLIENT_KEYWORDS) clientKeywords.pollFirst();
        }
    }

    /** The file name keywords of a query made of ANDs only, space separated; null otherwise. */
    static String keywords(SearchQuery query) {
        List<String> terms = new ArrayList<>();
        if (!collect(query, terms) || terms.isEmpty()) return null;
        return String.join(" ", terms).toLowerCase(Locale.ROOT).strip();
    }

    private static boolean collect(SearchQuery query, List<String> terms) {
        return switch (query) {
            case SearchQuery.TermQuery t -> {
                if (t.id() == SearchQuery.ID_FILENAME && !t.term().isBlank()) terms.add(t.term().strip());
                yield true; // a filter (type, size, extension...) is dropped
            }
            case SearchQuery.AndQuery and -> collect(and.left(), terms) && collect(and.right(), terms);
            case null -> false;
            default -> !(query instanceof SearchQuery.OrQuery) && !(query instanceof SearchQuery.NotQuery);
        };
    }

    private List<String> words(long now) {
        if (now - wordsAt >= WORDS_REFRESH) {
            words = fileIndex.topWords(TOP_WORDS);
            wordsAt = now;
        }
        return words;
    }

    /** One server: its session, schedule and searches. */
    private final class Worker {
        private final ServerGossip.Endpoint endpoint;
        private final byte[] userHash = userHash();
        private final UpdateSchedule schedule;
        private final Deque<Search> batch = new ArrayDeque<>();
        private int batchSize;
        private final Deque<Deferred> deferred = new ArrayDeque<>();
        private final Map<String, Long> searched = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > MAX_REMEMBERED_WORDS;
            }
        };
        private int wordCursor;
        private int largestPage;
        private Session session;
        private long reconnectAt;
        private long reconnectDelay = MIN_RECONNECT;
        private volatile Thread thread;
        private volatile boolean stopped;
        private volatile String state = "waiting";

        Worker(ServerGossip.Endpoint endpoint) {
            this.endpoint = endpoint;
            this.schedule = new UpdateSchedule(random, clock.getAsLong());
        }

        void run() {
            try {
                while (running && !stopped) {
                    if (!step()) break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.warn("Index update from {} stopped: {}", endpoint, e.getMessage(), e);
            } finally {
                closeSession();
                state = "stopped";
            }
        }

        /** Waits for the next search, then sends it; false to stop. */
        private boolean step() throws InterruptedException {
            ServerIndexUpdateConfig settings = config.get();
            long now = clock.getAsLong();
            if (!settings.enabled()) {
                closeSession();
                state = "disabled";
                return sleeper.sleepUntil(now + 30_000);
            }
            if (fileIndex.isFull()) {
                // maxFiles reached or the heap full: no update, and no idle session, until there is room again
                closeSession();
                state = "index full";
                return sleeper.sleepUntil(now + 60_000);
            }
            long at = schedule.nextSearchAt(now, settings.maxPerHour(), settings.maxPerDay());
            if (now < at) {
                state = schedule.stopped(now) ? "stopped after an incident" : "waiting";
                return sleeper.sleepUntil(at);
            }
            if (session == null) {
                if (now < reconnectAt) {
                    state = "reconnecting";
                    return sleeper.sleepUntil(reconnectAt);
                }
                try {
                    session = connector.open(endpoint, userHash);
                    reconnectDelay = MIN_RECONNECT;
                } catch (IOException e) {
                    loginFailures.incrementAndGet();
                    reconnectAt = now + reconnectDelay;
                    reconnectDelay = Math.min(MAX_RECONNECT, reconnectDelay * 2);
                    log.debug("Index update: cannot log in to {}: {}", endpoint, e.getMessage());
                    return true;
                }
            }
            Search search = next(now);
            if (search == null) {
                state = "no word";
                return sleeper.sleepUntil(now + 10 * 60_000);
            }
            state = "searching";
            send(search, now);
            return true;
        }

        /** The next search: the rest of the batch, a deferred refinement, a client's keywords or a word of the index. */
        private Search next(long now) {
            if (!batch.isEmpty() && batchSize < UpdateSchedule.MAX_PER_BATCH) {
                batchSize++;
                return batch.pollFirst();
            }
            // The batch is over: what it did not send waits a day
            for (Search late : batch) {
                if (deferred.size() < MAX_DEFERRED) deferred.addLast(new Deferred(late, now + DEFERRED_DELAY));
            }
            batch.clear();
            batchSize = 0;
            Deferred due = deferred.peekFirst();
            if (due != null && due.notBefore() <= now) {
                deferred.pollFirst();
                return startBatch(due.search());
            }
            synchronized (clientKeywords) {
                for (var it = clientKeywords.descendingIterator(); it.hasNext(); ) {
                    String keywords = it.next();
                    if (fresh(keywords, now)) return startBatch(new Search(keywords));
                }
            }
            List<String> candidates = words(now);
            for (int tried = 0; tried < candidates.size(); tried++) {
                String word = candidates.get(wordCursor++ % candidates.size());
                if (fresh(word, now)) return startBatch(new Search(word));
            }
            return null;
        }

        private boolean fresh(String keywords, long now) {
            Long last = searched.get(keywords);
            return last == null || now - last >= WORD_REPEAT;
        }

        private Search startBatch(Search search) {
            if (search.type() == null) searched.put(search.keywords(), clock.getAsLong());
            batchSize = 1;
            return search;
        }

        private void send(Search search, long now) {
            ServerUpdateSession.Answer answer;
            schedule.sent(now);
            searches.incrementAndGet();
            if (search.type() != null) refinements.incrementAndGet();
            try {
                answer = session.search(search.tree());
            } catch (IOException e) {
                incident(e.getMessage());
                return;
            }
            List<FileMetadata> files = ServerSearchBridge.decodeSearchResult(answer.payload());
            int count = files == null ? 0 : resultCount(answer.payload());
            largestPage = Math.max(largestPage, count);
            boolean full = count >= MIN_FULL_PAGE && count >= largestPage;
            if (full) {
                fullPages.incrementAndGet();
                refine(search);
            }
            if (files != null) ingest(files);
            schedule.answered(clock.getAsLong(), answer.millis(), busy.test(endpoint),
                    batch.isEmpty() || batchSize >= UpdateSchedule.MAX_PER_BATCH);
        }

        /** The refinements of a full search come next: per type, and the sizes of a full type before the other types. */
        private void refine(Search search) {
            List<Search> more = new ArrayList<>();
            if (search.type() == null) {
                for (String type : TYPES) more.add(new Search(search.keywords(), type, -1));
            } else if (search.slice() < 0) {
                for (int slice = 0; slice < SIZES.length; slice++) more.add(new Search(search.keywords(), search.type(), slice));
            }
            for (int i = more.size() - 1; i >= 0; i--) batch.addFirst(more.get(i));
        }

        private void incident(String reason) {
            incidents.incrementAndGet();
            schedule.incident(clock.getAsLong());
            batch.clear();
            closeSession();
            log.info("Index update from {} paused until {}: {}", endpoint,
                    java.time.Instant.ofEpochMilli(schedule.stoppedUntil()), reason);
        }

        private void closeSession() {
            if (session != null) {
                session.close();
                session = null;
            }
        }

        void stop() {
            stopped = true;
            Thread t = thread;
            if (t != null) t.interrupt();
        }

        Map<String, Object> view() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("server", endpoint.address().getHostAddress() + ":" + endpoint.tcpPort());
            view.put("state", state);
            view.put("searchesLastDay", schedule.sentLastDay(clock.getAsLong()));
            view.put("incidents", schedule.incidents());
            view.put("deferred", deferred.size());
            return view;
        }
    }

    private static int resultCount(byte[] payload) {
        return payload.length < 4 ? 0 : ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /** Valid, clean files with enough sources go to the index, on the ingestion thread. */
    private void ingest(List<FileMetadata> files) {
        int minSources = config.get().minSources();
        List<FileMetadata> kept = files.stream()
                .filter(f -> f.sourceCount() >= minSources)
                .filter(f -> !fileIndex.isChildAbuse(f.hash(), f.name()))
                .filter(f -> fakeDetector == null || !fakeDetector.isFake(f.hash(), f.name(), f.size()))
                .toList();
        results.addAndGet(files.size());
        accepted.addAndGet(kept.size());
        if (!kept.isEmpty()) ingestion.offer(() -> fileIndex.addFiles(kept, FileSource.SERVER_INDEX_UPDATE));
    }

    private byte[] userHash() {
        byte[] hash = new byte[16];
        random.nextBytes(hash);
        hash[5] = 14; // eMule's user hash markers
        hash[14] = 111;
        return hash;
    }

    /** Counters for the admin panel. */
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", config.get().enabled());
        stats.put("servers", workers.size());
        stats.put("searches", searches.get());
        stats.put("refinements", refinements.get());
        stats.put("fullPages", fullPages.get());
        stats.put("results", results.get());
        stats.put("accepted", accepted.get());
        stats.put("newFiles", fileIndex.newFileCount(FileSource.SERVER_INDEX_UPDATE));
        stats.put("incidents", incidents.get());
        stats.put("loginFailures", loginFailures.get());
        stats.put("perServer", workers.values().stream().map(Worker::view).toList());
        return stats;
    }

    @Override
    public void close() {
        running = false;
        workers.values().forEach(Worker::stop);
        workers.clear();
    }

    /** eMule search trees: keywords ANDed, optionally with a file type and a size range. */
    static final class SearchTrees {
        private SearchTrees() {
        }

        /** @param size {lowest, highest} in bytes, 0 for no bound; null for none */
        static byte[] build(String keywords, String type, long[] size) {
            List<byte[]> parts = new ArrayList<>();
            for (String word : keywords.split(" +")) {
                if (!word.isEmpty()) parts.add(keyword(word));
            }
            if (type != null) parts.add(fileType(type));
            if (size != null) {
                if (size[0] > 0) parts.add(fileSize(size[0] - 1, SearchQuery.OP_GREATER));
                if (size[1] > 0) parts.add(fileSize(size[1], SearchQuery.OP_LESS));
            }
            return and(parts);
        }

        private static byte[] and(List<byte[]> parts) {
            if (parts.size() == 1) return parts.getFirst();
            byte[] rest = and(parts.subList(1, parts.size()));
            byte[] first = parts.getFirst();
            return ByteBuffer.allocate(2 + first.length + rest.length)
                    .put((byte) 0x00).put(SearchQuery.OP_AND).put(first).put(rest).array();
        }

        private static byte[] keyword(String word) {
            byte[] value = word.getBytes(StandardCharsets.UTF_8);
            return ByteBuffer.allocate(3 + value.length).order(ByteOrder.LITTLE_ENDIAN)
                    .put((byte) 0x01).putShort((short) value.length).put(value).array();
        }

        private static byte[] fileType(String type) {
            byte[] value = type.getBytes(StandardCharsets.UTF_8);
            return ByteBuffer.allocate(6 + value.length).order(ByteOrder.LITTLE_ENDIAN)
                    .put((byte) 0x02).putShort((short) value.length).put(value)
                    .putShort((short) 1).put(SearchQuery.ID_FILETYPE).array();
        }

        private static byte[] fileSize(long bytes, byte operator) {
            return ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
                    .put((byte) 0x03).putInt((int) bytes).put(operator)
                    .putShort((short) 1).put(SearchQuery.ID_FILESIZE).array();
        }
    }
}
