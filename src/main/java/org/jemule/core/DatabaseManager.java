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

import org.jemule.protocol.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class DatabaseManager implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private final Connection connection;

    public DatabaseManager(String dbPath) throws SQLException {
        String url = "jdbc:h2:" + dbPath;
        this.connection = DriverManager.getConnection(url, "sa", "");
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
        }
    }

    public void saveFile(FileMetadata meta) {
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
            logger.error("Error saving file to database", e);
            try { connection.rollback(); } catch (SQLException ex) { /* ignore */ }
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException e) { /* ignore */ }
        }
    }

    public List<FileMetadata> loadFiles() {
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
        } catch (SQLException e) {
            logger.error("Error loading files from database", e);
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
        try (PreparedStatement ps = connection.prepareStatement(
                "MERGE INTO stats (stat_key, stat_value) KEY (stat_key) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setLong(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Error saving stat", e);
        }
    }

    public long getStat(String key, long defaultValue) {
        try (PreparedStatement ps = connection.prepareStatement("SELECT stat_value FROM stats WHERE stat_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong("stat_value");
            }
        } catch (SQLException e) {
            logger.error("Error loading stat", e);
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
