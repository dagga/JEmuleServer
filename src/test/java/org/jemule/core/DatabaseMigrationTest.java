package org.jemule.core;

import org.jemule.protocol.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void aVersionOneDatabaseIsMigratedAtStart() throws Exception {
        String dbPath = tempDir.resolve("olddb").toString();
        String codec = Tag.idName(SearchQuery.ID_MEDIA_CODEC);
        new DatabaseManager(dbPath).close();

        // As written by version 1: every tag in file_tags, the source count among them, no schema version
        try (Connection c = DriverManager.getConnection("jdbc:h2:" + dbPath, "sa", "");
             Statement stmt = c.createStatement()) {
            stmt.execute("DELETE FROM stats");
            stmt.execute("INSERT INTO files VALUES ('AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA', 'movie.avi', 100, 'Video')");
            stmt.execute("INSERT INTO files VALUES ('BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB', 'song.mp3', 200, 'Audio')");
            stmt.execute("INSERT INTO files VALUES ('CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC', 'other.iso', 300, 'Iso')");
            insertTag(c, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", Tag.TYPE_STRING, Tag.NAME_FILENAME, "movie.avi");
            insertTag(c, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", Tag.TYPE_INTEGER, Tag.NAME_FILESIZE, "100");
            insertTag(c, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", Tag.TYPE_INTEGER, Tag.NAME_SOURCES, "12");
            insertTag(c, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", Tag.TYPE_STRING, codec, "xvid");
            insertTag(c, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", Tag.TYPE_STRING, "Description", "dropped");
            insertTag(c, "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB", Tag.TYPE_INTEGER, Tag.NAME_SOURCES, "3");
            insertTag(c, "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB", Tag.TYPE_UINT64, Tag.NAME_SOURCES, "99999999999");
            insertTag(c, "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC", Tag.TYPE_STRING, Tag.NAME_SOURCES, "not a number");
            stmt.execute("INSERT INTO file_sources VALUES ('CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC', 8)");
            // Left by a migration interrupted before the swap: dropped and done again
            stmt.execute("CREATE TABLE file_tags_new (file_hash VARCHAR(32), tag_type TINYINT, tag_name VARCHAR(255), "
                    + "tag_value_str TEXT, tag_value_blob BLOB)");
            stmt.execute("INSERT INTO file_tags_new (file_hash, tag_name, tag_value_str) VALUES ('AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA', 'x', 'stale')");
        }

        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            assertEquals(DatabaseManager.SCHEMA_VERSION, db.getStat(DatabaseManager.SCHEMA_VERSION_KEY, 0));
            Map<String, FileMetadata> files = db.loadFiles().stream()
                    .collect(Collectors.toMap(FileMetadata::hash, f -> f));
            assertEquals(3, files.size(), "no file is lost");
            FileMetadata movie = files.get("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
            assertEquals(12, movie.sourceCount());
            assertEquals(List.of(codec, Tag.NAME_SOURCES), movie.tags().stream().map(Tag::name).toList());
            assertEquals(Integer.MAX_VALUE, files.get("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB").sourceCount(), "highest, capped");
            assertEquals(8, files.get("CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC").sourceCount(), "invalid count ignored");
        }

        try (Connection c = DriverManager.getConnection("jdbc:h2:" + dbPath, "sa", "");
             Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM file_tags")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "only the codec is left in file_tags");
        }

        // Already migrated: a new start changes nothing
        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            assertEquals(3, db.loadFiles().size());
        }
    }

    @Test
    void aVersionTwoDatabaseLosesItsBannedHashes() throws Exception {
        String dbPath = tempDir.resolve("v2db").toString();
        new DatabaseManager(dbPath).close();

        // As written by version 2 (1.2.23 and before): banned hashes, the files and their tags already migrated
        try (Connection c = DriverManager.getConnection("jdbc:h2:" + dbPath, "sa", "");
             Statement stmt = c.createStatement()) {
            stmt.execute("UPDATE stats SET stat_value = 2 WHERE stat_key = '" + DatabaseManager.SCHEMA_VERSION_KEY + "'");
            stmt.execute("CREATE TABLE banned_hashes (hash VARCHAR(32) PRIMARY KEY, reason VARCHAR(255), "
                    + "added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            stmt.execute("INSERT INTO banned_hashes (hash, reason) VALUES ('AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA', 'Child abuse material: x')");
            stmt.execute("INSERT INTO files VALUES ('BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB', 'song.mp3', 200, 'Audio')");
            stmt.execute("INSERT INTO file_tags (file_hash, tag_type, tag_name, tag_value_str) "
                    + "VALUES ('BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB', 2, 'Description', 'kept: version 2 is not migrated again')");
        }

        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            assertEquals(3L, db.getStat(DatabaseManager.SCHEMA_VERSION_KEY, 0));
            assertEquals(1, db.loadFiles().size());
            assertTrue(db.loadBannedNames().isEmpty());
        }
        try (Connection c = DriverManager.getConnection("jdbc:h2:" + dbPath, "sa", "");
             Statement stmt = c.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'BANNED_HASHES'")) {
                rs.next();
                assertEquals(0, rs.getInt(1), "banned_hashes is dropped");
            }
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM file_tags")) {
                rs.next();
                assertEquals(1, rs.getInt(1), "the tags are not migrated a second time");
            }
        }
    }

    private static void insertTag(Connection c, String hash, byte type, String name, String value) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO file_tags (file_hash, tag_type, tag_name, tag_value_str) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, hash);
            ps.setByte(2, type);
            ps.setString(3, name);
            ps.setString(4, value);
            ps.executeUpdate();
        }
    }

    @Test
    void storedSourceCountsAreParsedAndCapped() {
        assertEquals(12, DatabaseManager.sourceCount("12"));
        assertEquals(Integer.MAX_VALUE, DatabaseManager.sourceCount("99999999999"));
        assertEquals(null, DatabaseManager.sourceCount("-3"));
        assertEquals(null, DatabaseManager.sourceCount("not a number"));
        assertEquals(null, DatabaseManager.sourceCount("1234567890123456789"));
        assertEquals(null, DatabaseManager.sourceCount(null));
    }
}
