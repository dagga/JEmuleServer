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
    private final Connection connection;
    private final CircuitBreaker circuitBreaker;
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
        String url = "jdbc:h2:" + dbPath;
        this.connection = DriverManager.getConnection(url, "sa", "");
        
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .minimumNumberOfCalls(minCalls)
                .waitDurationInOpenState(java.time.Duration.ofSeconds(waitDurationInSeconds))
                .build();
        this.circuitBreaker = CircuitBreaker.of("database", config);
        
        initSchema();
    }

    /** Runs a database operation through the circuit breaker, holding the connection lock. */
    private <T> T locked(CheckedSupplier<T> operation) throws Throwable {
        return circuitBreaker.executeCheckedSupplier(() -> {
            lock.lock();
            try {
                return operation.get();
            } finally {
                lock.unlock();
            }
        });
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

            // Table for statistics
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS stats (
                    stat_key VARCHAR(255) PRIMARY KEY,
                    stat_value BIGINT
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
                         "INSERT INTO file_tags (file_hash, tag_type, tag_name, tag_value_str, tag_value_blob) VALUES (?, ?, ?, ?, ?)")) {
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
                    for (Tag tag : meta.tags()) {
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
            }

            connection.commit();
        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException ex) { /* ignore */ }
            throw e;
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException e) { /* ignore */ }
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
                     SELECT f.hash, f.name, f.size, f.type, t.tag_type, t.tag_name, t.tag_value_str, t.tag_value_blob
                     FROM files f LEFT JOIN file_tags t ON t.file_hash = f.hash ORDER BY f._ROWID_""")) {
            String hash = null, name = null, type = null;
            long size = 0;
            List<Tag> tags = new ArrayList<>();
            while (rs.next()) {
                String rowHash = rs.getString(1);
                if (!rowHash.equals(hash)) {
                    if (hash != null) consumer.accept(FileMetadata.withoutSources(hash, name, size, type, List.copyOf(tags)));
                    hash = rowHash;
                    name = rs.getString(2);
                    size = rs.getLong(3);
                    type = share(shared, rs.getString(4));
                    tags.clear();
                }
                String tagName = rs.getString(6);
                if (tagName != null) {
                    Tag tag = readTag(rs.getByte(5), share(shared, tagName), rs.getString(7), rs.getBytes(8));
                    if (tag != null) tags.add(tag);
                }
            }
            if (hash != null) consumer.accept(FileMetadata.withoutSources(hash, name, size, type, List.copyOf(tags)));
        }
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
