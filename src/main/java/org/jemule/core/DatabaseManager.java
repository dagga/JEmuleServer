/*
 * JEmuleServer - An experimental eMule server.
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
import org.jemule.protocol.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class DatabaseManager implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private final Connection connection;
    private final CircuitBreaker circuitBreaker;

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
            circuitBreaker.executeCheckedSupplier(() -> {
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

    public List<String> loadBannedHashes() {
        try {
            return circuitBreaker.executeCheckedSupplier(this::loadBannedHashesInternal);
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

    public void saveFile(FileMetadata meta) {
        try {
            circuitBreaker.executeCheckedSupplier(() -> {
                saveFileInternal(meta);
                return null;
            });
        } catch (Throwable e) {
            logger.error("Circuit breaker prevented or caught error during saveFile: {}", e.getMessage());
        }
    }

    private void saveFileInternal(FileMetadata meta) throws SQLException {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(
                    "MERGE INTO files (hash, name, size, type) KEY (hash) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, meta.hash());
                ps.setString(2, meta.name());
                ps.setLong(3, meta.size());
                ps.setString(4, meta.type());
                ps.executeUpdate();
            }

            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM file_tags WHERE file_hash = ?")) {
                ps.setString(1, meta.hash());
                ps.executeUpdate();
            }

            if (!meta.tags().isEmpty()) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO file_tags (file_hash, tag_type, tag_name, tag_value_str, tag_value_blob) VALUES (?, ?, ?, ?, ?)")) {
                    for (Tag tag : meta.tags()) {
                        ps.setString(1, meta.hash());
                        ps.setByte(2, tag.type());
                        ps.setString(3, tag.name());
                        
                        if (tag.value() instanceof byte[] bytes) {
                            ps.setNull(4, Types.CLOB);
                            ps.setBytes(5, bytes);
                        } else {
                            ps.setString(4, String.valueOf(tag.value()));
                            ps.setNull(5, Types.BLOB);
                        }
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
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
        try {
            return circuitBreaker.executeCheckedSupplier(this::loadFilesInternal);
        } catch (Throwable e) {
            logger.error("Circuit breaker prevented or caught error during loadFiles: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<FileMetadata> loadFilesInternal() throws SQLException {
        List<FileMetadata> files = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM files")) {
            while (rs.next()) {
                String hash = rs.getString("hash");
                String name = rs.getString("name");
                long size = rs.getLong("size");
                String type = rs.getString("type");
                
                List<Tag> tags = loadTags(hash);
                files.add(new FileMetadata(hash, name, size, type, tags));
            }
        }
        return files;
    }

    private List<Tag> loadTags(String fileHash) throws SQLException {
        List<Tag> tags = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM file_tags WHERE file_hash = ?")) {
            ps.setString(1, fileHash);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byte type = rs.getByte("tag_type");
                    String name = rs.getString("tag_name");
                    String valStr = rs.getString("tag_value_str");
                    byte[] valBlob = rs.getBytes("tag_value_blob");
                    
                    Object value = null;
                    switch (type) {
                        case Tag.TYPE_STRING -> value = valStr;
                        case Tag.TYPE_INTEGER -> value = Integer.parseInt(valStr);
                        case Tag.TYPE_INT16 -> value = Short.parseShort(valStr);
                        case Tag.TYPE_INT8 -> value = Byte.parseByte(valStr);
                        case Tag.TYPE_FLOAT -> value = Float.parseFloat(valStr);
                        case Tag.TYPE_BOOL, Tag.TYPE_BOOL_ALT -> value = Boolean.parseBoolean(valStr);
                        case Tag.TYPE_BLOB, Tag.TYPE_HASH -> value = valBlob;
                    }
                    if (value != null) {
                        tags.add(new Tag(type, name, value));
                    }
                }
            }
        }
        return tags;
    }

    public void setStat(String key, long value) {
        try {
            circuitBreaker.executeCheckedSupplier(() -> {
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
            return circuitBreaker.executeCheckedSupplier(() -> getStatInternal(key, defaultValue));
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
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}
