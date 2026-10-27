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

import org.jemule.protocol.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

/**
 * Startup, memory and search latency of the server with a large database (default 1,000,000 files), like a
 * database filled by months of publications and Kad ingestion. Not part of the regular test suite:
 * {@code ./gradlew benchmark -Pfiles=1000000 -Pheap=2g}.
 */
@org.junit.jupiter.api.Tag("benchmark")
class LargeDatabaseBenchmarkTest {

    private static final String[] EXTENSIONS = {"avi", "mkv", "mp3", "iso", "zip", "pdf", "flac", "mp4"};
    private static final String[] TYPES = {"Video", "Video", "Audio", "Archive", "Archive", "Doc", "Audio", "Video"};

    /** A vocabulary of 20,000 pseudo-words, a few of them very common (Zipf-like draw). */
    private static String[] vocabulary(Random random) {
        String[] words = new String[20_000];
        for (int i = 0; i < words.length; i++) {
            StringBuilder w = new StringBuilder();
            int len = 3 + random.nextInt(6);
            for (int j = 0; j < len; j++) w.append((char) ('a' + random.nextInt(26)));
            words[i] = w.toString() + (i < 1000 ? "" : i % 7);
        }
        return words;
    }

    private static String word(String[] vocabulary, Random random) {
        double u = random.nextDouble();
        return vocabulary[(int) (vocabulary.length * u * u * u)];
    }

    /** Writes {@code count} files with one FT_SOURCES tag each, directly with JDBC batches. */
    private static void fill(String dbPath, int count, String[] vocabulary) throws Exception {
        Random random = new Random(42);
        try (DatabaseManager schema = new DatabaseManager(dbPath)) {
            // creates the tables
        }
        try (Connection c = DriverManager.getConnection("jdbc:h2:" + dbPath, "sa", "")) {
            c.setAutoCommit(false);
            try (PreparedStatement files = c.prepareStatement("INSERT INTO files (hash, name, size, type) VALUES (?, ?, ?, ?)");
                 PreparedStatement tags = c.prepareStatement(
                         "INSERT INTO file_tags (file_hash, tag_type, tag_name, tag_value_str, tag_value_blob) VALUES (?, ?, ?, ?, NULL)")) {
                byte[] hash = new byte[16];
                for (int i = 0; i < count; i++) {
                    ByteBuffer.wrap(hash).order(ByteOrder.BIG_ENDIAN).putLong(0, random.nextLong()).putLong(8, i);
                    String hex = HexFormat.of().formatHex(hash);
                    int words = 3 + random.nextInt(4);
                    StringBuilder name = new StringBuilder();
                    for (int w = 0; w < words; w++) name.append(word(vocabulary, random)).append(w + 1 < words ? " " : "");
                    // Release tags, years and episode numbers make most words of a real index unique
                    if (random.nextInt(10) < 4) {
                        name.append(' ');
                        for (int k = 0; k < 8; k++) name.append((char) ('a' + random.nextInt(26)));
                    }
                    if (random.nextBoolean()) name.append(' ').append(1950 + random.nextInt(76));
                    else name.append(String.format(" s%02de%02d", 1 + random.nextInt(20), 1 + random.nextInt(30)));
                    int ext = random.nextInt(EXTENSIONS.length);
                    name.append('.').append(EXTENSIONS[ext]);
                    files.setString(1, hex);
                    files.setString(2, name.toString());
                    files.setLong(3, 1_000_000L + random.nextInt(2_000_000_000));
                    files.setString(4, TYPES[ext]);
                    files.addBatch();
                    tags.setString(1, hex);
                    tags.setByte(2, Tag.TYPE_INTEGER);
                    tags.setString(3, Tag.NAME_SOURCES);
                    tags.setString(4, String.valueOf(1 + random.nextInt(20)));
                    tags.addBatch();
                    if (i % 10_000 == 9_999) {
                        files.executeBatch();
                        tags.executeBatch();
                        c.commit();
                    }
                }
                files.executeBatch();
                tags.executeBatch();
                c.commit();
            }
        }
    }

    private static long usedHeapMb() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(200);
        }
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    private static SearchQuery keywords(String... words) {
        SearchQuery q = new SearchQuery.TermQuery(words[0], SearchQuery.ID_FILENAME);
        for (int i = 1; i < words.length; i++) {
            q = new SearchQuery.AndQuery(q, new SearchQuery.TermQuery(words[i], SearchQuery.ID_FILENAME));
        }
        return q;
    }

    private static void measure(FileIndex index, String label, SearchQuery query) {
        for (int i = 0; i < 3; i++) index.searchComplex(query, 300); // warm-up
        long[] durations = new long[20];
        int found = 0;
        for (int i = 0; i < durations.length; i++) {
            long start = System.nanoTime();
            found = index.searchComplex(query, 300).size();
            durations[i] = System.nanoTime() - start;
        }
        Arrays.sort(durations);
        System.out.printf("  %-38s %4d results   median %8.2f ms   max %8.2f ms%n", label, found,
                durations[durations.length / 2] / 1e6, durations[durations.length - 1] / 1e6);
    }

    @Test
    void largeDatabase(@TempDir Path dir) throws Exception {
        int count = Integer.getInteger("jemule.benchmark.files", 1_000_000);
        String dbPath = dir.resolve("bench").toString();
        String[] vocabulary = vocabulary(new Random(7));

        long start = System.nanoTime();
        fill(dbPath, count, vocabulary);
        System.out.printf("%n=== Large database benchmark: %,d files ===%n", count);
        System.out.printf("Database filled in %.1f s%n", (System.nanoTime() - start) / 1e9);

        // Startup breakdown: database read alone, then the child abuse filter on every name
        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            List<String> names = new java.util.ArrayList<>();
            start = System.nanoTime();
            db.forEachFile(meta -> names.add(meta.name()));
            System.out.printf("Database read alone: %.1f s%n", (System.nanoTime() - start) / 1e9);
            org.jemule.security.ChildAbuseFilter filter = org.jemule.security.ChildAbuseFilter.defaults();
            start = System.nanoTime();
            long rejected = names.stream().filter(filter::matches).count();
            System.out.printf("Child abuse filter on every name: %.1f s (%d rejected)%n", (System.nanoTime() - start) / 1e9, rejected);
        }

        long heapBefore = usedHeapMb();
        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            start = System.nanoTime();
            FileIndex index = new FileIndex(db);
            System.out.printf("Startup load: %,d files in %.1f s%n", index.fileCount(), (System.nanoTime() - start) / 1e9);
            System.out.printf("Distinct words in the index: %,d%n", index.wordCount());
            long heapMb = usedHeapMb() - heapBefore;
            System.out.printf("Heap used by the index: %,d MB (%,d bytes per file)%n", heapMb,
                    heapMb * 1024 * 1024 / Math.max(1, count));

            System.out.println("searchComplex (limit 300):");
            measure(index, "common word", keywords(vocabulary[0]));
            measure(index, "rare word", keywords(vocabulary[19_000]));
            measure(index, "two words", keywords(vocabulary[1], vocabulary[2]));
            measure(index, "absent word", keywords("zzzzqqqq"));
            measure(index, "year", keywords("1999"));
            measure(index, "episode", keywords("s03e07"));
            measure(index, "word + size > 1 GB", new SearchQuery.AndQuery(keywords(vocabulary[3]),
                    new SearchQuery.NumericQuery(SearchQuery.ID_FILESIZE, SearchQuery.OP_GREATER, 1_000_000_000L)));
            measure(index, "word + type Audio", new SearchQuery.AndQuery(keywords(vocabulary[4]),
                    new SearchQuery.TermQuery("Audio", SearchQuery.ID_FILETYPE)));

            // Adding a file (publication or Kad ingestion) on a large index
            start = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                index.addFile(new FileMetadata(String.format("%032x", i), "new file " + i + ".avi", 1000L, "Video",
                        List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 1))));
            }
            System.out.printf("addFile: %.2f ms per file (with H2 write)%n", (System.nanoTime() - start) / 1e6 / 1000);
        }
    }

    /**
     * The same database imported into MySQL, then loaded from it twice: right after the import, and again with the
     * InnoDB buffer pool warm, as when only the eD2K server restarts. Needs -Pmysql.url (its tables are dropped).
     */
    @Test
    void largeDatabaseOnMySql(@TempDir Path dir) throws Exception {
        String url = System.getProperty("jemule.test.mysql.url");
        org.junit.jupiter.api.Assumptions.assumeTrue(url != null && !url.isBlank(), "no MySQL server given (-Pmysql.url)");
        String user = System.getProperty("jemule.test.mysql.user", "root");
        String password = System.getProperty("jemule.test.mysql.password", "");
        try (Connection c = DriverManager.getConnection(url, user, password); java.sql.Statement stmt = c.createStatement()) {
            for (String table : List.of("file_tags", "file_sources", "files", "stats", "metrics_history", "banned_names")) {
                stmt.execute("DROP TABLE IF EXISTS " + table);
            }
        }
        int count = Integer.getInteger("jemule.benchmark.files", 1_000_000);
        String dbPath = dir.resolve("bench").toString();
        fill(dbPath, count, vocabulary(new Random(7)));
        System.out.printf("%n=== MySQL benchmark: %,d files ===%n", count);
        org.jemule.config.DatabaseConfig mysql =
                new org.jemule.config.DatabaseConfig(org.jemule.config.DatabaseConfig.Type.MYSQL, url, user, password, true);

        long start = System.nanoTime();
        try (DatabaseManager db = new DatabaseManager(mysql, dbPath, 50.0f, 10, 60)) {
            System.out.printf("H2 import: %.1f s, %,d MB in MySQL%n", (System.nanoTime() - start) / 1e9,
                    db.fileSize() / 1024 / 1024);
        }
        for (String run : List.of("after the import", "buffer pool warm")) {
            long heapBefore = usedHeapMb();
            try (DatabaseManager db = new DatabaseManager(mysql, dbPath, 50.0f, 10, 60)) {
                start = System.nanoTime();
                FileIndex index = new FileIndex(db);
                System.out.printf("Startup load (%s): %,d files in %.1f s, index %,d MB%n", run, index.fileCount(),
                        (System.nanoTime() - start) / 1e9, usedHeapMb() - heapBefore);
                if (run.startsWith("buffer")) {
                    start = System.nanoTime();
                    for (int i = 0; i < 1000; i++) {
                        index.addFile(new FileMetadata(String.format("%032x", i), "new file " + i + ".avi", 1000L,
                                "Video", List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 1))));
                    }
                    System.out.printf("addFile: %.2f ms per file (with MySQL write)%n",
                            (System.nanoTime() - start) / 1e6 / 1000);
                }
            }
        }
    }
}
