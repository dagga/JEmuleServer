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

import org.jemule.config.DatabaseConfig;
import org.jemule.protocol.Tag;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The MySQL backend against a real server, given by -Pmysql.url=jdbc:mysql://host:port/database (and -Pmysql.user,
 * -Pmysql.password): skipped without it. The tables of that database are dropped before each test.
 */
class MySqlDatabaseTest {
    private static final String URL = System.getProperty("jemule.test.mysql.url");
    private static final String USER = System.getProperty("jemule.test.mysql.user", "root");
    private static final String PASSWORD = System.getProperty("jemule.test.mysql.password", "");

    @TempDir
    Path tempDir;

    @BeforeEach
    void dropTables() throws Exception {
        Assumptions.assumeTrue(URL != null && !URL.isBlank(), "no MySQL server given (-Pmysql.url)");
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD); Statement stmt = c.createStatement()) {
            for (String table : List.of("file_tags", "file_sources", "files", "stats", "metrics_history", "banned_names")) {
                stmt.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }

    private static DatabaseConfig mysql(boolean importFromH2) {
        return new DatabaseConfig(DatabaseConfig.Type.MYSQL, URL, USER, PASSWORD, importFromH2);
    }

    private DatabaseManager open() throws Exception {
        return open(false);
    }

    private DatabaseManager open(boolean importFromH2) throws Exception {
        return new DatabaseManager(mysql(importFromH2), tempDir.resolve("old").toString(), 50.0f, 10, 60);
    }

    private static String hash(int i) {
        return String.format("%032X", i);
    }

    private static FileMetadata file(int i, String name, int sources) {
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag(Tag.TYPE_STRING, Tag.idName(SearchQuery.ID_MEDIA_CODEC), "xvid"));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.idName(SearchQuery.ID_MEDIA_LENGTH), 3600 + i));
        if (sources > 0) tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, sources));
        return FileMetadata.withoutSources(hash(i), name, 1_000_000L + i, "Video", tags);
    }

    /** The values of the tags of a file but its source count, which the load puts back among them. */
    private static List<Object> values(FileMetadata meta) {
        return meta.tags().stream().filter(t -> !Tag.NAME_SOURCES.equals(t.name())).map(Tag::value).toList();
    }

    private static Map<String, FileMetadata> byHash(List<FileMetadata> files) {
        Map<String, FileMetadata> map = new HashMap<>();
        files.forEach(f -> map.put(f.hash(), f));
        return map;
    }

    private static long count(String table) throws Exception {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD); Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void filesTagsAndSourceCountsAreSavedAndLoaded() throws Exception {
        try (DatabaseManager db = open()) {
            assertTrue(db.isMySql());
            db.saveFiles(List.of(file(1, "Le Fabuleux Destin d'Amélie Poulain 🎬.avi", 12), file(2, "plain.avi", 0)));
            db.saveSourceCounts(List.of(file(2, "plain.avi", 7), file(99, "never saved.avi", 3)));
            // Saved again: its tags are replaced, not added
            db.saveFile(file(1, "Le Fabuleux Destin d'Amélie Poulain 🎬.avi", 15));
        }
        try (DatabaseManager db = open()) {
            Map<String, FileMetadata> files = byHash(db.loadFiles());
            assertEquals(2, files.size(), "the count of a file never saved is not written");
            FileMetadata first = files.get(hash(1));
            assertEquals("Le Fabuleux Destin d'Amélie Poulain 🎬.avi", first.name());
            assertEquals(1_000_001L, first.size());
            assertEquals("Video", first.type());
            assertEquals(15, first.sourceCount());
            assertEquals(List.of("xvid", 3601), values(first), "tags in their order");
            assertEquals(7, files.get(hash(2)).sourceCount());

            db.deleteFile(hash(1));
            assertEquals(1, db.loadFiles().size());
        }
        assertEquals(2, count("file_tags"), "the tags of the deleted file went with it");
        assertEquals(1, count("file_sources"));
    }

    @Test
    void theLoadReadsPageAfterPage() throws Exception {
        int total = DatabaseManager.MYSQL_LOAD_PAGE_FILES * 2 + 3;
        try (DatabaseManager db = open()) {
            List<FileMetadata> files = new ArrayList<>();
            for (int i = 0; i < total; i++) files.add(file(i, "file " + i + ".avi", i % 5));
            db.saveFiles(files);
        }
        try (DatabaseManager db = open()) {
            List<FileMetadata> loaded = db.loadFiles();
            assertEquals(total, loaded.size());
            assertEquals(total, byHash(loaded).size(), "no file twice");
            for (FileMetadata meta : loaded) {
                int i = Integer.parseInt(meta.hash(), 16);
                assertEquals("file " + i + ".avi", meta.name());
                assertEquals(i % 5, meta.sourceCount());
                assertEquals(2, values(meta).size(), meta.hash());
            }
            FileIndex index = new FileIndex(db);
            assertEquals(total, index.fileCount());
            assertEquals(10, index.search("file", 10).size());
        }
    }

    @Test
    void bannedNamesStatsAndMetrics() throws Exception {
        try (DatabaseManager db = open()) {
            db.addBannedName("élan", "fake");
            db.addBannedName("elan", "admin");
            db.addBannedNameIfAbsent("elan", "child abuse");
            db.addBannedName("x".repeat(769), "too long");
            assertEquals(2, db.countBannedNames(), "names compare exactly, a name longer than 768 is not recorded");
            assertEquals("admin", db.loadBannedNames().stream().filter(n -> n.name().equals("elan")).findFirst()
                    .orElseThrow().reason(), "an existing ban keeps its reason");
            assertEquals(List.of("elan", "élan"), db.bannedNamesAfter(null, 10));
            assertEquals(List.of("élan"), db.bannedNamesAfter("elan", 10));
            assertEquals(1, db.loadBannedNames("admin").size());
            assertEquals(1, db.loadRecentBannedNames(1).size());
            db.removeBannedName("élan");
            assertEquals(1, db.countBannedNames());

            db.setStat("kad_node_id_high", 42);
            db.setStat("kad_node_id_high", 43);
            assertEquals(43, db.getStat("kad_node_id_high", 0));
            assertEquals(DatabaseManager.SCHEMA_VERSION, db.getStat(DatabaseManager.SCHEMA_VERSION_KEY, 0));

            db.saveMetricsSample(1_000, 10, 1, 0);
            db.saveMetricsSample(2_000, 20, 2, 0);
            db.saveMetricsSample(2_000, 21, 3, 1_500);
            List<long[]> samples = db.loadMetricsSamples(0);
            assertEquals(1, samples.size(), "the older sample is pruned");
            assertArrayEquals(new long[]{2_000, 21, 3}, samples.getFirst());
            assertTrue(db.fileSize() > 0);
            assertFalse(db.compactOnline());
        }
    }

    @Test
    void aLostConnectionIsReopened() throws Exception {
        try (DatabaseManager db = open()) {
            db.setStat("before", 1);
            long id;
            try (Statement stmt = db.connection().createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT CONNECTION_ID()")) {
                rs.next();
                id = rs.getLong(1);
            }
            try (Connection admin = DriverManager.getConnection(URL, USER, PASSWORD);
                 Statement stmt = admin.createStatement()) {
                stmt.execute("KILL " + id);
            }
            db.setStat("after", 2);
            assertEquals(1, db.getReopenCount());
            assertEquals(2, db.getStat("after", 0));
        }
    }

    /** An H2 database written by 1.2: files, tags, counts, bans, statistics, metrics. */
    private String writeH2Database(int files) throws Exception {
        String h2Path = tempDir.resolve("old").toString();
        try (DatabaseManager h2 = new DatabaseManager(h2Path)) {
            List<FileMetadata> batch = new ArrayList<>();
            for (int i = 0; i < files; i++) batch.add(file(i, "h2 file " + i + ".mkv", i % 4));
            h2.saveFiles(batch);
            h2.addBannedName("some fake.avi", "fake");
            h2.addBannedNameIfAbsent("y".repeat(900), "child abuse");
            h2.setStat("kad_node_id_high", 1234);
            h2.setStat("kad_node_id_low", -5678);
            h2.saveMetricsSample(10_000, files, 3, 0);
        }
        return h2Path;
    }

    @Test
    void anH2DatabaseIsImportedAtTheFirstStart() throws Exception {
        int files = H2ToMySqlImport.PAGE_FILES + 10;
        writeH2Database(files);
        try (DatabaseManager db = open(true)) {
            assertEquals(H2ToMySqlImport.DONE, db.getStat(H2ToMySqlImport.STATE_KEY, 0));
            Map<String, FileMetadata> loaded = byHash(db.loadFiles());
            assertEquals(files, loaded.size());
            FileMetadata sample = loaded.get(hash(4_321));
            assertEquals("h2 file 4321.mkv", sample.name());
            assertEquals(4_321 % 4, sample.sourceCount());
            assertEquals(List.of("xvid", 3600 + 4_321), values(sample));
            assertEquals(List.of("some fake.avi"), db.bannedNamesAfter(null, 10), "a name too long for MySQL is left out");
            assertEquals(1234, db.getStat("kad_node_id_high", 0), "the Kad node keeps its ID");
            assertEquals(-5678, db.getStat("kad_node_id_low", 0));
            assertEquals(1, db.loadMetricsSamples(0).size());
        }
        assertEquals(files * 2L, count("file_tags"));

        // Done once: a file added to H2 afterwards is not copied
        try (DatabaseManager h2 = new DatabaseManager(tempDir.resolve("old").toString())) {
            h2.saveFile(file(files + 1, "late.avi", 1));
        }
        try (DatabaseManager db = open(true)) {
            assertEquals(files, db.loadFiles().size());
        }
    }

    @Test
    void anInterruptedImportResumesAfterTheLastFileCopied() throws Exception {
        int files = H2ToMySqlImport.PAGE_FILES * 2 + 10;
        writeH2Database(files);
        try (DatabaseManager db = open(true)) {
            assertEquals(files, db.loadFiles().size());
        }
        // As if the import had stopped after its first page
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD); Statement stmt = c.createStatement()) {
            stmt.execute("DELETE FROM files WHERE hash > '" + hash(H2ToMySqlImport.PAGE_FILES - 1) + "'");
            stmt.execute("UPDATE stats SET stat_value = " + H2ToMySqlImport.IN_PROGRESS
                    + " WHERE stat_key = '" + H2ToMySqlImport.STATE_KEY + "'");
        }
        assertEquals(H2ToMySqlImport.PAGE_FILES, count("files"));
        try (DatabaseManager db = open(true)) {
            assertEquals(H2ToMySqlImport.DONE, db.getStat(H2ToMySqlImport.STATE_KEY, 0));
            assertEquals(files, db.loadFiles().size());
        }
        assertEquals(files * 2L, count("file_tags"), "no tag copied twice");
    }

    @Test
    void noImportIntoADatabaseThatHoldsFiles() throws Exception {
        writeH2Database(10);
        try (DatabaseManager db = open(false)) {
            db.saveFile(file(500, "mysql own file.avi", 1));
        }
        try (DatabaseManager db = open(true)) {
            assertEquals(1, db.loadFiles().size());
            assertEquals(0, db.getStat(H2ToMySqlImport.STATE_KEY, 0));
        }
    }

    @Test
    void noH2FileNothingToImport() throws Exception {
        try (DatabaseManager db = open(true)) {
            assertTrue(db.loadFiles().isEmpty());
            assertEquals(0, db.getStat(H2ToMySqlImport.STATE_KEY, 0));
        }
    }
}
