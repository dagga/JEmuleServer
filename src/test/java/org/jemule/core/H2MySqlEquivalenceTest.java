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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Non-regression between H2 and MySQL ({@link DatabaseEquivalence}): an H2 database is imported into MySQL, then
 * both are read, searched and written to the same way and must stay identical; each step is timed on both. Needs
 * -Pmysql.url (the tables of that database are dropped).
 * <p>
 * The production check runs on a copy of a real H2 database, outside the regular suite:
 * {@code ./gradlew benchmark --tests '*H2MySqlEquivalenceTest' -Ph2=/path/to/jemule_db.mv.db -Pheap=6g -Pmysql.url=...}.
 * The file given is copied to build/equivalence first and never opened itself.
 */
class H2MySqlEquivalenceTest {
    private static final String URL = System.getProperty("jemule.test.mysql.url");
    private static final String USER = System.getProperty("jemule.test.mysql.user", "root");
    private static final String PASSWORD = System.getProperty("jemule.test.mysql.password", "");

    private static void requireMySql() throws Exception {
        Assumptions.assumeTrue(URL != null && !URL.isBlank(), "no MySQL server given (-Pmysql.url)");
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD); Statement stmt = c.createStatement()) {
            for (String table : List.of("file_tags", "file_sources", "files", "stats", "metrics_history", "banned_names")) {
                stmt.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }

    private static DatabaseManager openMySql(String h2Path) throws Exception {
        return new DatabaseManager(new DatabaseConfig(DatabaseConfig.Type.MYSQL, URL, USER, PASSWORD, true), h2Path,
                50.0f, 10, 60);
    }

    /** Imports {@code h2Path} into MySQL, then compares, writes the same to both and compares again. */
    private static List<String> importAndCompare(String h2Path) throws Exception {
        long start = System.nanoTime();
        try (DatabaseManager ignored = openMySql(h2Path)) {
            System.out.printf("H2 import into MySQL: %.1f s%n", (System.nanoTime() - start) / 1e9);
        }
        start = System.nanoTime();
        DatabaseManager h2 = new DatabaseManager(h2Path);
        double h2Open = (System.nanoTime() - start) / 1e6;
        start = System.nanoTime();
        DatabaseManager mysql = openMySql(h2Path);
        double mysqlOpen = (System.nanoTime() - start) / 1e6;
        try (h2; mysql) {
            DatabaseEquivalence equivalence = new DatabaseEquivalence(h2, mysql, System.out);
            equivalence.time("open the database", h2Open, mysqlOpen);
            equivalence.compareFiles("imported");
            equivalence.compareTables("imported");
            equivalence.compareIndexes();
            equivalence.applySameWrites();
            equivalence.compareFiles("after the writes");
            equivalence.compareTables("after the writes");
            equivalence.printTimings();
            return equivalence.differences();
        }
    }

    /**
     * Files covering what the tables can hold: every tag type, Unicode and 1,000-character names, names differing
     * by case or accent only, no type, sizes beyond 4 GB, no tag, large source counts; banned names, statistics and
     * metrics too.
     */
    @Test
    void aRichSyntheticDatabaseStaysIdentical(@TempDir Path dir) throws Exception {
        requireMySql();
        String h2Path = dir.resolve("synthetic").toString();
        Random random = new Random(3);
        String[] words = {"film", "Film", "élan", "elan", "東京", "música", "Ünïcödé", "s01e02", "1080p", "x264", "🎬",
                "linux", "iso", "album", "live", "remaster", "dvdrip", "ébauche", "noël", "naïve"};
        try (DatabaseManager db = new DatabaseManager(h2Path)) {
            List<FileMetadata> files = new ArrayList<>();
            for (int i = 0; i < 30_000; i++) {
                StringBuilder name = new StringBuilder();
                for (int w = 0, n = 2 + random.nextInt(4); w < n; w++) name.append(words[random.nextInt(words.length)]).append(' ');
                name.append(i).append(i % 997 == 0 ? " " + "long".repeat(240) : "").append(".avi");
                List<Tag> tags = new ArrayList<>();
                switch (i % 4) {
                    case 0 -> { }
                    case 1 -> {
                        tags.add(new Tag(Tag.TYPE_STRING, Tag.idName(SearchQuery.ID_MEDIA_CODEC), "xvid é 東"));
                        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.idName(SearchQuery.ID_MEDIA_LENGTH), random.nextInt(100_000)));
                    }
                    case 2 -> {
                        tags.add(new Tag(Tag.TYPE_INT16, "custom16", (short) random.nextInt(30_000)));
                        tags.add(new Tag(Tag.TYPE_INT8, "custom8", (byte) random.nextInt(100)));
                        tags.add(new Tag(Tag.TYPE_FLOAT, "rating", random.nextFloat() * 5));
                        tags.add(new Tag(Tag.TYPE_UINT64, "big", random.nextLong() >>> 1));
                        tags.add(new Tag(Tag.TYPE_BOOL, "flag", random.nextBoolean()));
                    }
                    default -> {
                        byte[] blob = new byte[1 + random.nextInt(64)];
                        random.nextBytes(blob);
                        tags.add(new Tag(Tag.TYPE_BLOB, "blob", blob));
                        byte[] hash = new byte[16];
                        random.nextBytes(hash);
                        tags.add(new Tag(Tag.TYPE_HASH, "hash", hash));
                        tags.add(new Tag(Tag.TYPE_STRING, Tag.idName(SearchQuery.ID_MEDIA_ARTIST), ""));
                    }
                }
                if (i % 3 != 0) tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, i % 7 == 0 ? 2_000_000 : 1 + i % 40));
                String type = i % 11 == 0 ? null : i % 2 == 0 ? "Video" : "Audio";
                long size = i % 5 == 0 ? 5_000_000_000L + i : 1_000L + random.nextInt(200_000_000);
                files.add(FileMetadata.withoutSources(String.format("%016X%016X", random.nextLong(), i), name.toString(),
                        size, type, tags));
                if (files.size() == 5_000) {
                    db.saveFiles(files);
                    files.clear();
                }
            }
            for (int i = 0; i < 300; i++) db.addBannedName("banned " + words[i % words.length] + " " + i, i % 2 == 0 ? "admin" : null);
            db.addBannedNameIfAbsent("x".repeat(900), "child abuse"); // too long for MySQL: left out
            db.setStat("kad_node_id_high", Long.MIN_VALUE);
            db.setStat("kad_node_id_low", Long.MAX_VALUE);
            for (int i = 0; i < 500; i++) db.saveMetricsSample(1_700_000_000_000L + i * 300_000L, i * 10, i, 0);
        }
        assertEquals(List.of(), importAndCompare(h2Path));
    }

    @Test
    @org.junit.jupiter.api.Tag("benchmark")
    void aProductionDatabaseStaysIdentical() throws Exception {
        String source = System.getProperty("jemule.equivalence.h2");
        Assumptions.assumeTrue(source != null && !source.isBlank(), "no H2 database given (-Ph2)");
        requireMySql();
        Path work = Path.of("build", "equivalence").toAbsolutePath();
        Files.createDirectories(work);
        Path copy = work.resolve("production.mv.db");
        long start = System.nanoTime();
        Files.copy(Path.of(source), copy, StandardCopyOption.REPLACE_EXISTING);
        System.out.printf("%n=== H2 / MySQL equivalence: %s (%,d MB), copied in %.1f s ===%n", source,
                Files.size(copy) / 1_048_576, (System.nanoTime() - start) / 1e9);
        try {
            assertEquals(List.of(), importAndCompare(work.resolve("production").toString()));
        } finally {
            Files.deleteIfExists(copy);
            Files.deleteIfExists(work.resolve("production.trace.db"));
        }
    }
}
