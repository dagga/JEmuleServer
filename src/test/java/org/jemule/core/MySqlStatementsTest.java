package org.jemule.core;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The MySQL statements of the file retention (issue #103), run without a MySQL server: on H2 in its MySQL
 * compatibility mode, with the MySQL tables (16 byte hashes, ON DUPLICATE KEY UPDATE). {@link MySqlDatabaseTest}
 * runs them on a real server when one is given (-Pmysql.url).
 */
class MySqlStatementsTest {
    private static final int MIGRATION_DAY = 20_000;

    private static byte[] hash(int i) {
        return HexFormat.of().parseHex(String.format("%032x", i));
    }

    private static Connection mysqlMode(String name) throws SQLException {
        return DriverManager.getConnection("jdbc:h2:mem:" + name + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "");
    }

    @Test
    void theStatementsAreTheExpectedMySqlOnes() {
        assertTrue(DatabaseManager.MYSQL_FILES.contains("last_seen SMALLINT UNSIGNED\n"),
                "two bytes, nullable, no default: null stands for the migration day");
        assertEquals("ALTER TABLE files ADD COLUMN last_seen SMALLINT UNSIGNED NULL, ALGORITHM=INSTANT",
                DatabaseManager.MYSQL_ADD_LAST_SEEN_INSTANT, "added from the metadata, without rewriting the rows");
        assertEquals(DatabaseManager.MYSQL_ADD_LAST_SEEN + ", ALGORITHM=INSTANT", DatabaseManager.MYSQL_ADD_LAST_SEEN_INSTANT);
        assertTrue(DatabaseManager.MYSQL_SQL.mergeFile().endsWith("last_seen = VALUES(last_seen)"));
        assertEquals(5, DatabaseManager.MYSQL_SQL.mergeFile().chars().filter(c -> c == '?').count());
        assertEquals(5, DatabaseManager.H2_SQL.mergeFile().chars().filter(c -> c == '?').count());
        assertEquals("SELECT hash FROM files WHERE hash > ? AND hash <= ? AND (last_seen < ? OR last_seen IS NULL) "
                + "ORDER BY hash", DatabaseManager.staleFilesSql(MIGRATION_DAY, MIGRATION_DAY + 1));
        assertEquals("SELECT hash FROM files WHERE hash > ? AND hash <= ? AND last_seen < ? ORDER BY hash",
                DatabaseManager.staleFilesSql(MIGRATION_DAY, MIGRATION_DAY), "not stale on the migration day itself");
        assertEquals("DELETE FROM files WHERE hash IN (?, ?, ?) AND last_seen < ?",
                DatabaseManager.deleteStaleFilesSql(3, MIGRATION_DAY, MIGRATION_DAY - 10));
    }

    @Test
    void aVersionFourTableGetsLastSeenAndTheFilesAreTouchedAndPurged() throws Exception {
        try (Connection c = mysqlMode("retention"); Statement stmt = c.createStatement()) {
            String v5 = DatabaseManager.MYSQL_FILES.formatted("files");
            String v4 = v5.replace(",\n    last_seen SMALLINT UNSIGNED", "");
            assertNotEquals(v5, v4);
            stmt.execute(v4);
            stmt.execute(DatabaseManager.MYSQL_FILE_TAGS.formatted("file_tags", "files"));
            for (int i = 1; i <= 4; i++) {
                stmt.execute("INSERT INTO files (hash, name, size, type) VALUES (X'" + String.format("%032x", i)
                        + "', 'file " + i + "', " + i + ", 'Video')");
                stmt.execute("INSERT INTO file_tags (file_hash, tag_type, tag_name, tag_value_str) VALUES (X'"
                        + String.format("%032x", i) + "', 2, 'codec', 'xvid')");
            }

            // H2 knows no ALGORITHM clause, as MySQL 5.7 knows no INSTANT: the plain statement follows
            assertThrows(SQLException.class, () -> stmt.execute(DatabaseManager.MYSQL_ADD_LAST_SEEN_INSTANT));
            stmt.execute(DatabaseManager.MYSQL_ADD_LAST_SEEN);
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM files WHERE last_seen IS NULL")) {
                rs.next();
                assertEquals(4, rs.getInt(1), "the rows keep null: the migration day");
            }

            // Saved again (upsert) and touched: the day is written once
            try (PreparedStatement merge = c.prepareStatement(DatabaseManager.MYSQL_SQL.mergeFile())) {
                merge.setBytes(1, hash(5));
                merge.setString(2, "new file");
                merge.setLong(3, 5);
                merge.setString(4, "Video");
                merge.setInt(5, MIGRATION_DAY + 300);
                assertEquals(1, merge.executeUpdate());
                merge.setBytes(1, hash(5));
                merge.setInt(5, MIGRATION_DAY + 301);
                merge.executeUpdate();
            }
            assertEquals(MIGRATION_DAY + 301, lastSeen(c, 5));
            int today = MIGRATION_DAY + 302;
            try (PreparedStatement touch = c.prepareStatement(DatabaseManager.TOUCH_FILE)) {
                for (int i : new int[]{1, 5, 1}) {
                    touch.setInt(1, today);
                    touch.setBytes(2, hash(i));
                    touch.setInt(3, today);
                    touch.addBatch();
                }
                int[] counts = touch.executeBatch();
                assertEquals(1, counts[0]);
                assertEquals(1, counts[1]);
                assertEquals(0, counts[2], "already written today: no write");
            }
            assertEquals(today, lastSeen(c, 1));

            // Retention of 180 days, 302 days after the migration: the files not seen since are stale
            int cutoff = today - 180;
            List<byte[]> stale = new ArrayList<>();
            try (PreparedStatement select = c.prepareStatement(DatabaseManager.staleFilesSql(MIGRATION_DAY, cutoff))) {
                select.setBytes(1, new byte[0]);
                select.setBytes(2, hash(5));
                select.setInt(3, cutoff);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) stale.add(rs.getBytes(1));
                }
            }
            assertEquals(List.of("00000000000000000000000000000002", "00000000000000000000000000000003",
                    "00000000000000000000000000000004"), stale.stream().map(HexFormat.of()::formatHex).toList());
            // File 3 is seen meanwhile: kept
            try (PreparedStatement touch = c.prepareStatement(DatabaseManager.TOUCH_FILE)) {
                touch.setInt(1, today);
                touch.setBytes(2, hash(3));
                touch.setInt(3, today);
                assertEquals(1, touch.executeUpdate());
            }
            try (PreparedStatement delete = c.prepareStatement(
                    DatabaseManager.deleteStaleFilesSql(stale.size(), MIGRATION_DAY, cutoff))) {
                for (int i = 0; i < stale.size(); i++) delete.setBytes(i + 1, stale.get(i));
                delete.setInt(stale.size() + 1, cutoff);
                assertEquals(2, delete.executeUpdate());
            }
            assertEquals(-1, lastSeen(c, 2));
            assertEquals(-1, lastSeen(c, 4));
            assertEquals(today, lastSeen(c, 3));
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM file_tags")) {
                rs.next();
                assertEquals(2, rs.getInt(1), "the tags of the files deleted go with them");
            }
        }
    }

    private static int lastSeen(Connection c, int i) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT last_seen FROM files WHERE hash = ?")) {
            ps.setBytes(1, hash(i));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }
}
