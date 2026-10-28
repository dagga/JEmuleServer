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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

/**
 * Copies the H2 database of an older version into an empty MySQL database, at the first start with
 * {@code databaseType=mysql}. The rows are copied table by table: files with their source counts, and their tags, by
 * pages of {@link #PAGE_FILES} files in hash order, each page committed whole. MySQL keeps the hashes on 16 bytes: a
 * hash that is not 32 hexadecimal digits, which no client sends, is left out. An import stopped halfway (crash,
 * restart) starts over at the next start, the files copied being written again and their tags replaced: the order of
 * the 16 byte hashes is not the order of their text in H2 when the case of their letters differs.
 * <p>
 * The H2 database is opened by a {@link DatabaseManager} first, which brings it to the current schema version. It is
 * not modified otherwise, and stays as a backup.
 */
final class H2ToMySqlImport {
    private static final Logger logger = LoggerFactory.getLogger(H2ToMySqlImport.class);
    /** Stat of the MySQL database: {@link #IN_PROGRESS} or {@link #DONE}, absent before any import. */
    static final String STATE_KEY = "h2_import_state";
    static final long IN_PROGRESS = 1;
    static final long DONE = 2;
    static final int PAGE_FILES = 5_000;
    private static final int SMALL_TABLE_BATCH_ROWS = 5_000;

    private final DatabaseManager target;
    private final Connection mysql;
    private final DatabaseManager.Sql sql;
    private final String h2Path;

    H2ToMySqlImport(DatabaseManager target, String h2Path) {
        this.target = target;
        this.mysql = target.connection();
        this.sql = target.sql();
        this.h2Path = h2Path;
    }

    /**
     * Imports the H2 database unless it was done, the H2 file does not exist, or the MySQL database already holds
     * files of its own.
     *
     * @throws SQLException if the import failed; it resumes at the next start
     */
    void importIfNeeded() throws SQLException {
        long state = target.getStat(STATE_KEY, 0);
        if (state == DONE) return;
        if (h2Path == null || h2Path.isBlank() || h2Path.trim().startsWith("mem:")) return;
        java.io.File h2File = new java.io.File(h2Path + ".mv.db");
        if (!h2File.isFile()) return;
        if (state != IN_PROGRESS && countRows(mysql, "files") > 0) {
            logger.warn("The H2 database {} is not imported: the MySQL database already holds files", h2File);
            return;
        }

        logger.info("Importing the H2 database {} ({} MB) into MySQL{}", h2File, h2File.length() / 1_048_576,
                state == IN_PROGRESS ? " (resuming)" : "");
        long start = System.currentTimeMillis();
        setState(IN_PROGRESS);
        try (DatabaseManager h2Manager = new DatabaseManager(h2Path)) {
            Connection h2 = h2Manager.connection();
            long stats = copyStats(h2);
            long samples = copyMetrics(h2);
            long names = copyBannedNames(h2);
            long[] files = copyFiles(h2, state == IN_PROGRESS);
            setState(DONE);
            logger.info("H2 database imported into MySQL in {} s: {} files, {} tags, {} source counts, {} banned names, "
                            + "{} metrics samples, {} statistics. {} is no longer used: keep it as a backup or delete it",
                    (System.currentTimeMillis() - start) / 1000, String.format("%,d", files[0]),
                    String.format("%,d", files[1]), String.format("%,d", files[2]), String.format("%,d", names),
                    String.format("%,d", samples), stats, h2File);
        } catch (SQLException e) {
            logger.error("Import of the H2 database {} into MySQL failed (resumed at the next start): {}", h2File,
                    e.getMessage(), e);
            throw e;
        }
    }

    private void setState(long state) throws SQLException {
        try (PreparedStatement ps = mysql.prepareStatement(sql.mergeStat())) {
            ps.setString(1, STATE_KEY);
            ps.setLong(2, state);
            ps.executeUpdate();
        }
    }

    /** Every statistic (the Kad node ID among them) but the schema version, which is MySQL's own. */
    private long copyStats(Connection h2) throws SQLException {
        long rows = 0;
        try (Statement select = h2.createStatement();
             ResultSet rs = select.executeQuery("SELECT stat_key, stat_value FROM stats");
             PreparedStatement merge = mysql.prepareStatement(sql.mergeStat())) {
            while (rs.next()) {
                String key = rs.getString(1);
                if (DatabaseManager.SCHEMA_VERSION_KEY.equals(key) || STATE_KEY.equals(key)) continue;
                merge.setString(1, key);
                merge.setLong(2, rs.getLong(2));
                merge.addBatch();
                rows++;
            }
            merge.executeBatch();
        }
        return rows;
    }

    private long copyMetrics(Connection h2) throws SQLException {
        long rows = 0;
        try (Statement select = h2.createStatement();
             ResultSet rs = select.executeQuery("SELECT sampled_at, files, clients FROM metrics_history");
             PreparedStatement merge = mysql.prepareStatement(sql.mergeMetricsSample())) {
            while (rs.next()) {
                merge.setLong(1, rs.getLong(1));
                merge.setInt(2, rs.getInt(2));
                merge.setInt(3, rs.getInt(3));
                merge.addBatch();
                if (++rows % SMALL_TABLE_BATCH_ROWS == 0) merge.executeBatch();
            }
            merge.executeBatch();
        }
        return rows;
    }

    /** The banned names with their reason and date; those too long for MySQL are left out and counted. */
    private long copyBannedNames(Connection h2) throws SQLException {
        long rows = 0, tooLong = 0;
        try (Statement select = h2.createStatement();
             ResultSet rs = select.executeQuery("SELECT name, reason, added_at FROM banned_names");
             PreparedStatement merge = mysql.prepareStatement("INSERT INTO banned_names (name, reason, added_at) "
                     + "VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE reason = VALUES(reason), added_at = VALUES(added_at)")) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (name.length() > sql.maxBannedNameLength()) {
                    tooLong++;
                    continue;
                }
                merge.setString(1, name);
                merge.setString(2, rs.getString(2));
                merge.setString(3, DatabaseManager.mySqlTimestamp(rs.getTimestamp(3)));
                merge.addBatch();
                if (++rows % SMALL_TABLE_BATCH_ROWS == 0) merge.executeBatch();
            }
            merge.executeBatch();
        }
        if (tooLong > 0) {
            logger.warn("H2 import: {} banned names longer than {} characters left out", tooLong,
                    sql.maxBannedNameLength());
        }
        return rows;
    }

    /**
     * Files with their source counts, and their tags, page by page in hash order, each page in one transaction.
     *
     * @param again whether a previous run copied part of the files: their tags are deleted before being copied
     * @return {files, tags, source counts} copied by this run
     */
    private long[] copyFiles(Connection h2, boolean again) throws SQLException {
        long total = countRows(h2, "files");
        String after = "";
        long done = 0;
        long files = 0, tags = 0, sources = 0, notHashes = 0;
        int lastReported = -1;
        try (PreparedStatement selectFiles = h2.prepareStatement("""
                     SELECT f.hash, f.name, f.size, f.type, s.source_count FROM files f
                     LEFT JOIN file_sources s ON s.hash = f.hash WHERE f.hash > ? ORDER BY f.hash LIMIT ?""");
             PreparedStatement selectTags = h2.prepareStatement("""
                     SELECT file_hash, tag_type, tag_name, tag_value_str, tag_value_blob FROM file_tags
                     WHERE file_hash > ? AND file_hash <= ? ORDER BY file_hash, _ROWID_""");
             PreparedStatement mergeFile = mysql.prepareStatement("""
                     INSERT INTO files (hash, name, size, type, source_count) VALUES (?, ?, ?, ?, ?)
                     ON DUPLICATE KEY UPDATE name = VALUES(name), size = VALUES(size), type = VALUES(type),
                     source_count = VALUES(source_count)""");
             PreparedStatement deleteTags = mysql.prepareStatement("DELETE FROM file_tags WHERE file_hash = ?");
             PreparedStatement insertTag = mysql.prepareStatement(
                     "INSERT INTO file_tags (file_hash, tag_type, tag_name, tag_value_str, tag_value_blob) VALUES (?, ?, ?, ?, ?)")) {
            mysql.setAutoCommit(false);
            while (true) {
                String first = after, last = null;
                int pageFiles = 0;
                selectFiles.setString(1, after);
                selectFiles.setInt(2, PAGE_FILES);
                try (ResultSet rs = selectFiles.executeQuery()) {
                    while (rs.next()) {
                        last = rs.getString(1);
                        pageFiles++;
                        if (DatabaseManager.hashBytes(last) == null) {
                            notHashes++;
                            continue;
                        }
                        target.setHash(mergeFile, 1, last);
                        mergeFile.setString(2, rs.getString(2));
                        mergeFile.setLong(3, rs.getLong(3));
                        mergeFile.setString(4, rs.getString(4));
                        int count = rs.getInt(5);
                        if (rs.wasNull()) {
                            mergeFile.setNull(5, Types.INTEGER);
                        } else {
                            mergeFile.setInt(5, count);
                            sources++;
                        }
                        mergeFile.addBatch();
                        if (again) {
                            target.setHash(deleteTags, 1, last);
                            deleteTags.addBatch();
                        }
                    }
                }
                if (last == null) break;
                mergeFile.executeBatch();
                if (again) deleteTags.executeBatch();

                selectTags.setString(1, first);
                selectTags.setString(2, last);
                try (ResultSet rs = selectTags.executeQuery()) {
                    while (rs.next()) {
                        String hash = rs.getString(1);
                        if (DatabaseManager.hashBytes(hash) == null) continue;
                        target.setHash(insertTag, 1, hash);
                        insertTag.setByte(2, rs.getByte(2));
                        insertTag.setString(3, rs.getString(3));
                        String text = rs.getString(4);
                        if (text == null) insertTag.setNull(4, Types.CLOB); else insertTag.setString(4, text);
                        byte[] blob = rs.getBytes(5);
                        if (blob == null) insertTag.setNull(5, Types.BLOB); else insertTag.setBytes(5, blob);
                        insertTag.addBatch();
                        tags++;
                    }
                }
                insertTag.executeBatch();
                mysql.commit();

                after = last;
                files += pageFiles;
                done += pageFiles;
                int percent = total == 0 ? 100 : (int) (Math.min(done, total) * 100 / total);
                if (percent >= lastReported + 5) {
                    lastReported = percent;
                    logger.info("H2 import: {}% ({} of {} files)", percent, String.format("%,d", done),
                            String.format("%,d", total));
                }
            }
        } catch (SQLException e) {
            try { mysql.rollback(); } catch (SQLException ex) { /* ignore */ }
            throw e;
        } finally {
            try { mysql.setAutoCommit(true); } catch (SQLException e) { /* ignore */ }
        }
        if (notHashes > 0) {
            logger.warn("H2 import: {} files whose hash is not 32 hexadecimal digits left out", notHashes);
        }
        return new long[]{files - notHashes, tags, sources};
    }

    private static long countRows(Connection connection, String table) throws SQLException {
        try (Statement stmt = connection.createStatement(); ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
