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

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.functions.CheckedSupplier;
import org.jemule.protocol.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.concurrent.locks.ReentrantLock;

public class DatabaseManager implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    /** Minimum time between two attempts to reopen a database closed by a fatal error. */
    private static final long REOPEN_INTERVAL_MS = 10_000;
    /** Below this percentage of the chunks used, the file is compacted at startup. */
    static final int MIN_FILL_RATE = 80;
    /** Version of the schema written by this code, kept in the stats table. Databases without it are version 1. */
    static final long SCHEMA_VERSION = 2;
    static final String SCHEMA_VERSION_KEY = "schema_version";
    /** Rows of file_tags read per transaction, so that a migration never holds much memory. */
    private static final int MIGRATION_BATCH_ROWS = 50_000;
    private static final String MERGE_SOURCE_COUNT =
            "MERGE INTO file_sources (hash, source_count) KEY (hash) VALUES (?, ?)";
    private static final String MERGE_SOURCE_COUNT_OF_SAVED_FILE = """
            MERGE INTO file_sources (hash, source_count) KEY (hash)
            SELECT ?, ? FROM files WHERE hash = ?""";

    /** The database without its settings. */
    private final String baseUrl;
    /** Connection URL: AUTO_COMPACT_FILL_RATE=0 until {@link #enableOnlineCompaction()}. */
    private volatile String url;
    /** The H2 file, for the sizes logged around a compaction. */
    private final java.io.File file;
    /** Replaced when the database is reopened after a fatal error; only used while holding {@link #lock}. */
    private volatile Connection connection;
    private final CircuitBreaker circuitBreaker;
    /** Set by {@link #close()}: a database closed on purpose is never reopened. */
    private volatile boolean closed;
    private long lastReopenAttempt;
    private final java.util.concurrent.atomic.AtomicLong reopenCount = new java.util.concurrent.atomic.AtomicLong();
    /**
     * Serializes the use of the single JDBC connection: its auto-commit mode is switched for the transaction of
     * saveFile, which must not interleave with the statements of other threads. A lock rather than synchronized,
     * which would pin the carrier thread of the calling virtual thread during the I/O.
     */
    private final ReentrantLock lock = new ReentrantLock();

    public DatabaseManager(String dbPath) throws SQLException {
        this(dbPath, 50.0f, 10, 60);
    }

    public DatabaseManager(String dbPath, float failureRateThreshold, int minCalls, int waitDurationInSeconds) throws SQLException {
        // No background compaction until the startup load is over: it rewrites the live pages at the end of the
        // file, and while a long statement (the load at startup, a migration) keeps the old ones, the file grows by
        // gigabytes. The file is compacted at startup instead (compactIfSparse), then online once the server runs
        // (enableOnlineCompaction). A 4 MB page cache instead of 16: the database is read once at startup, then only
        // written to.
        this.baseUrl = "jdbc:h2:" + dbPath;
        this.url = baseUrl + ";AUTO_COMPACT_FILL_RATE=0;CACHE_SIZE=4096";
        this.file = new java.io.File(dbPath + ".mv.db");
        this.connection = DriverManager.getConnection(url, "sa", "");
        
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .minimumNumberOfCalls(minCalls)
                .waitDurationInOpenState(java.time.Duration.ofSeconds(waitDurationInSeconds))
                .build();
        this.circuitBreaker = CircuitBreaker.of("database", config);
        
        initSchema();
        migrate();
        compactIfSparse();
    }

    /**
     * Compacts the file at startup when its chunks are less than {@link #MIN_FILL_RATE}% used, before anything else
     * reads it.
     */
    private void compactIfSparse() {
        int fillRate;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'info.CHUNKS_FILL_RATE'")) {
            if (!rs.next()) return;
            fillRate = Integer.parseInt(rs.getString(1));
        } catch (SQLException | NumberFormatException e) {
            logger.warn("Could not read the database fill rate: {}", e.getMessage());
            return;
        }
        if (fillRate >= MIN_FILL_RATE) return;
        logger.info("The database file is {}% used", fillRate);
        compact();
    }

    /**
     * Brings a database written by an older version to {@link #SCHEMA_VERSION}, reporting its progress on the
     * console. A migration stopped halfway (crash, restart) resumes at the next start: each batch is committed whole.
     */
    private void migrate() {
        long version;
        try {
            version = getStatInternal(SCHEMA_VERSION_KEY, 1);
        } catch (SQLException e) {
            logger.error("Could not read the database schema version: {}", e.getMessage());
            return;
        }
        if (version >= SCHEMA_VERSION) return;
        try {
            long start = System.currentTimeMillis();
            boolean changed = migrateTagsToV2();
            setStatInternal(SCHEMA_VERSION_KEY, SCHEMA_VERSION);
            logger.info("Database migration to schema version {} done in {} s", SCHEMA_VERSION,
                    (System.currentTimeMillis() - start) / 1000);
            if (changed) logger.info("Database migration: the space freed is released by the compaction below");
        } catch (SQLException e) {
            logger.error("Database migration to schema version {} failed (retried at the next start): {}",
                    SCHEMA_VERSION, e.getMessage(), e);
        }
    }

    /**
     * Version 2: the source counts move from file_tags to file_sources, and only the tags the index keeps
     * ({@link FileMetadata#isIndexedTag}) stay in file_tags. Almost every row goes, so the table is rebuilt rather
     * than deleted from: one pass in _ROWID_ order (a range scan of the table, whatever the indexes) copies the rows
     * kept into file_tags_new, then the tables are swapped. Until the swap file_tags is not modified, so that a
     * migration interrupted halfway starts again from the beginning.
     *
     * @return true if rows were dropped
     */
    private boolean migrateTagsToV2() throws SQLException {
        long rows = countRows("file_tags");
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS file_tags_new"); // left by an interrupted migration
        }
        if (rows == 0) return false;
        logger.info("Database migration to schema version {}: {} tag rows to check (source counts moved to "
                + "file_sources, tags not used by searches dropped)", SCHEMA_VERSION, String.format("%,d", rows));
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE file_tags_new (
                    file_hash VARCHAR(32),
                    tag_type TINYINT,
                    tag_name VARCHAR(255),
                    tag_value_str TEXT,
                    tag_value_blob BLOB,
                    FOREIGN KEY (file_hash) REFERENCES files(hash) ON DELETE CASCADE
                )
                """);
        }

        long read = 0, kept = 0;
        int lastReported = 0;
        // Written once at the end in hash order: merged batch by batch, the counts of random hashes made every
        // commit rewrite most pages of file_sources, and the file grew by gigabytes
        Map<String, Integer> sourceCounts = new HashMap<>();
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement select = connection.prepareStatement("""
                         SELECT _ROWID_, file_hash, tag_type, tag_name, tag_value_str, tag_value_blob FROM file_tags
                         WHERE _ROWID_ > ? ORDER BY _ROWID_ FETCH FIRST ? ROWS ONLY""");
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO file_tags_new (file_hash, tag_type, tag_name, tag_value_str, tag_value_blob) VALUES (?, ?, ?, ?, ?)");
                 PreparedStatement mergeSources = connection.prepareStatement("""
                         MERGE INTO file_sources (hash, source_count) KEY (hash)
                         SELECT ?, GREATEST(?, COALESCE(MAX(source_count), 0)) FROM file_sources WHERE hash = ?""")) {
                long lastRow = Long.MIN_VALUE;
                while (true) {
                    int batchRows = 0;
                    select.setLong(1, lastRow);
                    select.setInt(2, MIGRATION_BATCH_ROWS);
                    try (ResultSet rs = select.executeQuery()) {
                        while (rs.next()) {
                            batchRows++;
                            lastRow = rs.getLong(1);
                            String hash = rs.getString(2);
                            String name = rs.getString(4);
                            if (hash == null) continue;
                            if (Tag.NAME_SOURCES.equals(name)) {
                                Integer count = sourceCount(rs.getString(5));
                                if (count != null) sourceCounts.merge(hash, count, Math::max);
                            } else if (FileMetadata.isIndexedTag(name)) {
                                insert.setString(1, hash);
                                insert.setByte(2, rs.getByte(3));
                                insert.setString(3, name);
                                insert.setString(4, rs.getString(5));
                                insert.setBytes(5, rs.getBytes(6));
                                insert.addBatch();
                                kept++;
                            }
                        }
                    }
                    if (batchRows == 0) break;
                    insert.executeBatch();
                    connection.commit();

                    read += batchRows;
                    int percent = (int) (Math.min(read, rows) * 100 / rows);
                    if (percent >= lastReported + 5) {
                        lastReported = percent;
                        logger.info("Database migration: {}% ({} of {} tag rows read)", percent,
                                String.format("%,d", read), String.format("%,d", rows));
                    }
                }

                List<String> hashes = new ArrayList<>(sourceCounts.keySet());
                java.util.Collections.sort(hashes);
                logger.info("Database migration: writing {} source counts", String.format("%,d", hashes.size()));
                for (int i = 0; i < hashes.size(); i++) {
                    String hash = hashes.get(i);
                    mergeSources.setString(1, hash);
                    mergeSources.setInt(2, sourceCounts.get(hash));
                    mergeSources.setString(3, hash);
                    mergeSources.addBatch();
                    if (i % MIGRATION_BATCH_ROWS == MIGRATION_BATCH_ROWS - 1 || i == hashes.size() - 1) {
                        mergeSources.executeBatch();
                        connection.commit();
                    }
                }
            }
        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException ex) { /* ignore */ }
            throw e;
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException e) { /* ignore */ }
        }

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DROP TABLE file_tags");
            stmt.execute("ALTER TABLE file_tags_new RENAME TO file_tags");
        }
        logger.info("Database migration: {} tag rows read, {} kept, {} source counts in file_sources ({} moved)",
                String.format("%,d", read), String.format("%,d", kept), String.format("%,d", countRows("file_sources")),
                String.format("%,d", sourceCounts.size()));
        return kept < read;
    }

    /** A source count stored as text in file_tags, capped to an int; null if it is not a number. */
    static Integer sourceCount(String text) {
        if (text == null || text.isEmpty() || text.length() > 18) return null;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') return null;
        }
        return (int) Math.min(Long.parseLong(text), Integer.MAX_VALUE);
    }

    private long countRows(String table) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Turns compaction on once the startup load is over, by reopening the connection with
     * AUTO_COMPACT_FILL_RATE={@link #MIN_FILL_RATE}: H2 then compacts in the background, and {@link #compactOnline}
     * works. Without it, the writes of a running server make the file grow by about a gigabyte a day. Never before
     * the load: while a long read keeps the old pages, the pages rewritten by a compaction cannot be freed and the
     * file grows instead (1.2.15).
     */
    public void enableOnlineCompaction() {
        try {
            locked(() -> {
                if (url.contains("AUTO_COMPACT_FILL_RATE=" + MIN_FILL_RATE)) return null;
                url = baseUrl + ";AUTO_COMPACT_FILL_RATE=" + MIN_FILL_RATE + ";CACHE_SIZE=4096";
                connection.close();
                connection = DriverManager.getConnection(url, "sa", "");
                logger.info("Online database compaction enabled (file {} MB, {}% used)", file.length() / 1_048_576,
                        chunksFillRate());
                return null;
            });
        } catch (Throwable e) {
            logger.error("Could not enable online database compaction: {}", e.getMessage());
        }
    }

    /**
     * Compacts the file, for at most {@code maxMillis}, when its chunks are less than {@link #MIN_FILL_RATE}% used.
     * Does nothing before {@link #enableOnlineCompaction}. Holds the connection lock meanwhile, so that no other
     * statement runs.
     *
     * @return true if the file was compacted
     */
    public boolean compactOnline(int maxMillis) {
        try {
            return locked(() -> {
                if (!url.contains("AUTO_COMPACT_FILL_RATE=" + MIN_FILL_RATE)) return false;
                int fillRate = chunksFillRate();
                if (fillRate < 0 || fillRate >= MIN_FILL_RATE) return false;
                if (!(connection instanceof org.h2.jdbc.JdbcConnection jdbc)
                        || !(jdbc.getSession() instanceof org.h2.engine.SessionLocal session)) return false;
                org.h2.mvstore.db.Store store = session.getDatabase().getStore();
                if (store == null) return false;
                long before = file.length();
                long start = System.currentTimeMillis();
                store.compactFile(maxMillis);
                logger.info("Database compacted in {} ms: {} MB, {}% used -> {} MB, {}% used",
                        System.currentTimeMillis() - start, before / 1_048_576, fillRate, file.length() / 1_048_576,
                        chunksFillRate());
                return true;
            });
        } catch (Throwable e) {
            logger.warn("Online database compaction failed: {}", e.getMessage());
            return false;
        }
    }

    /** Percentage of the chunks of the file still in use, -1 if unknown. */
    private int chunksFillRate() {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'info.CHUNKS_FILL_RATE'")) {
            return rs.next() ? Integer.parseInt(rs.getString(1)) : -1;
        } catch (SQLException | NumberFormatException e) {
            return -1;
        }
    }

    /** Rewrites the database file without the space freed by a migration, then reopens it. */
    private void compact() {
        logger.info("Compacting the database file ({} MB) to release the space freed (this can take a few minutes)...",
                file.length() / 1_048_576);
        long start = System.currentTimeMillis();
        try {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("SHUTDOWN COMPACT");
            }
        } catch (SQLException e) {
            logger.warn("Database compaction failed: {}", e.getMessage());
        }
        try {
            connection = DriverManager.getConnection(url, "sa", "");
            logger.info("Database compacted in {} s: {} MB", (System.currentTimeMillis() - start) / 1000,
                    file.length() / 1_048_576);
        } catch (SQLException e) {
            logger.error("Could not reopen the database after compaction: {}", e.getMessage());
        }
    }

    /**
     * Runs a database operation through the circuit breaker, holding the connection lock. When the database was
     * closed by a fatal error (H2 closes it after an OutOfMemoryError, for example), it is reopened and the operation
     * runs once more, so that writes resume without restarting the server.
     */
    private <T> T locked(CheckedSupplier<T> operation) throws Throwable {
        return circuitBreaker.executeCheckedSupplier(() -> {
            lock.lock();
            try {
                try {
                    return operation.get();
                } catch (SQLException e) {
                    if (!isDatabaseClosed(e) || !reopen(e)) throw e;
                    return operation.get();
                }
            } finally {
                lock.unlock();
            }
        });
    }

    /** Whether {@code e} means that the database or the connection is closed, as after a fatal error. */
    static boolean isDatabaseClosed(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                switch (sql.getErrorCode()) {
                    case org.h2.api.ErrorCode.DATABASE_IS_CLOSED, org.h2.api.ErrorCode.DATABASE_CALLED_AT_SHUTDOWN,
                         org.h2.api.ErrorCode.OUT_OF_MEMORY, org.h2.api.ErrorCode.CONNECTION_BROKEN_1,
                         org.h2.api.ErrorCode.OBJECT_CLOSED -> {
                        return true;
                    }
                    default -> { }
                }
            }
            if (t.getMessage() != null && t.getMessage().contains("This store is closed")) return true;
        }
        return false;
    }

    /**
     * Reopens the database after a fatal error. Called with {@link #lock} held.
     *
     * @return true if the operation can be retried on a new connection
     */
    private boolean reopen(SQLException cause) {
        if (closed) return false;
        long now = System.currentTimeMillis();
        if (now - lastReopenAttempt < REOPEN_INTERVAL_MS) return false;
        lastReopenAttempt = now;
        logger.error("The database was closed by a fatal error ({}): reopening it", cause.getMessage());
        try {
            connection.close();
        } catch (SQLException ignored) {
            // the connection is already unusable
        }
        try {
            connection = DriverManager.getConnection(url, "sa", "");
            initSchema();
            reopenCount.incrementAndGet();
            logger.error("Database reopened: writes resume. Check the memory of the JVM (-XX:MaxRAMPercentage)");
            return true;
        } catch (SQLException e) {
            logger.error("Could not reopen the database (next attempt in {} s): {}", REOPEN_INTERVAL_MS / 1000,
                    e.getMessage());
            return false;
        }
    }

    /** Size of the database file in bytes (0 for an in-memory database). */
    public long fileSize() {
        return file.length();
    }

    /** Times the database was reopened after a fatal error since the server started. */
    public long getReopenCount() {
        return reopenCount.get();
    }

    private void initSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Table for files
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS files (
                    hash VARCHAR(32) PRIMARY KEY,
                    name VARCHAR(1024) NOT NULL,
                    size BIGINT NOT NULL,
                    type VARCHAR(255)
                )
                """);

            // Table for file tags
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS file_tags (
                    file_hash VARCHAR(32),
                    tag_type TINYINT,
                    tag_name VARCHAR(255),
                    tag_value_str TEXT,
                    tag_value_blob BLOB,
                    FOREIGN KEY (file_hash) REFERENCES files(hash) ON DELETE CASCADE
                )
                """);

            // Source count of each file, apart from its tags: updating it writes one narrow row. Databases written
            // before keep an FT_SOURCES tag in file_tags; the higher of the two counts is loaded.
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS file_sources (
                    hash VARCHAR(32) PRIMARY KEY,
                    source_count INT NOT NULL,
                    FOREIGN KEY (hash) REFERENCES files(hash) ON DELETE CASCADE
                )
                """);

            // Table for statistics
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS stats (
                    stat_key VARCHAR(255) PRIMARY KEY,
                    stat_value BIGINT
                )
                """);

            // Samples of the index size for the admin panel (one every 5 minutes, a month kept): a table of its own,
            // that older versions ignore
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS metrics_history (
                    sampled_at BIGINT PRIMARY KEY,
                    files INT NOT NULL,
                    clients INT NOT NULL
                )
                """);

            // Table for banned hashes
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS banned_hashes (
                    hash VARCHAR(32) PRIMARY KEY,
                    reason VARCHAR(255),
                    added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        }
    }

    public void addBannedHash(String hash, String reason) {
        try {
            locked(() -> {
                addBannedHashInternal(hash, reason);
                return null;
            });
        } catch (Throwable e) {
            logger.error("Circuit breaker prevented or caught error during addBannedHash: {}", e.getMessage());
        }
    }

    private void addBannedHashInternal(String hash, String reason) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "MERGE INTO banned_hashes (hash, reason) KEY (hash) VALUES (?, ?)")) {
            ps.setString(1, hash.toUpperCase());
            ps.setString(2, reason);
            ps.executeUpdate();
        }
    }

    public void removeBannedHash(String hash) {
        try {
            locked(() -> {
                try (PreparedStatement ps = connection.prepareStatement("DELETE FROM banned_hashes WHERE hash = ?")) {
                    ps.setString(1, hash.toUpperCase());
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (Throwable e) {
            logger.error("Circuit breaker prevented or caught error during removeBannedHash: {}", e.getMessage());
        }
    }

    public List<String> loadBannedHashes() {
        try {
            return locked(this::loadBannedHashesInternal);
        } catch (Throwable e) {
            logger.error("Circuit breaker prevented or caught error during loadBannedHashes: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<String> loadBannedHashesInternal() throws SQLException {
        List<String> hashes = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT hash FROM banned_hashes")) {
            while (rs.next()) {
                hashes.add(rs.getString("hash"));
            }
        }
        return hashes;
    }

    /** Most recently added banned hashes, newest first; never loads the whole table. */
    public List<String> loadRecentBannedHashes(int limit) {
        if (limit < 1) return new ArrayList<>();
        try {
            return locked(() -> {
                List<String> hashes = new ArrayList<>(limit);
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT hash FROM banned_hashes ORDER BY added_at DESC, _ROWID_ DESC LIMIT ?")) {
                    ps.setInt(1, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            hashes.add(rs.getString("hash"));
                        }
                    }
                }
                return hashes;
            });
        } catch (Throwable e) {
            logger.error("Circuit breaker prevented or caught error during loadRecentBannedHashes: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** Banned hashes whose reason starts with the given prefix. */
    public List<String> loadBannedHashes(String reasonPrefix) {
        try {
            return locked(() -> loadBannedHashesInternal(reasonPrefix));
        } catch (Throwable e) {
            logger.error("Circuit breaker prevented or caught error during loadBannedHashes: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<String> loadBannedHashesInternal(String reasonPrefix) throws SQLException {
        List<String> hashes = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT hash FROM banned_hashes WHERE reason LIKE ? ESCAPE '\\'")) {
            ps.setString(1, reasonPrefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    hashes.add(rs.getString("hash"));
                }
            }
        }
        return hashes;
    }

    /** Deletes a file and its tags. */
    public void deleteFile(String hash) {
        try {
            locked(() -> {
                try (PreparedStatement ps = connection.prepareStatement("DELETE FROM files WHERE hash = ?")) {
                    ps.setString(1, hash);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (Throwable e) {
            logger.error("Circuit breaker prevented or caught error during deleteFile: {}", e.getMessage());
        }
    }

    public void saveFile(FileMetadata meta) {
        saveFiles(List.of(meta));
    }

    public void saveFiles(Collection<FileMetadata> files) {
        if (files == null || files.isEmpty()) return;
        try {
            locked(() -> {
                saveFilesInternal(files);
                return null;
            });
        } catch (Throwable e) {
            logger.error("Circuit breaker prevented or caught error during saveFiles ({} files): {}",
                    files.size(), e.getMessage(), e);
        }
    }

    private void saveFilesInternal(Collection<FileMetadata> files) throws SQLException {
        Map<String, FileMetadata> uniqueFiles = new LinkedHashMap<>();
        for (FileMetadata file : files) {
            if (file != null) uniqueFiles.put(file.hash(), file);
        }
        if (uniqueFiles.isEmpty()) return;

        try {
            connection.setAutoCommit(false);
            try (PreparedStatement mergeFile = connection.prepareStatement(
                         "MERGE INTO files (hash, name, size, type) KEY (hash) VALUES (?, ?, ?, ?)");
                 PreparedStatement deleteTags = connection.prepareStatement("DELETE FROM file_tags WHERE file_hash = ?");
                 PreparedStatement insertTag = connection.prepareStatement(
                         "INSERT INTO file_tags (file_hash, tag_type, tag_name, tag_value_str, tag_value_blob) VALUES (?, ?, ?, ?, ?)");
                 PreparedStatement mergeSources = connection.prepareStatement(MERGE_SOURCE_COUNT)) {
                for (FileMetadata meta : uniqueFiles.values()) {
                    mergeFile.setString(1, meta.hash());
                    mergeFile.setString(2, meta.name());
                    mergeFile.setLong(3, meta.size());
                    mergeFile.setString(4, meta.type());
                    mergeFile.addBatch();

                    deleteTags.setString(1, meta.hash());
                    deleteTags.addBatch();
                }
                mergeFile.executeBatch();
                deleteTags.executeBatch();

                for (FileMetadata meta : uniqueFiles.values()) {
                    if (meta.sourceCount() > 0) {
                        mergeSources.setString(1, meta.hash());
                        mergeSources.setInt(2, meta.sourceCount());
                        mergeSources.addBatch();
                    }
                    for (Tag tag : meta.tags()) {
                        if (Tag.NAME_SOURCES.equals(tag.name())) continue; // in file_sources
                        insertTag.setString(1, meta.hash());
                        insertTag.setByte(2, tag.type());
                        insertTag.setString(3, tag.name());
                        if (tag.value() instanceof byte[] bytes) {
                            insertTag.setNull(4, Types.CLOB);
                            insertTag.setBytes(5, bytes);
                        } else {
                            insertTag.setString(4, String.valueOf(tag.value()));
                            insertTag.setNull(5, Types.BLOB);
                        }
                        insertTag.addBatch();
                    }
                }
                insertTag.executeBatch();
                mergeSources.executeBatch();
            }

            connection.commit();
        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException ex) { /* ignore */ }
            throw e;
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException e) { /* ignore */ }
        }
    }

    /**
     * Stores the source count of files already saved, without rewriting their name and tags. A file not saved yet is
     * skipped (its count is written with it by {@link #saveFiles}).
     */
    public void saveSourceCounts(Collection<FileMetadata> files) {
        if (files == null || files.isEmpty()) return;
        try {
            locked(() -> {
                try (PreparedStatement mergeSources = connection.prepareStatement(MERGE_SOURCE_COUNT_OF_SAVED_FILE)) {
                    for (FileMetadata meta : files) {
                        mergeSources.setString(1, meta.hash());
                        mergeSources.setInt(2, meta.sourceCount());
                        mergeSources.setString(3, meta.hash());
                        mergeSources.addBatch();
                    }
                    mergeSources.executeBatch();
                }
                return null;
            });
        } catch (Throwable e) {
            logger.error("Circuit breaker prevented or caught error during saveSourceCounts ({} files): {}",
                    files.size(), e.getMessage());
        }
    }

    public List<FileMetadata> loadFiles() {
        List<FileMetadata> files = new ArrayList<>();
        return forEachFile(files::add) ? files : new ArrayList<>();
    }

    /**
     * Reads every file of the database with its tags in one ordered pass, handing them to {@code consumer} one by
     * one instead of loading them all in memory first. The consumer runs while the database is locked: it must not
     * call this manager.
     *
     * @return false if the read failed (the consumer may have received part of the files)
     */
    public boolean forEachFile(Consumer<FileMetadata> consumer) {
        try {
            return locked(() -> {
                forEachFileInternal(consumer);
                return true;
            });
        } catch (Throwable e) {
            logger.error("Circuit breaker prevented or caught error during loadFiles: {}", e.getMessage());
            return false;
        }
    }

    private void forEachFileInternal(Consumer<FileMetadata> consumer) throws SQLException {
        // Type and tag names repeat on every row: one instance of each is kept
        Map<String, String> shared = new HashMap<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT f.hash, f.name, f.size, f.type, t.tag_type, t.tag_name, t.tag_value_str, t.tag_value_blob,
                            s.source_count
                     FROM files f LEFT JOIN file_sources s ON s.hash = f.hash
                     LEFT JOIN file_tags t ON t.file_hash = f.hash ORDER BY f._ROWID_""")) {
            String hash = null, name = null, type = null;
            long size = 0;
            int sourceCount = -1;
            List<Tag> tags = new ArrayList<>();
            while (rs.next()) {
                String rowHash = rs.getString(1);
                if (!rowHash.equals(hash)) {
                    if (hash != null) consumer.accept(loaded(hash, name, size, type, tags, sourceCount));
                    hash = rowHash;
                    name = rs.getString(2);
                    size = rs.getLong(3);
                    type = share(shared, rs.getString(4));
                    int count = rs.getInt(9);
                    sourceCount = rs.wasNull() ? -1 : count;
                    tags.clear();
                }
                String tagName = rs.getString(6);
                if (tagName != null) {
                    Tag tag = readTag(rs.getByte(5), share(shared, tagName), rs.getString(7), rs.getBytes(8));
                    if (tag != null) tags.add(tag);
                }
            }
            if (hash != null) consumer.accept(loaded(hash, name, size, type, tags, sourceCount));
        }
    }

    /** A file read from the database, its source count taken from file_sources when higher than its old tag. */
    private static FileMetadata loaded(String hash, String name, long size, String type, List<Tag> tags,
                                       int sourceCount) {
        if (sourceCount >= 0) {
            int tagIndex = -1;
            for (int i = 0; i < tags.size(); i++) {
                if (Tag.NAME_SOURCES.equals(tags.get(i).name()) && tags.get(i).isInt()) tagIndex = i;
            }
            if (tagIndex < 0) {
                tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, sourceCount));
            } else if (tags.get(tagIndex).unsignedValue() < sourceCount) {
                tags.set(tagIndex, new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, sourceCount));
            }
        }
        return FileMetadata.withoutSources(hash, name, size, type, List.copyOf(tags));
    }

    /** The shared instance of a repeated string (bounded, so that unusual values cannot fill the map). */
    private static String share(Map<String, String> shared, String value) {
        if (value == null || value.length() > 64) return value;
        String known = shared.get(value);
        if (known != null) return known;
        if (shared.size() < 4096) shared.put(value, value);
        return value;
    }

    private static Tag readTag(byte type, String name, String valStr, byte[] valBlob) {
        Object value = switch (type) {
            case Tag.TYPE_STRING -> valStr;
            case Tag.TYPE_INTEGER -> Integer.valueOf(valStr);
            case Tag.TYPE_INT16 -> Short.valueOf(valStr);
            case Tag.TYPE_INT8 -> Byte.valueOf(valStr);
            case Tag.TYPE_FLOAT -> Float.valueOf(valStr);
            case Tag.TYPE_UINT64 -> Long.valueOf(valStr);
            case Tag.TYPE_BOOL -> Boolean.valueOf(valStr);
            case Tag.TYPE_BLOB, Tag.TYPE_HASH, Tag.TYPE_BOOLARRAY, Tag.TYPE_BSOB -> valBlob;
            default -> null;
        };
        return value != null ? new Tag(type, name, value) : null;
    }

    /** Stores a sample of the index size and deletes those taken before {@code keepSince}. */
    public void saveMetricsSample(long sampledAt, int files, int clients, long keepSince) {
        try {
            locked(() -> {
                try (PreparedStatement merge = connection.prepareStatement(
                             "MERGE INTO metrics_history (sampled_at, files, clients) KEY (sampled_at) VALUES (?, ?, ?)");
                     PreparedStatement prune = connection.prepareStatement(
                             "DELETE FROM metrics_history WHERE sampled_at < ?")) {
                    merge.setLong(1, sampledAt);
                    merge.setInt(2, files);
                    merge.setInt(3, clients);
                    merge.executeUpdate();
                    prune.setLong(1, keepSince);
                    prune.executeUpdate();
                }
                return null;
            });
        } catch (Throwable e) {
            logger.error("Circuit breaker prevented or caught error during saveMetricsSample: {}", e.getMessage());
        }
    }

    /** Samples of the index size taken since {@code since}, oldest first: {sampledAt, files, clients} each. */
    public List<long[]> loadMetricsSamples(long since) {
        try {
            return locked(() -> {
                List<long[]> samples = new ArrayList<>();
                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT sampled_at, files, clients FROM metrics_history WHERE sampled_at >= ? ORDER BY sampled_at")) {
                    ps.setLong(1, since);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) samples.add(new long[]{rs.getLong(1), rs.getInt(2), rs.getInt(3)});
                    }
                }
                return samples;
            });
        } catch (Throwable e) {
            logger.error("Circuit breaker prevented or caught error during loadMetricsSamples: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public void setStat(String key, long value) {
        try {
            locked(() -> {
                setStatInternal(key, value);
                return null;
            });
        } catch (Throwable e) {
            logger.error("Circuit breaker prevented or caught error during setStat: {}", e.getMessage());
        }
    }

    private void setStatInternal(String key, long value) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "MERGE INTO stats (stat_key, stat_value) KEY (stat_key) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setLong(2, value);
            ps.executeUpdate();
        }
    }

    public long getStat(String key, long defaultValue) {
        try {
            return locked(() -> getStatInternal(key, defaultValue));
        } catch (Throwable e) {
            logger.error("Circuit breaker prevented or caught error during getStat: {}", e.getMessage());
            return defaultValue;
        }
    }

    private long getStatInternal(String key, long defaultValue) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT stat_value FROM stats WHERE stat_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong("stat_value");
            }
        }
        return defaultValue;
    }

    @Override
    public void close() throws SQLException {
        closed = true;
        lock.lock();
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } finally {
            lock.unlock();
        }
    }
}
