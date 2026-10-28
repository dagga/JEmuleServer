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

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.ConcurrentMergeScheduler;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MergePolicy;
import org.apache.lucene.index.MergeScheduler;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.search.Weight;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.jemule.core.event.EventManager;
import org.jemule.core.event.FileEvent;
import org.jemule.protocol.FileTypes;
import org.jemule.protocol.Tag;
import org.jemule.security.ChildAbuseFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.StampedLock;

/**
 * Manages the global file index for the server.
 * <p>
 * The files and the words of their names live in a Lucene index, on disk next to the database and read through mmap,
 * outside the heap. The database stays the reference: a clean close records the same token in the index and in the
 * database, and the next start reopens the index only if both still hold it, with the same child abuse keywords;
 * otherwise (a crash, a restored backup, other keywords) it rebuilds the index from the database. The heap only
 * keeps what changes all the time or must be scanned by substring: the online sources of the files ({@link
 * #liveSources}), the dictionary of the words and of the media tag values, and the writes not yet visible to the
 * searches.
 * <p>
 * A file name keyword matches the files with a word containing it, found by the {@link WordDictionary}, so that each
 * keyword becomes the set of those words. A query that translates exactly (name keywords, size, source count, type,
 * extension, media tags and their AND / OR / NOT) is answered by Lucene alone, sorted by source count; any other query
 * (complete sources, a tag given by name) takes Lucene's candidates and tests each with {@link SearchQuery#test}.
 * <p>
 * The writes reach the searches at the next refresh: at the next search by default, every second on a running server
 * ({@link #refreshEvery}). The lookups by hash (publishing, {@link #contains}, {@link #removeFile}) see them at once:
 * the files written since the last refresh are kept in {@link #pending}.
 */
public class FileIndex implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(FileIndex.class);
    private static final Comparator<FileMetadata> SOURCE_COUNT_ORDER =
            Comparator.comparingInt(FileMetadata::sourceCount).reversed();
    /** Lucene fields: the hash in lower case for hexadecimal hashes ({@link #id}), the hash as stored, the name... */
    private static final String ID = "id", HASH = "hash", NAME = "name", TYPE = "type", SIZE = "size", SOURCES = "src",
            WORD = "w", EFFECTIVE_TYPE = "t", EXTENSION = "ext", TAGS = "tags";
    /** Media tags indexed for the filters; a file only gets the first tag of each, as {@link SearchQuery} reads it. */
    private static final byte[] STRING_TAGS = {SearchQuery.ID_MEDIA_ARTIST, SearchQuery.ID_MEDIA_ALBUM,
            SearchQuery.ID_MEDIA_TITLE, SearchQuery.ID_MEDIA_CODEC};
    private static final byte[] NUMERIC_TAGS = {SearchQuery.ID_MEDIA_LENGTH, SearchQuery.ID_MEDIA_BITRATE,
            SearchQuery.ID_FILERATING};
    /** Tags whose value is too long for a Lucene term (32,766 bytes) on some file, by field: those files get tested. */
    private static final String LONG_TAG = "longtag";
    private static final int MAX_TERM_BYTES = 32_766;
    private static final Sort BY_SOURCES = new Sort(new SortField(SOURCES, SortField.Type.LONG, true));
    /** Writes kept for the lookups beyond which the index is refreshed at once, whatever the refresh period. */
    static final int MAX_UNREFRESHED = 20_000;
    /** Database stat holding the token of the last clean close of the index ({@link #close}). */
    static final String INDEX_TOKEN_STAT = "file_index_token";
    /** Commit data of the index: its format, the token of its clean close, the keywords its files were checked with. */
    private static final String FORMAT_KEY = "format", TOKEN_KEY = "token", FILTER_KEY = "filter";
    /** Version of the documents and of their codec: an index of another version is rebuilt. */
    private static final String FORMAT = "2";
    /** Value of {@link #pending} for a removed file. */
    private static final Object REMOVED = new Object();

    private final Directory directory;
    private final IndexWriter writer;
    private final SearcherManager searchers;
    /**
     * Taken for reading by every write of the index, with the record of the write in {@link #pending}; for writing
     * by a refresh, to set aside the writes its new searcher will see. A StampedLock: a ReentrantReadWriteLock may
     * lose a read lock to an OutOfMemoryError, and then never let a refresh through.
     */
    private final StampedLock lock = new StampedLock();
    /** Files written since the last refresh, by {@link #id}: their last state (without sources), or {@link #REMOVED}. */
    private volatile Map<String, Object> pending = new ConcurrentHashMap<>();
    /** The writes of {@link #pending} a running refresh makes visible, looked up until it ends. */
    private volatile Map<String, Object> refreshing = Map.of();
    private final Object refreshLock = new Object();
    /** The writes of a file are made one at a time: the lookup of its state and its write go together. */
    private final Object[] stripes = new Object[64];
    /** Indexed files: read without any lock (admin, metrics, full index). */
    private final AtomicInteger indexedFiles = new AtomicInteger();
    /** Online sources of the files that have some, by {@link #id}: never an empty map. */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, ClientState>> liveSources = new ConcurrentHashMap<>();
    /** Every word of the index, for {@link WordDictionary}; a word left without file stays until the restart. */
    private final Set<String> words = ConcurrentHashMap.newKeySet();
    /** Words of {@link #words}, searched by substring. */
    private final WordDictionary dictionary = new WordDictionary();
    /** Distinct normalized values of each string media tag, by field; a value left without file stays. */
    private final Map<String, Set<String>> tagValues = new ConcurrentHashMap<>();
    /** Fields of the string tags too long to be indexed on some file. */
    private final Set<String> longTags = ConcurrentHashMap.newKeySet();
    /** Refreshes the index on a running server; null when the searches refresh it. */
    private volatile ScheduledExecutorService refresher;
    private volatile boolean closed;
    /** Whether the index is on disk, to be reopened by the next start. */
    private final boolean onDisk;
    /** Whether the index of the last run was reopened instead of rebuilt. */
    private final boolean reused;
    /** Fingerprint of the child abuse keywords the indexed files were checked with when the index was built. */
    private final String checkedWith;

    private final DatabaseManager db;
    /** Runs the database writes: the caller's thread by default, the ingestion thread on a running server. */
    private volatile java.util.concurrent.Executor persistence = Runnable::run;
    private final EventManager eventManager;
    private final ChildAbuseFilter abuseFilter;
    /** Files the child abuse filter rejected since the start. */
    private final LongAdder childAbuseRejected = new LongAdder();
    /** Searches refused since the start because their terms match the child abuse filter. */
    private final LongAdder childAbuseQueries = new LongAdder();
    /** Names recorded lately: a file that comes back with every search is written once. */
    private final Map<String, Boolean> recentAbuseNames = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > 10_000;
        }
    };
    /** Most files indexed: new hashes beyond it are refused (Integer.MAX_VALUE: no limit). */
    private volatile java.util.function.IntSupplier maxFiles = () -> Integer.MAX_VALUE;
    /** True when the heap or the disk cannot take more files: new hashes are refused too. */
    private volatile java.util.function.BooleanSupplier heapFull = () -> false;
    /** New hashes refused because the index was full (since server start). */
    private final LongAdder refusedFull = new LongAdder();
    /** Count of files whose hash was not indexed yet, per ingestion channel (since server start). */
    private final Map<FileSource, LongAdder> newFilesBySource = new ConcurrentHashMap<>();

    // LRU Cache for searches (Thread-safe via Collections.synchronizedMap)
    private final Map<String, List<FileMetadata>> searchCache = Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<FileMetadata>> eldest) {
            return size() > 100; // Limit to 100 entries
        }
    });

    /**
     * Constructs a FileIndex, loading the files of the database into a new Lucene index. Files of the database that
     * the child abuse filter rejects are deleted from it instead of being loaded.
     *
     * @param db           The database manager for persistence.
     * @param eventManager The event manager for broadcasting file events.
     * @param abuseFilter  The child abuse filter applied to every published or loaded file.
     * @param path         Directory of the Lucene index, emptied first; null for an index in the heap (tests).
     */
    public FileIndex(DatabaseManager db, EventManager eventManager, ChildAbuseFilter abuseFilter, Path path) {
        this.db = db;
        this.eventManager = eventManager;
        this.abuseFilter = abuseFilter;
        for (int i = 0; i < stripes.length; i++) stripes[i] = new Object();
        Directory opened = null;
        IndexWriter opening;
        String keywords = String.valueOf(abuseFilter.fingerprint());
        boolean reopen = false;
        try {
            opened = path == null ? new ByteBuffersDirectory() : FSDirectory.open(Files.createDirectories(path));
            reopen = path != null && db != null && matchesDatabase(opened, db, keywords);
            try {
                opening = new IndexWriter(opened, writerConfig(reopen));
            } catch (LockObtainFailedException e) {
                // Another server of this JVM (tests) or of this machine on the same database path
                log.warn("The file index at {} is used by another server: this one keeps its index in the heap", path);
                opened.close();
                opened = new ByteBuffersDirectory();
                reopen = false;
                opening = new IndexWriter(opened, writerConfig(false));
            }
            // Committed without token: until a clean close records one, the next start rebuilds the index. On a
            // rebuild, the files of the index of the last run go now, before the new one takes their room on the disk
            opening.setLiveCommitData(Map.of(FORMAT_KEY, FORMAT).entrySet());
            opening.commit();
            searchers = new SearcherManager(opening, null);
        } catch (IOException e) {
            if (opened != null) {
                try {
                    opened.close();
                } catch (IOException ignored) {
                    // already failing
                }
            }
            throw new UncheckedIOException("Cannot open the file index at " + path, e);
        }
        directory = opened;
        writer = opening;
        onDisk = directory instanceof FSDirectory;
        reused = reopen;
        checkedWith = keywords;
        long start = System.nanoTime();
        if (reused) {
            try {
                readDictionaries();
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read the file index at " + path, e);
            }
            log.info("File index reopened at {}: {} files, {} ms", path, fileCount(), (System.nanoTime() - start) / 1_000_000);
        } else if (db != null) {
            try {
                load(db);
            } catch (Exception e) {
                // If DB fails during load, we continue with the files read so far
                // DatabaseManager with CircuitBreaker should handle specific errors
                log.error("Loading the files of the database failed: {}", e.toString());
            }
            log.info("File index built from the database{}: {} files, {} s", path == null ? "" : " at " + path,
                    fileCount(), (System.nanoTime() - start) / 1_000_000_000);
        }
    }

    /**
     * Whether the index in {@code directory} holds the files of the database: closed cleanly with the token the
     * database holds, in the current format, its files checked with the current child abuse keywords.
     */
    private static boolean matchesDatabase(Directory directory, DatabaseManager db, String keywords) {
        try {
            if (!DirectoryReader.indexExists(directory)) return false;
            Map<String, String> data = SegmentInfos.readLatestCommit(directory).getUserData();
            long token = db.getStat(INDEX_TOKEN_STAT, 0);
            boolean matches = token != 0 && String.valueOf(token).equals(data.get(TOKEN_KEY))
                    && FORMAT.equals(data.get(FORMAT_KEY)) && keywords.equals(data.get(FILTER_KEY));
            if (!matches) {
                log.info("File index rebuilt: {}", data.get(TOKEN_KEY) == null ? "the last run did not close it"
                        : !String.valueOf(token).equals(data.get(TOKEN_KEY)) ? "the database changed since"
                        : !FORMAT.equals(data.get(FORMAT_KEY)) ? "another format" : "other child abuse keywords");
            }
            return matches;
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot read the file index of the last run ({}): it is rebuilt", e.toString());
            return false;
        }
    }

    /** The words, the media tag values and the file count of a reopened index. */
    private void readDictionaries() throws IOException {
        IndexSearcher searcher = acquire();
        try {
            for (LeafReaderContext leaf : searcher.getIndexReader().leaves()) {
                for (BytesRef term : terms(leaf, WORD)) {
                    String word = term.utf8ToString();
                    if (words.add(word)) dictionary.add(word);
                }
                for (byte id : STRING_TAGS) {
                    for (BytesRef value : terms(leaf, tagField(id))) values(tagField(id)).add(value.utf8ToString());
                }
                for (BytesRef field : terms(leaf, LONG_TAG)) longTags.add(field.utf8ToString());
            }
            indexedFiles.set(searcher.getIndexReader().numDocs());
        } finally {
            release(searcher);
        }
    }

    /** The terms of a field in a leaf, read once. */
    private static Iterable<BytesRef> terms(LeafReaderContext leaf, String field) throws IOException {
        Terms terms = leaf.reader().terms(field);
        TermsEnum it = terms == null ? TermsEnum.EMPTY : terms.iterator();
        return () -> new Iterator<>() {
            private BytesRef next = advance();

            private BytesRef advance() {
                try {
                    return it.next();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public boolean hasNext() {
                return next != null;
            }

            @Override
            public BytesRef next() {
                if (next == null) throw new NoSuchElementException();
                BytesRef current = BytesRef.deepCopyOf(next);
                next = advance();
                return current;
            }
        };
    }

    /** Whether the index of the last run was reopened instead of rebuilt from the database. */
    boolean reused() {
        return reused;
    }

    public FileIndex(DatabaseManager db, EventManager eventManager, ChildAbuseFilter abuseFilter) {
        this(db, eventManager, abuseFilter, null);
    }

    public FileIndex(DatabaseManager db, EventManager eventManager) {
        this(db, eventManager, ChildAbuseFilter.defaults());
    }

    public FileIndex(DatabaseManager db) {
        this(db, null);
    }

    private static IndexWriterConfig writerConfig(boolean reopen) {
        return new IndexWriterConfig()
                .setOpenMode(reopen ? IndexWriterConfig.OpenMode.APPEND : IndexWriterConfig.OpenMode.CREATE)
                .setCodec(new FileIndexCodec())
                .setRAMBufferSizeMB(32)
                .setMergeScheduler(mergeScheduler())
                .setCommitOnClose(false);
    }

    /** Merges segments at the priority of the ingestion, below the clients and the searches. */
    private static MergeScheduler mergeScheduler() {
        return new ConcurrentMergeScheduler() {
            @Override
            protected synchronized MergeThread getMergeThread(MergeSource mergeSource, MergePolicy.OneMerge merge)
                    throws IOException {
                MergeThread thread = super.getMergeThread(mergeSource, merge);
                thread.setPriority(ServerThreads.Tier.INGESTION.priority);
                return thread;
            }
        };
    }

    /**
     * Indexes every file of the database; of two hashes differing by case, the first one read. The child abuse files
     * are deleted afterwards, the database being locked during the read.
     */
    private void load(DatabaseManager db) throws IOException {
        List<FileMetadata> rejected = new ArrayList<>();
        Md4Set seen = new Md4Set();
        Set<String> seenOthers = new HashSet<>();
        IOException[] failure = new IOException[1];
        db.forEachFile(meta -> {
            if (failure[0] != null) return;
            if (abuseFilter.matches(meta.name())) {
                rejected.add(meta);
                return;
            }
            String id = id(meta.hash());
            if (!(Md4Set.parse(id) == null ? seenOthers.add(id) : seen.add(id))) return;
            try {
                writer.addDocument(document(meta.indexed(), meta.sourceCount()));
                indexedFiles.incrementAndGet();
            } catch (IOException e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) throw failure[0];
        for (FileMetadata meta : rejected) {
            recordChildAbuseName(meta.name());
            db.deleteFile(meta.hash());
        }
        if (!rejected.isEmpty()) {
            log.warn("Deleted {} child abuse file(s) from the database", rejected.size());
        }
        searchers.maybeRefreshBlocking();
    }

    /**
     * Refreshes the index every {@code millis} milliseconds on a thread of the ingestion tier, instead of at the next
     * search: the searches never wait for a refresh, and see the writes at most that late.
     */
    public void refreshEvery(long millis) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                ServerThreads.factory(ServerThreads.Tier.INGESTION, "index-refresh"));
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                refresh();
            } catch (RuntimeException e) {
                log.error("Refreshing the file index failed: {}", e.toString());
            }
        }, millis, millis, TimeUnit.MILLISECONDS);
        ScheduledExecutorService previous = refresher;
        refresher = scheduler;
        if (previous != null) previous.shutdownNow();
    }

    /** Makes the writes visible to the searches at once. */
    public void refresh() {
        synchronized (refreshLock) {
            if (closed) return;
            Map<String, Object> swapped;
            long stamp = lock.writeLock();
            try {
                swapped = pending;
                if (swapped.isEmpty()) return;
                refreshing = swapped;
                pending = new ConcurrentHashMap<>();
            } finally {
                lock.unlockWrite(stamp);
            }
            try {
                searchers.maybeRefreshBlocking();
            } catch (IOException e) {
                // The writes stay looked up, the later ones first
                stamp = lock.writeLock();
                try {
                    swapped.forEach(pending::putIfAbsent);
                } finally {
                    lock.unlockWrite(stamp);
                }
                throw new UncheckedIOException(e);
            } finally {
                refreshing = Map.of();
            }
        }
        searchCache.clear();
    }

    /** Before a search: the writes made since the last refresh, unless a thread refreshes the index regularly. */
    private void refreshForSearch() {
        if (refresher == null && !pending.isEmpty()) refresh();
    }

    /**
     * Checks a file name against the child abuse filter. The name of a rejected file is recorded among the banned
     * names (reason {@link ChildAbuseFilter#BAN_REASON}), to be exported from the admin panel.
     *
     * @return true if the file must be rejected
     */
    public boolean isChildAbuse(String hash, String name) {
        if (!abuseFilter.matches(name)) return false;
        childAbuseRejected.increment();
        log.debug("Child abuse file rejected: hash={} name='{}'", hash, name);
        recordChildAbuseName(name);
        return true;
    }

    /**
     * Records the name of a file the child abuse filter rejected among the banned names, in the database only: the
     * filter rejects that name anyway, and the names would fill the heap. Written by the persistence executor.
     */
    public void recordChildAbuseName(String name) {
        String normalized = org.jemule.security.FakeFileDetector.normalize(name);
        if (normalized == null || db == null) return;
        synchronized (recentAbuseNames) {
            if (recentAbuseNames.put(normalized, Boolean.TRUE) != null) return;
        }
        persistence.execute(() -> db.addBannedNameIfAbsent(normalized, ChildAbuseFilter.BAN_REASON));
    }

    /**
     * True when the terms of a search match the child abuse filter: such a search gets no result and is sent
     * neither to Kad nor to other servers. Counted.
     */
    public boolean isChildAbuseQuery(SearchQuery query) {
        if (query == null || !abuseFilter.matches(SearchQuery.terms(query))) return false;
        childAbuseQueries.increment();
        return true;
    }

    /** Searches refused since the start because their terms match the child abuse filter. */
    public long childAbuseQueryCount() {
        return childAbuseQueries.sum();
    }

    /** Files the child abuse filter rejected since the start. */
    public long childAbuseRejectedCount() {
        return childAbuseRejected.sum();
    }

    /**
     * Limits the index: once {@code maxFiles} files are indexed or {@code full} says so (heap or disk), files of
     * unknown hashes are refused, whatever channel brings them; known files still get their new sources.
     */
    public void setCapacity(java.util.function.IntSupplier maxFiles, java.util.function.BooleanSupplier full) {
        this.maxFiles = maxFiles;
        this.heapFull = full;
    }

    /** True when new files are refused: maxFiles reached, or the heap or the disk full. */
    public boolean isFull() {
        return fileCount() >= maxFiles.getAsInt() || heapFull.getAsBoolean();
    }

    /** New files refused because the index was full, since the server started. */
    public long refusedFullCount() {
        return refusedFull.sum();
    }

    /**
     * Registers a published file. If the file (same hash) is already known, the publisher's sources are
     * <b>added</b> to the existing entry: every client sharing a file must be returned as a source.
     *
     * @param meta The file metadata to add, carrying the publishing client(s) in {@link FileMetadata#sources()}.
     * @return the file as now stored in the index for this hash, null for a new file refused by a full index
     */
    public FileMetadata addFile(FileMetadata meta) {
        return addFile(meta, FileSource.UNKNOWN);
    }

    /**
     * Registers a published file, recording its ingestion channel for the monitoring counters.
     */
    public FileMetadata addFile(FileMetadata meta, FileSource source) {
        Writes writes = new Writes(1);
        FileMetadata stored = addFileInternal(meta, writes, source);
        persist(writes);
        return stored;
    }

    /**
     * Adds a group of files to the index and persists changed entries in one database transaction.
     */
    public List<FileMetadata> addFiles(Collection<FileMetadata> files) {
        return addFiles(files, FileSource.UNKNOWN);
    }

    /**
     * Adds a group of files to the index, recording their ingestion channel for the monitoring counters.
     */
    public List<FileMetadata> addFiles(Collection<FileMetadata> files, FileSource source) {
        if (files == null || files.isEmpty()) return List.of();
        Writes writes = new Writes(files.size());
        List<FileMetadata> stored = new ArrayList<>(files.size());
        for (FileMetadata file : files) {
            if (file == null || file.hash() == null) continue;
            FileMetadata added = addFileInternal(file, writes, source);
            if (added != null) stored.add(added);
        }
        persist(writes);
        return List.copyOf(stored);
    }

    /** Database writes of a call: the files of unknown hashes, and the higher source counts of known ones. */
    private record Writes(List<FileMetadata> files, List<FileMetadata> sourceCounts) {
        Writes(int expected) {
            this(new ArrayList<>(expected), new ArrayList<>(0));
        }
    }

    private FileMetadata addFileInternal(FileMetadata meta, Writes writes, FileSource source) {
        // Checked before the lock: a full index refuses the new hashes of every channel at once
        boolean full = isFull();
        FileMetadata incoming = meta.indexed();
        String id = id(incoming.hash());
        boolean isNew = false;
        boolean isNewHash = false;
        boolean sourceCountChanged = false;
        FileMetadata replaced = null;
        FileMetadata stored;
        synchronized (stripe(id)) {
            long stamp = lock.readLock();
            try {
                FileMetadata existing = stored(id);
                if (existing == null && full) {
                    refusedFull.increment();
                    return null;
                }
                ConcurrentHashMap<String, ClientState> live = liveSources.get(id);
                if (existing == null || (live == null && !incoming.sources().isEmpty())) {
                    // unknown file, or a file without online source (e.g. loaded from the database) published by a
                    // client: take the new metadata, under the hash the database knows. A search result of a known
                    // hash keeps the entry below.
                    FileMetadata selected = existing == null ? incoming : withHash(incoming, existing.hash());
                    int knownSources = existing == null ? 0 : existing.sourceCount();
                    if (selected.sourceCount() < knownSources) {
                        selected = selected.withSourceCount(knownSources);
                    }
                    isNew = true;
                    isNewHash = existing == null;
                    replaced = existing;
                    stored = selected;
                    if (!stored.sources().isEmpty()) liveSources.put(id, stored.sources());
                    int count = stored.sourceCount();
                    if (existing == null) {
                        writer.addDocument(document(stored, count));
                        indexedFiles.incrementAndGet();
                    } else if (sameContent(existing, stored)) {
                        // Published again as it is indexed: only its count may change
                        if (count != existing.sourceCount()) {
                            writer.updateNumericDocValue(new Term(ID, id), SOURCES, count);
                        }
                    } else {
                        writer.updateDocument(new Term(ID, id), document(stored, count));
                    }
                    pending.put(id, withoutSources(stored, count));
                } else {
                    if (!incoming.sources().isEmpty()) {
                        live = liveSources.compute(id, (k, sources) -> {
                            if (sources == null) sources = new ConcurrentHashMap<>();
                            sources.putAll(incoming.sources());
                            return sources;
                        });
                    }
                    int indexedCount = existing.sourceCount();
                    int knownCount = Math.max(indexedCount, live == null ? 0 : live.size());
                    int mergedSourceCount = Math.max(knownCount, incoming.sourceCount());
                    sourceCountChanged = mergedSourceCount > knownCount;
                    // The count of the index is raised to the online sources too, so that the searches sort and
                    // filter on it as on the files they return
                    if (mergedSourceCount != indexedCount) {
                        writer.updateNumericDocValue(new Term(ID, id), SOURCES, mergedSourceCount);
                        pending.put(id, withoutSources(existing, mergedSourceCount));
                    }
                    stored = withSources(withoutSources(existing, mergedSourceCount), live);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                lock.unlockRead(stamp);
            }
        }
        if (pending.size() > MAX_UNREFRESHED) refresh();
        if (isNew) {
            if (isNewHash) {
                newFilesBySource.computeIfAbsent(source, k -> new LongAdder()).increment();
            }
            searchCache.clear(); // Invalidate cache when adding a new file
            // The index holds every hash of the database: an unknown hash is written whole, a known one only when
            // its source count rose
            if (isNewHash) {
                writes.files().add(stored);
            } else if (stored.sourceCount() > replaced.sourceCount()) {
                writes.sourceCounts().add(stored);
            }
            if (eventManager != null) {
                eventManager.broadcast(new FileEvent(FileEvent.PUBLISHED, stored.name(), stored.hash(), "File published: " + stored.name()));
            }
        } else if (sourceCountChanged) {
            searchCache.clear();
            writes.sourceCounts().add(stored); // the hash is already stored: only its count is written
        }
        return stored;
    }

    private static FileMetadata withHash(FileMetadata meta, String hash) {
        return hash.equals(meta.hash()) ? meta
                : new FileMetadata(hash, meta.name(), meta.size(), meta.type(), meta.tags(), meta.sources());
    }

    /** Whether the index would hold the same document for both files, but their source count. */
    private static boolean sameContent(FileMetadata a, FileMetadata b) {
        return a.hash().equals(b.hash()) && Objects.equals(a.name(), b.name()) && a.size() == b.size()
                && Objects.equals(a.type(), b.type()) && Arrays.equals(encodeTags(a.tags()), encodeTags(b.tags()));
    }

    /** The file as the index stores it: no online source, {@code count} as its source count. */
    private static FileMetadata withoutSources(FileMetadata meta, int count) {
        return FileMetadata.withoutSources(meta.hash(), meta.name(), meta.size(), meta.type(),
                meta.withSourceCount(count).tags());
    }

    /** A file of the index with its online sources, when it has some. */
    private static FileMetadata withSources(FileMetadata stored, ConcurrentHashMap<String, ClientState> sources) {
        return sources == null ? stored
                : new FileMetadata(stored.hash(), stored.name(), stored.size(), stored.type(), stored.tags(), sources);
    }

    /**
     * Removes a disconnected client from the sources of every file it offered. Files left without any
     * source stay indexed and searchable, like the files loaded from the database.
     */
    public void removeSource(ClientState client) {
        String key = sourceKey(client);
        for (String hash : client.publishedHashes()) {
            liveSources.computeIfPresent(id(hash), (k, sources) -> {
                sources.remove(key);
                return sources.isEmpty() ? null : sources;
            });
        }
        client.publishedHashes().clear();
    }

    /**
     * Removes a file from the index and the database (e.g. after a ban). Its current sources are not told: they
     * simply stop being returned.
     *
     * @return true if the file was indexed
     */
    public boolean removeFile(String hash) {
        if (hash == null) return false;
        String id = id(hash);
        String hashToDelete;
        synchronized (stripe(id)) {
            long stamp = lock.readLock();
            try {
                FileMetadata existing = stored(id);
                if (existing == null) return false;
                hashToDelete = existing.hash();
                writer.deleteDocuments(new Term(ID, id));
                pending.put(id, REMOVED);
                liveSources.remove(id);
                indexedFiles.decrementAndGet();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                lock.unlockRead(stamp);
            }
        }
        if (pending.size() > MAX_UNREFRESHED) refresh();
        searchCache.clear();
        if (db != null) {
            sealPendingWrites();
            persistence.execute(() -> db.deleteFile(hashToDelete)); // after any queued write of this file
        }
        return true;
    }

    /**
     * Removes the files indexed under a name, compared in lower case without the spaces around it (a banned name).
     *
     * @return the number of files removed
     */
    public int removeFilesNamed(String name) {
        String wanted = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        if (wanted.isEmpty()) return 0;
        // Such a file has every word of the name, and only those
        BooleanQuery.Builder candidates = new BooleanQuery.Builder().add(new MatchAllDocsQuery(), Occur.FILTER);
        for (String word : new LinkedHashSet<>(List.of(Keywords.split(wanted)))) {
            candidates.add(new TermQuery(new Term(WORD, word)), Occur.FILTER);
        }
        List<String> hashes = new ArrayList<>();
        refreshForSearch();
        IndexSearcher searcher = acquire();
        try {
            forEachMatch(searcher, candidates.build(), (stored, doc, count) -> {
                Document fields = stored.document(doc, Set.of(HASH, NAME));
                String indexed = fields.get(NAME);
                if (indexed != null && indexed.length() >= wanted.length()
                        && indexed.strip().toLowerCase(Locale.ROOT).equals(wanted)) {
                    hashes.add(fields.get(HASH));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            release(searcher);
        }
        int removed = 0;
        for (String hash : hashes) {
            if (removeFile(hash)) removed++;
        }
        return removed;
    }

    /**
     * Runs the database writes on {@code executor}, one at a time and in order, instead of the caller's thread: a
     * client publishing files or a search delivering results then never waits for the database.
     */
    public void setPersistenceExecutor(java.util.concurrent.Executor executor) {
        this.persistence = executor == null ? Runnable::run : executor;
    }

    /** Most files written in one transaction by a {@link PendingWrites}: a larger one waits for the next. */
    static final int MAX_PENDING_WRITES = 10_000;

    /**
     * Writes queued and not started yet: the files published one by one join the same transaction instead of a
     * transaction each (which MySQL syncs to the disk, about 2 ms). Guarded by {@link #pendingLock}.
     */
    private PendingWrites pendingWrites;
    private final Object pendingLock = new Object();

    /** The writes of one transaction, the last state of each file kept. */
    private static final class PendingWrites {
        final Map<String, FileMetadata> files = new LinkedHashMap<>();
        final Map<String, FileMetadata> sourceCounts = new LinkedHashMap<>();

        void add(Writes writes) {
            for (FileMetadata file : writes.files()) {
                sourceCounts.remove(file.hash());
                files.put(file.hash(), file);
            }
            for (FileMetadata file : writes.sourceCounts()) {
                // A file written in this transaction is written again with its new count
                if (files.containsKey(file.hash())) files.put(file.hash(), file);
                else sourceCounts.put(file.hash(), file);
            }
        }

        int size() {
            return files.size() + sourceCounts.size();
        }
    }

    private void persist(Writes writes) {
        if (db == null || writes.files().isEmpty() && writes.sourceCounts().isEmpty()) return;
        PendingWrites started = null;
        synchronized (pendingLock) {
            if (pendingWrites == null || pendingWrites.size() >= MAX_PENDING_WRITES) {
                pendingWrites = started = new PendingWrites();
            }
            pendingWrites.add(writes);
        }
        if (started != null) {
            PendingWrites batch = started;
            persistence.execute(() -> {
                sealPendingWrites(batch);
                if (!batch.files.isEmpty()) db.saveFiles(batch.files.values());
                if (!batch.sourceCounts.isEmpty()) db.saveSourceCounts(batch.sourceCounts.values());
            });
        }
    }

    /**
     * Writes queued after this call start a new transaction, queued after what the caller queues next: a deletion
     * then never runs before a write of the same file published before it, nor after one published after it.
     */
    private void sealPendingWrites() {
        synchronized (pendingLock) {
            pendingWrites = null;
        }
    }

    private void sealPendingWrites(PendingWrites batch) {
        synchronized (pendingLock) {
            if (pendingWrites == batch) pendingWrites = null;
        }
    }

    /** Key of a client in {@link FileMetadata#sources()}: unique per connection (several clients may share one IP). */
    public static String sourceKey(ClientState client) {
        return String.valueOf(client.connectionId());
    }

    /** Key of a file in the index: a hexadecimal hash in lower case (case ignored), any other hash as it is. */
    static String id(String hash) {
        return Md4Set.parse(hash) == null ? hash : hash.toLowerCase(Locale.ROOT);
    }

    private Object stripe(String id) {
        return stripes[id.hashCode() & (stripes.length - 1)];
    }

    /**
     * The file stored under {@code id} without its online sources, its source count as indexed: as written since the
     * last refresh, or as the searcher sees it; null if none. Called under the stripe of the file.
     */
    private FileMetadata stored(String id) throws IOException {
        Object written = pending.get(id);
        if (written == null) written = refreshing.get(id);
        if (written != null) return written == REMOVED ? null : (FileMetadata) written;
        IndexSearcher searcher = acquire();
        try {
            TopFieldDocs top = searcher.search(new TermQuery(new Term(ID, id)), 1, BY_SOURCES);
            if (top.scoreDocs.length == 0) return null;
            ScoreDoc hit = top.scoreDocs[0];
            return file(searcher.storedFields().document(hit.doc), (Long) ((FieldDoc) hit).fields[0]);
        } finally {
            release(searcher);
        }
    }

    /**
     * Retrieves a list of clients (sources) currently sharing a file.
     * Implements IP proximity sorting and diversity via shuffling.
     *
     * @param hash      The MD4 hash of the file.
     * @param requester The client state of the requester (for proximity matching).
     * @param limit     The maximum number of sources to return.
     * @return A list of {@link ClientState} representing sources.
     */
    public List<ClientState> getSources(String hash, ClientState requester, int limit) {
        if (hash == null) return Collections.emptyList();
        ConcurrentHashMap<String, ClientState> sources = liveSources.get(id(hash));
        if (sources == null || sources.isEmpty()) return Collections.emptyList();
        List<ClientState> all = new ArrayList<>(sources.values());
        if (all.isEmpty()) return Collections.emptyList();
        if (all.size() <= limit && requester == null) {
            Collections.shuffle(all);
            return all;
        }

        // Diversity: start by shuffling
        Collections.shuffle(all);

        if (requester != null) {
            byte[] reqIp = requester.address().getAddress();
            // Sort to prioritize IP proximity (same /16 or /24)
            all.sort((a, b) -> {
                int scoreA = calculateProximity(a.address().getAddress(), reqIp);
                int scoreB = calculateProximity(b.address().getAddress(), reqIp);
                return Integer.compare(scoreB, scoreA); // Highest score first
            });
        }

        return all.subList(0, Math.min(limit, all.size()));
    }

    private int calculateProximity(byte[] ipA, byte[] ipB) {
        if (ipA.length != ipB.length) return 0;
        int score = 0;
        for (int i = 0; i < ipA.length; i++) {
            if (ipA[i] == ipB[i]) score++;
            else break;
        }
        return score;
    }

    /**
     * Executes a complex search query using boolean logic and filters, on every known file, including the
     * files loaded from the database and the files whose sources are all offline (FT_SOURCES = 0).
     *
     * @param query The parsed {@link SearchQuery}.
     * @param limit The maximum number of results.
     * @return A list of matching {@link FileMetadata}, the most sources first.
     */
    public List<FileMetadata> searchComplex(SearchQuery query, int limit) {
        if (eventManager != null) {
            eventManager.broadcast(new FileEvent(FileEvent.SEARCHED, query.toString(), "", "Complex search executed"));
        }
        if (limit <= 0) return List.of();
        refreshForSearch();
        IndexSearcher searcher = acquire();
        try {
            Translation translation = translate(query);
            if (translation.exact()) return topBySources(searcher, translation.query(), limit);
            Query candidates = translation.query();
            if (onlyFilesWithSources(query)) candidates = withSourcesOnly(searcher, candidates);
            return tested(searcher, candidates, query, limit);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            release(searcher);
        }
        // A file indexed before the keywords were reloaded may match them now: the senders of the results check
        // every file once more (SearchHandler, Server's UDP global search)
    }

    private List<FileMetadata> topBySources(IndexSearcher searcher, Query query, int limit) throws IOException {
        int n = Math.min(limit, searcher.count(query));
        if (n == 0) return List.of();
        TopFieldDocs top = searcher.search(query, n, BY_SOURCES);
        StoredFields stored = searcher.storedFields();
        List<FileMetadata> results = new ArrayList<>(top.scoreDocs.length);
        for (ScoreDoc hit : top.scoreDocs) {
            results.add(result(stored.document(hit.doc), (Long) ((FieldDoc) hit).fields[0]));
        }
        return results;
    }

    /** Every candidate is loaded and tested with the query. */
    private List<FileMetadata> tested(IndexSearcher searcher, Query candidates, SearchQuery query, int limit)
            throws IOException {
        List<FileMetadata> results = new ArrayList<>();
        forEachMatch(searcher, candidates, (stored, doc, count) -> {
            FileMetadata meta = result(stored.document(doc), count);
            if (query.test(meta)) results.add(meta);
        });
        return results.stream()
                .sorted(SOURCE_COUNT_ORDER)
                .limit(limit)
                .toList();
    }

    /** A document matching a query: its leaf's stored fields, its number in the leaf and its source count. */
    private interface Match {
        void accept(StoredFields stored, int doc, long sourceCount) throws IOException;
    }

    private static void forEachMatch(IndexSearcher searcher, Query query, Match match) throws IOException {
        Weight weight = searcher.createWeight(searcher.rewrite(query), ScoreMode.COMPLETE_NO_SCORES, 1f);
        for (LeafReaderContext leaf : searcher.getIndexReader().leaves()) {
            Scorer scorer = weight.scorer(leaf);
            if (scorer == null) continue;
            Bits live = leaf.reader().getLiveDocs();
            NumericDocValues sources = leaf.reader().getNumericDocValues(SOURCES);
            StoredFields stored = leaf.reader().storedFields();
            DocIdSetIterator it = scorer.iterator();
            for (int doc = it.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = it.nextDoc()) {
                if (live != null && !live.get(doc)) continue;
                long count = sources != null && sources.advanceExact(doc) ? sources.longValue() : 0;
                match.accept(stored, doc, count);
            }
        }
    }

    /**
     * True when a file without online source cannot match the query: a filter on the complete sources, which only
     * the online sources give, rejects it.
     */
    private static boolean onlyFilesWithSources(SearchQuery query) {
        return switch (query) {
            case SearchQuery.NumericQuery n when n.id() == SearchQuery.ID_COMPLETE_SOURCES ->
                    !n.test(FileMetadata.withoutSources("", "", 0, null, List.of()));
            case SearchQuery.AndQuery a -> onlyFilesWithSources(a.left()) || onlyFilesWithSources(a.right());
            case SearchQuery.OrQuery o -> onlyFilesWithSources(o.left()) && onlyFilesWithSources(o.right());
            case SearchQuery.NotQuery n -> onlyFilesWithSources(n.left());
            case null, default -> false;
        };
    }

    /** The candidates restricted to the files with online sources, when these are fewer. */
    private Query withSourcesOnly(IndexSearcher searcher, Query candidates) throws IOException {
        List<BytesRef> ids = new ArrayList<>();
        for (String id : liveSources.keySet()) ids.add(new BytesRef(id));
        if (ids.isEmpty()) return new MatchNoDocsQuery();
        if (ids.size() >= searcher.count(candidates)) return candidates;
        return new BooleanQuery.Builder().add(candidates, Occur.FILTER).add(new TermInSetQuery(ID, ids), Occur.FILTER)
                .build();
    }

    /** Whether {@code query} is answered by Lucene alone, no file tested (for the comparison with the heap index). */
    boolean isExact(SearchQuery query) {
        return translate(query).exact();
    }

    /** A Lucene query and whether it selects exactly the files the search query matches. */
    private record Translation(Query query, boolean exact) {
        static final Translation ALL = new Translation(new MatchAllDocsQuery(), true);
        static final Translation NONE = new Translation(new MatchNoDocsQuery(), true);
        static final Translation CANDIDATES_ALL = new Translation(new MatchAllDocsQuery(), false);
    }

    private Translation translate(SearchQuery query) {
        return switch (query) {
            case SearchQuery.TermQuery t -> switch (t.id()) {
                case SearchQuery.ID_FILENAME -> nameQuery(t.term());
                case SearchQuery.ID_FILETYPE -> new Translation(
                        new TermQuery(new Term(EFFECTIVE_TYPE, t.term().toLowerCase(Locale.ROOT))), true);
                // An extension with a dot ("tar.gz") is not one indexed word
                case SearchQuery.ID_FORMAT -> t.term().contains(".") ? Translation.CANDIDATES_ALL : new Translation(
                        new TermQuery(new Term(EXTENSION, t.term().toLowerCase(Locale.ROOT))), true);
                case SearchQuery.ID_MEDIA_CODEC, SearchQuery.ID_MEDIA_TITLE, SearchQuery.ID_MEDIA_ALBUM,
                     SearchQuery.ID_MEDIA_ARTIST -> tagQuery(t.id(), t.term());
                default -> Translation.ALL;
            };
            case SearchQuery.NumericQuery n -> switch (n.id()) {
                case SearchQuery.ID_FILESIZE -> new Translation(range(n.operator(), n.value(), SIZE_RANGE), true);
                case SearchQuery.ID_SOURCES -> new Translation(range(n.operator(), n.value(), SOURCES_RANGE), true);
                case SearchQuery.ID_MEDIA_LENGTH, SearchQuery.ID_MEDIA_BITRATE, SearchQuery.ID_FILERATING ->
                        new Translation(range(n.operator(), n.value(), tagRange(n.id())), true);
                // Counted on the online sources, which stay in the heap
                case SearchQuery.ID_COMPLETE_SOURCES -> Translation.CANDIDATES_ALL;
                default -> Translation.ALL;
            };
            case SearchQuery.AndQuery a -> {
                Translation left = translate(a.left()), right = translate(a.right());
                yield new Translation(new BooleanQuery.Builder().add(left.query(), Occur.FILTER)
                        .add(right.query(), Occur.FILTER).build(), left.exact() && right.exact());
            }
            case SearchQuery.OrQuery o -> {
                Translation left = translate(o.left()), right = translate(o.right());
                yield new Translation(new BooleanQuery.Builder().add(left.query(), Occur.SHOULD)
                        .add(right.query(), Occur.SHOULD).setMinimumNumberShouldMatch(1).build(),
                        left.exact() && right.exact());
            }
            case SearchQuery.NotQuery n -> {
                Translation left = translate(n.left()), right = translate(n.right());
                // Only an exact right side can be excluded: its candidates may hold files it does not match
                yield right.exact()
                        ? new Translation(new BooleanQuery.Builder().add(left.query(), Occur.FILTER)
                                .add(new MatchAllDocsQuery(), Occur.FILTER).add(right.query(), Occur.MUST_NOT).build(),
                                left.exact())
                        : new Translation(left.query(), false);
            }
            // Lambdas of the parser (a tag given by name): they match every file, but tested to be sure
            case null, default -> Translation.CANDIDATES_ALL;
        };
    }

    /**
     * A name keyword: for each of its words, the files with an indexed word containing it. Exact: a word of the
     * keyword (letters and digits only) can only lie inside one word of the name.
     */
    private Translation nameQuery(String term) {
        String[] tokens = Keywords.split(term);
        if (tokens.length == 0) return Translation.NONE; // a keyword without any letter/digit matches nothing
        BooleanQuery.Builder all = new BooleanQuery.Builder();
        for (String token : tokens) {
            List<BytesRef> containing = new ArrayList<>();
            for (String word : dictionary.wordsContaining(token, words)) containing.add(new BytesRef(word));
            if (containing.isEmpty()) return Translation.NONE;
            all.add(new TermInSetQuery(WORD, containing), Occur.FILTER);
        }
        return new Translation(all.build(), true);
    }

    /**
     * A string media tag containing {@code term} (both normalized): the files with one of the values of the
     * dictionary that contain it. Exact unless a file has a value of that tag too long to be indexed.
     */
    private Translation tagQuery(byte id, String term) {
        String field = tagField(id);
        String text = Keywords.normalize(term);
        List<BytesRef> containing = new ArrayList<>();
        for (String value : values(field)) {
            if (value.contains(text)) containing.add(new BytesRef(value));
        }
        Query values = containing.isEmpty() ? new MatchNoDocsQuery() : new TermInSetQuery(field, containing);
        if (!longTags.contains(field)) return new Translation(values, true);
        return new Translation(new BooleanQuery.Builder().add(values, Occur.SHOULD)
                .add(new TermQuery(new Term(LONG_TAG, field)), Occur.SHOULD).build(), false);
    }

    /** A numeric field: the files with a value in [lo, hi], and those with a value at all. */
    private interface RangeField {
        Query between(long lo, long hi);

        Query present();
    }

    private static final RangeField SIZE_RANGE = new RangeField() {
        public Query between(long lo, long hi) {
            return LongPoint.newRangeQuery(SIZE, lo, hi);
        }

        public Query present() {
            return new MatchAllDocsQuery();
        }
    };

    private static final RangeField SOURCES_RANGE = new RangeField() {
        public Query between(long lo, long hi) {
            return NumericDocValuesField.newSlowRangeQuery(SOURCES, lo, hi);
        }

        public Query present() {
            return new MatchAllDocsQuery();
        }
    };

    /** A numeric media tag: a file without it matches no comparison, as in {@link SearchQuery.NumericQuery}. */
    private static RangeField tagRange(byte id) {
        String field = numericField(id);
        return new RangeField() {
            public Query between(long lo, long hi) {
                return LongPoint.newRangeQuery(field, lo, hi);
            }

            public Query present() {
                return LongPoint.newRangeQuery(field, Long.MIN_VALUE, Long.MAX_VALUE);
            }
        };
    }

    /** The values satisfying {@code <value> <operator> v}, strict operators as in {@link SearchQuery.NumericQuery}. */
    private static Query range(byte operator, long v, RangeField field) {
        return switch (operator) {
            case SearchQuery.OP_EQUAL -> field.between(v, v);
            case SearchQuery.OP_GREATER -> v == Long.MAX_VALUE ? new MatchNoDocsQuery() : field.between(v + 1, Long.MAX_VALUE);
            case SearchQuery.OP_LESS -> v == Long.MIN_VALUE ? new MatchNoDocsQuery() : field.between(Long.MIN_VALUE, v - 1);
            case SearchQuery.OP_GREATER_EQUAL -> field.between(v, Long.MAX_VALUE);
            case SearchQuery.OP_LESS_EQUAL -> field.between(Long.MIN_VALUE, v);
            case SearchQuery.OP_NOTEQUAL -> new BooleanQuery.Builder().add(field.present(), Occur.FILTER)
                    .add(field.between(v, v), Occur.MUST_NOT).build();
            default -> field.present();
        };
    }

    private static String tagField(byte id) {
        return "tag" + Integer.toHexString(id & 0xFF);
    }

    private static String numericField(byte id) {
        return "num" + Integer.toHexString(id & 0xFF);
    }

    private Set<String> values(String field) {
        return tagValues.computeIfAbsent(field, f -> ConcurrentHashMap.newKeySet());
    }

    /**
     * Executes a simple keyword-based search: every word of the query must start a word of the name.
     * Uses a result cache for performance.
     *
     * @param query The textual query string.
     * @param limit The maximum number of results.
     * @return A list of matching {@link FileMetadata}.
     */
    public List<FileMetadata> search(String query, int limit) {
        if (eventManager != null) {
            eventManager.broadcast(new FileEvent(FileEvent.SEARCHED, query, "", "Search executed: " + query));
        }
        if (limit <= 0) return List.of();
        refreshForSearch();
        if (query == null || query.trim().isEmpty()) {
            return inIndexOrder(new MatchAllDocsQuery(), limit);
        }

        String queryLower = Keywords.normalize(query).trim();

        // Cache check
        String cacheKey = queryLower + "|" + limit;
        List<FileMetadata> cached = searchCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        String[] tokens = Keywords.split(queryLower);
        if (tokens.length == 0) return Collections.emptyList();
        BooleanQuery.Builder all = new BooleanQuery.Builder();
        for (String t : tokens) all.add(new PrefixQuery(new Term(WORD, t)), Occur.FILTER);
        List<FileMetadata> res = inIndexOrder(all.build(), limit);

        // Cache the result
        searchCache.put(cacheKey, res);

        return res;
    }

    private List<FileMetadata> inIndexOrder(Query query, int limit) {
        IndexSearcher searcher = acquire();
        try {
            int n = Math.min(limit, searcher.count(query));
            if (n == 0) return new ArrayList<>();
            TopDocs top = searcher.search(query, n, Sort.INDEXORDER);
            StoredFields stored = searcher.storedFields();
            List<FileMetadata> results = new ArrayList<>(top.scoreDocs.length);
            for (ScoreDoc hit : top.scoreDocs) results.add(result(stored.document(hit.doc), sourceCount(searcher, hit.doc)));
            return results;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            release(searcher);
        }
    }

    private static long sourceCount(IndexSearcher searcher, int doc) throws IOException {
        List<LeafReaderContext> leaves = searcher.getIndexReader().leaves();
        LeafReaderContext leaf = leaves.get(org.apache.lucene.index.ReaderUtil.subIndex(doc, leaves));
        NumericDocValues sources = leaf.reader().getNumericDocValues(SOURCES);
        return sources != null && sources.advanceExact(doc - leaf.docBase) ? sources.longValue() : 0;
    }

    /** Number of distinct words of the index (the words left without file stay until the restart). */
    public int wordCount() {
        return words.size();
    }

    /**
     * The words indexed under the most files, most files first: at least 3 characters, not only digits, and
     * not matching the child abuse filter. The files removed since the last merge of their segment still count.
     */
    public List<String> topWords(int limit) {
        if (limit <= 0) return List.of();
        record Counted(String word, int files) {}
        PriorityQueue<Counted> top = new PriorityQueue<>(Comparator.comparingInt(Counted::files));
        refreshForSearch();
        IndexSearcher searcher = acquire();
        try {
            Terms terms = MultiTerms.getTerms(searcher.getIndexReader(), WORD);
            TermsEnum it = terms == null ? TermsEnum.EMPTY : terms.iterator();
            for (BytesRef term = it.next(); term != null; term = it.next()) {
                String word = term.utf8ToString();
                if (word.length() < 3 || word.chars().allMatch(Character::isDigit)) continue;
                int files = it.docFreq();
                if (top.size() < limit) {
                    top.add(new Counted(word, files));
                } else if (files > top.peek().files()) {
                    top.poll();
                    top.add(new Counted(word, files));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            release(searcher);
        }
        return top.stream()
                .filter(c -> !abuseFilter.matches(c.word()))
                .sorted(Comparator.comparingInt(Counted::files).reversed().thenComparing(Counted::word))
                .map(Counted::word)
                .toList();
    }

    /** True when a text (a word to search, for instance) matches the child abuse filter. Not counted. */
    public boolean matchesChildAbuse(String text) {
        return abuseFilter.matches(text);
    }

    /** True if a file with this hash is indexed (case ignored for hexadecimal hashes). */
    public boolean contains(String hash) {
        if (hash == null) return false;
        String id = id(hash);
        Object written = pending.get(id);
        if (written == null) written = refreshing.get(id);
        if (written != null) return written != REMOVED;
        IndexSearcher searcher = acquire();
        try {
            return searcher.count(new TermQuery(new Term(ID, id))) > 0;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            release(searcher);
        }
    }

    /**
     * Returns the total number of files indexed.
     *
     * @return The file count.
     */
    public int fileCount() {
        return indexedFiles.get();
    }

    /** The lock a refresh takes against the writes of the index, for the tests. */
    StampedLock indexLock() {
        return lock;
    }

    /** Number of new files (a hash not indexed before) inserted since server start, for one ingestion channel. */
    public long newFileCount(FileSource source) {
        LongAdder adder = newFilesBySource.get(source);
        return adder == null ? 0 : adder.sum();
    }

    /** Number of new files inserted since server start, per ingestion channel (in enum order). */
    public Map<FileSource, Long> newFileCounts() {
        Map<FileSource, Long> counts = new LinkedHashMap<>();
        for (FileSource source : FileSource.values()) {
            counts.put(source, newFileCount(source));
        }
        return counts;
    }

    /** Total number of new files inserted since server start. */
    public long totalNewFiles() {
        return newFilesBySource.values().stream().mapToLong(LongAdder::sum).sum();
    }

    /** Size of the index files on disk (or in the heap for an index without directory). */
    public long indexBytes() {
        try {
            long total = 0;
            for (String file : directory.listAll()) {
                try {
                    total += directory.fileLength(file);
                } catch (IOException e) {
                    // deleted by a merge meanwhile
                }
            }
            return total;
        } catch (IOException e) {
            return 0;
        }
    }

    private IndexSearcher acquire() {
        try {
            return searchers.acquire();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void release(IndexSearcher searcher) {
        try {
            searchers.release(searcher);
        } catch (IOException e) {
            log.debug("Releasing a searcher of the file index: {}", e.toString());
        }
    }

    private Document document(FileMetadata meta, long sourceCount) {
        Document doc = new Document();
        doc.add(new StringField(ID, id(meta.hash()), Field.Store.NO));
        doc.add(new StoredField(HASH, meta.hash()));
        if (meta.name() != null) {
            doc.add(new StoredField(NAME, meta.name()));
            for (String word : new LinkedHashSet<>(List.of(Keywords.split(meta.name())))) {
                doc.add(new StringField(WORD, word, Field.Store.NO));
                if (words.add(word)) dictionary.add(word);
            }
            String lower = meta.name().toLowerCase(Locale.ROOT);
            int dot = lower.lastIndexOf('.');
            if (dot >= 0) doc.add(new StringField(EXTENSION, lower.substring(dot + 1), Field.Store.NO));
        }
        if (meta.type() != null) doc.add(new StoredField(TYPE, meta.type()));
        String type = meta.type() == null || meta.type().isEmpty() ? FileTypes.fromFileName(meta.name()) : meta.type();
        doc.add(new StringField(EFFECTIVE_TYPE, type.toLowerCase(Locale.ROOT), Field.Store.NO));
        doc.add(new LongPoint(SIZE, meta.size()));
        doc.add(new StoredField(SIZE, meta.size()));
        doc.add(new NumericDocValuesField(SOURCES, sourceCount));
        addMediaTags(doc, meta.tags());
        byte[] tags = encodeTags(meta.tags());
        if (tags != null) doc.add(new StoredField(TAGS, tags));
        return doc;
    }

    /** The first string tag and the first integer tag of each ID, as {@link SearchQuery} reads them. */
    private void addMediaTags(Document doc, List<Tag> tags) {
        if (tags == null) return;
        for (byte id : STRING_TAGS) {
            String name = Tag.idName(id);
            for (Tag tag : tags) {
                if (!tag.name().equals(name) || !(tag.value() instanceof String value)) continue;
                String field = tagField(id);
                String normalized = Keywords.normalize(value);
                if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_TERM_BYTES) {
                    doc.add(new StringField(LONG_TAG, field, Field.Store.NO));
                    longTags.add(field);
                } else {
                    doc.add(new StringField(field, normalized, Field.Store.NO));
                    values(field).add(normalized);
                }
                break;
            }
        }
        for (byte id : NUMERIC_TAGS) {
            String name = Tag.idName(id);
            for (Tag tag : tags) {
                if (!tag.name().equals(name) || !tag.isInt()) continue;
                doc.add(new LongPoint(numericField(id), tag.unsignedValue()));
                break;
            }
        }
    }

    /** A file of the index as stored, without its online sources. */
    private static FileMetadata file(Document doc, long sourceCount) {
        List<Tag> tags = new ArrayList<>(decodeTags(doc.getBinaryValue(TAGS)));
        // A file without known source has no FT_SOURCES tag, as it was published
        if (sourceCount > 0) tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, (int) sourceCount));
        return FileMetadata.withoutSources(doc.get(HASH), doc.get(NAME), doc.getField(SIZE).numericValue().longValue(),
                doc.get(TYPE), tags);
    }

    /** A file found by a search, with its online sources when it has some. */
    private FileMetadata result(Document doc, long sourceCount) {
        FileMetadata stored = file(doc, sourceCount);
        return withSources(stored, liveSources.get(id(stored.hash())));
    }

    /** The tags but the source count (a doc value of its own); null when there is none. */
    private static byte[] encodeTags(List<Tag> tags) {
        if (tags == null) return null;
        List<Tag> kept = tags.stream().filter(t -> !Tag.NAME_SOURCES.equals(t.name()) && storable(t.value())).toList();
        if (kept.isEmpty()) return null;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeShort(kept.size());
            for (Tag tag : kept) {
                out.writeByte(tag.type());
                writeBytes(out, tag.name().getBytes(StandardCharsets.UTF_8));
                switch (tag.value()) {
                    case String s -> { out.writeByte('S'); writeBytes(out, s.getBytes(StandardCharsets.UTF_8)); }
                    case Integer i -> { out.writeByte('I'); out.writeInt(i); }
                    case Long l -> { out.writeByte('L'); out.writeLong(l); }
                    case Short s -> { out.writeByte('H'); out.writeShort(s); }
                    case Byte b -> { out.writeByte('B'); out.writeByte(b); }
                    case Float f -> { out.writeByte('F'); out.writeFloat(f); }
                    case byte[] b -> { out.writeByte('Y'); writeBytes(out, b); }
                    default -> throw new IllegalStateException("Tag value not stored: " + tag);
                }
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Whether a tag value is stored in the index; the other ones are dropped. */
    private static boolean storable(Object value) {
        return value instanceof String || value instanceof Integer || value instanceof Long || value instanceof Short
                || value instanceof Byte || value instanceof Float || value instanceof byte[];
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        byte[] bytes = new byte[in.readInt()];
        in.readFully(bytes);
        return bytes;
    }

    private static List<Tag> decodeTags(BytesRef bytes) {
        if (bytes == null) return List.of();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.bytes, bytes.offset, bytes.length))) {
            int n = in.readUnsignedShort();
            List<Tag> tags = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                byte type = in.readByte();
                String name = new String(readBytes(in), StandardCharsets.UTF_8);
                // The indexed tags are named by their ID: the names shared, as FileMetadata.indexed() gives them
                if (name.length() == 1) name = Tag.idName(name.charAt(0));
                Object value = switch (in.readByte()) {
                    case 'S' -> new String(readBytes(in), StandardCharsets.UTF_8);
                    case 'I' -> in.readInt();
                    case 'L' -> in.readLong();
                    case 'H' -> in.readShort();
                    case 'B' -> in.readByte();
                    case 'F' -> in.readFloat();
                    case 'Y' -> readBytes(in);
                    default -> throw new IllegalStateException("Unknown tag value kind");
                };
                tags.add(new Tag(type, name, value));
            }
            return tags;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Closes the index. On disk, it records the same new token in the index and in the database, for the next start
     * to reopen it: to be called once the database writes are done (the persistence executor drained).
     */
    @Override
    public void close() {
        close(true);
    }

    /** Closes the index as a crash leaves it: without token, the next start rebuilds it (for the tests). */
    void closeWithoutToken() {
        close(false);
    }

    private void close(boolean recordToken) {
        synchronized (refreshLock) {
            if (closed) return;
            closed = true;
        }
        ScheduledExecutorService scheduler = refresher;
        if (scheduler != null) scheduler.shutdownNow();
        try {
            searchers.close();
            if (recordToken && onDisk && db != null) {
                long token;
                do {
                    token = java.util.concurrent.ThreadLocalRandom.current().nextLong();
                } while (token == 0);
                writer.setLiveCommitData(Map.of(FORMAT_KEY, FORMAT, TOKEN_KEY, String.valueOf(token),
                        FILTER_KEY, checkedWith).entrySet());
                writer.commit();
                db.setStat(INDEX_TOKEN_STAT, token);
            }
            writer.close();
            directory.close();
        } catch (IOException | RuntimeException e) {
            log.warn("Closing the file index: {}", e.toString());
        }
    }
}
