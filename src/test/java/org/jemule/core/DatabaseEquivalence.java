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

import java.io.PrintStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.function.Consumer;

/**
 * Non-regression between an H2 and a MySQL database holding the same data: every file with its tags and source
 * count, the other tables, the searches of an index loaded from each, then the same writes applied to both. Each
 * step is timed on both sides. The differences found are collected in {@link #differences()}.
 */
final class DatabaseEquivalence {
    /** Examples of differing files printed at most. */
    private static final int EXAMPLES = 10;
    /** Number of files written, updated or deleted by {@link #applySameWrites}. */
    static final int WRITE_BATCH = 10_000;
    static final int WRITE_SINGLE = 500;

    private final DatabaseManager h2;
    private final DatabaseManager mysql;
    private final PrintStream out;
    private final List<String> differences = new ArrayList<>();
    /** Step label → {H2 ms, MySQL ms}. */
    private final Map<String, double[]> timings = new LinkedHashMap<>();
    /** Names of one file in every {@link #NAME_SAMPLE_EVERY}, read from H2: the words the searches use. */
    private final List<String> sampledNames = new ArrayList<>();
    private final List<String> sampledHashes = new ArrayList<>();
    private static final int NAME_SAMPLE_EVERY = 211;

    DatabaseEquivalence(DatabaseManager h2, DatabaseManager mysql, PrintStream out) {
        this.h2 = h2;
        this.mysql = mysql;
        this.out = out;
    }

    List<String> differences() {
        return differences;
    }

    private void difference(String text) {
        differences.add(text);
        if (differences.size() <= 50) out.println("  DIFFERENCE " + text);
    }

    /** Runs {@code step} on both databases, H2 first, and records the durations. */
    private void timed(String label, Consumer<DatabaseManager> step) {
        long start = System.nanoTime();
        step.accept(h2);
        double h2Ms = (System.nanoTime() - start) / 1e6;
        start = System.nanoTime();
        step.accept(mysql);
        double mysqlMs = (System.nanoTime() - start) / 1e6;
        timings.merge(label, new double[]{h2Ms, mysqlMs}, (a, b) -> new double[]{a[0] + b[0], a[1] + b[1]});
    }

    void time(String label, double h2Ms, double mysqlMs) {
        timings.put(label, new double[]{h2Ms, mysqlMs});
    }

    /** A file as text, its tags sorted (H2 does not order them) and the byte values in hex. */
    static String canonical(FileMetadata meta) {
        List<String> tags = new ArrayList<>();
        for (Tag tag : meta.tags()) {
            if (Tag.NAME_SOURCES.equals(tag.name())) continue; // compared as sourceCount
            Object value = tag.value();
            String text = value instanceof byte[] bytes ? "bytes:" + HexFormat.of().formatHex(bytes)
                    : value == null ? "null" : value.getClass().getSimpleName() + ":" + value;
            tags.add(tag.type() + "/" + HexFormat.of().formatHex(tag.name().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    + "=" + text);
        }
        Collections.sort(tags);
        return key(meta) + "|" + meta.name() + "|" + meta.size() + "|" + meta.type() + "|" + meta.sourceCount() + "|" + tags;
    }

    /** A file's hash compared in lower case: MySQL gives back the 16 bytes it keeps in lower case. */
    private static String key(FileMetadata meta) {
        return meta.hash().toLowerCase(Locale.ROOT);
    }

    private static long digest(String text) {
        long h = 0xcbf29ce484222325L; // FNV-1a 64
        for (int i = 0; i < text.length(); i++) {
            h ^= text.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** Every file of both databases, compared one by one. */
    void compareFiles(String when) {
        out.println("Files (" + when + ")");
        Map<String, Long> h2Files = new HashMap<>();
        boolean sample = sampledNames.isEmpty();
        long[] read = new long[1];
        long start = System.nanoTime();
        h2.forEachFile(meta -> {
            h2Files.put(key(meta), digest(canonical(meta)));
            if (sample && read[0]++ % NAME_SAMPLE_EVERY == 0) {
                sampledNames.add(meta.name());
                sampledHashes.add(meta.hash());
            }
        });
        double h2Ms = (System.nanoTime() - start) / 1e6;
        int h2Count = h2Files.size();
        List<String> differing = new ArrayList<>();
        long[] extra = new long[1], same = new long[1];
        start = System.nanoTime();
        mysql.forEachFile(meta -> {
            Long expected = h2Files.remove(key(meta));
            if (expected == null) {
                if (extra[0]++ < EXAMPLES) difference("only in MySQL: " + canonical(meta));
            } else if (expected != digest(canonical(meta))) {
                if (differing.size() < EXAMPLES) differing.add(key(meta));
                else differing.add(null);
            } else {
                same[0]++;
            }
        });
        double mysqlMs = (System.nanoTime() - start) / 1e6;
        time("read every file (" + when + ")", h2Ms, mysqlMs);
        out.printf("  %,d files in H2, %,d identical in MySQL, %,d only in H2, %,d only in MySQL, %,d different%n",
                h2Count, same[0], h2Files.size(), extra[0], differing.size());
        h2Files.keySet().stream().limit(EXAMPLES).forEach(hash -> difference("only in H2: " + hash));
        if (h2Files.size() > EXAMPLES) difference((h2Files.size() - EXAMPLES) + " more files only in H2");
        if (extra[0] > EXAMPLES) difference((extra[0] - EXAMPLES) + " more files only in MySQL");
        if (!differing.isEmpty()) {
            Set<String> examples = new HashSet<>(differing);
            examples.remove(null);
            Map<String, String> h2Text = new HashMap<>(), mysqlText = new HashMap<>();
            h2.forEachFile(meta -> { if (examples.contains(key(meta))) h2Text.put(key(meta), canonical(meta)); });
            mysql.forEachFile(meta -> { if (examples.contains(key(meta))) mysqlText.put(key(meta), canonical(meta)); });
            for (String hash : examples) difference("different file:\n    H2    " + h2Text.get(hash) + "\n    MySQL " + mysqlText.get(hash));
            if (differing.size() > examples.size()) difference((differing.size() - examples.size()) + " more different files");
        }
    }

    /** Banned names, statistics and metrics samples. */
    void compareTables(String when) {
        out.println("Banned names, statistics, metrics (" + when + ")");
        List<List<String>> names = new ArrayList<>();
        timed("read every banned name (" + when + ")", db -> {
            List<String> all = new ArrayList<>();
            String last = null;
            List<String> page;
            do {
                page = db.bannedNamesAfter(last, 5_000);
                all.addAll(page);
                if (!page.isEmpty()) last = page.getLast();
            } while (page.size() == 5_000);
            names.add(all);
        });
        // MySQL cannot hold the names longer than 768 characters: they were left out of the import
        List<String> h2Names = names.get(0).stream().filter(n -> n.length() <= DatabaseManager.MYSQL_SQL.maxBannedNameLength()).toList();
        if (!h2Names.equals(names.get(1))) {
            difference("banned names: " + h2Names.size() + " in H2, " + names.get(1).size() + " in MySQL");
        }
        // The bans written by applySameWrites are dated by each database when written: their dates are compared
        // only when both come from H2 (the import)
        boolean withDates = when.equals("imported");
        List<List<String>> recent = new ArrayList<>();
        timed("200 most recent banned names (" + when + ")", db -> recent.add(db.loadRecentBannedNames(200).stream()
                .filter(n -> n.name().length() <= DatabaseManager.MYSQL_SQL.maxBannedNameLength())
                .map(n -> n.name() + "|" + n.reason() + (withDates ? "|" + n.addedAt() : "")).toList()));
        // H2 may list a name too long for MySQL among its 200: compare the names both lists can hold
        int common = Math.min(recent.get(0).size(), recent.get(1).size());
        if (!recent.get(0).subList(0, common).equals(recent.get(1).subList(0, common))) {
            difference("most recent banned names differ:\n    H2    " + recent.get(0).subList(0, Math.min(common, 5))
                    + "\n    MySQL " + recent.get(1).subList(0, Math.min(common, 5)));
        }
        Map<String, Long> h2Stats = stats(h2), mysqlStats = stats(mysql);
        if (!h2Stats.equals(mysqlStats)) difference("stats: H2 " + h2Stats + ", MySQL " + mysqlStats);
        List<List<String>> samples = new ArrayList<>();
        timed("read the metrics history (" + when + ")", db -> samples.add(db.loadMetricsSamples(0).stream()
                .map(Arrays::toString).toList()));
        if (!samples.get(0).equals(samples.get(1))) {
            difference("metrics: " + samples.get(0).size() + " samples in H2, " + samples.get(1).size() + " in MySQL");
        }
        out.printf("  %,d banned names, %d statistics, %,d metrics samples%n", names.get(1).size(), mysqlStats.size(),
                samples.get(1).size());
    }

    private static Map<String, Long> stats(DatabaseManager db) {
        Map<String, Long> stats = new TreeMap<>();
        try (Statement stmt = db.connection().createStatement();
             ResultSet rs = stmt.executeQuery("SELECT stat_key, stat_value FROM stats")) {
            while (rs.next()) stats.put(rs.getString(1), rs.getLong(2));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        stats.remove(DatabaseManager.SCHEMA_VERSION_KEY);
        stats.remove(H2ToMySqlImport.STATE_KEY);
        return stats;
    }

    /** Words of the sampled names, at most {@code count}, each searched alone. */
    private List<String> words(int count) {
        LinkedHashSet<String> words = new LinkedHashSet<>();
        for (String name : sampledNames) {
            for (String word : name.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                if (word.length() >= 3) words.add(word);
            }
            if (words.size() >= count) break;
        }
        return new ArrayList<>(words).subList(0, Math.min(count, words.size()));
    }

    /**
     * An index loaded from each database, then the same searches on both: every result without limit as a set of
     * hashes, and the first 300 by their source counts (files of the same count may come in another order).
     */
    void compareIndexes() {
        out.println("Index loaded from each database, searches");
        FileIndex[] indexes = new FileIndex[2];
        timed("startup load of the index", db -> indexes[db == h2 ? 0 : 1] = new FileIndex(db));
        if (indexes[0].fileCount() != indexes[1].fileCount()) {
            difference("indexed files: " + indexes[0].fileCount() + " from H2, " + indexes[1].fileCount() + " from MySQL");
        }
        List<String> words = words(300);
        List<SearchQuery> queries = new ArrayList<>();
        for (String word : words) queries.add(new SearchQuery.TermQuery(word, SearchQuery.ID_FILENAME));
        for (int i = 0; i + 1 < words.size() && i < 100; i += 2) {
            queries.add(new SearchQuery.AndQuery(new SearchQuery.TermQuery(words.get(i), SearchQuery.ID_FILENAME),
                    new SearchQuery.TermQuery(words.get(i + 1), SearchQuery.ID_FILENAME)));
        }
        for (int i = 0; i < words.size() && i < 50; i++) {
            queries.add(new SearchQuery.AndQuery(new SearchQuery.TermQuery(words.get(i), SearchQuery.ID_FILENAME),
                    new SearchQuery.NumericQuery(SearchQuery.ID_FILESIZE, SearchQuery.OP_GREATER, 100_000_000L)));
            queries.add(new SearchQuery.AndQuery(new SearchQuery.TermQuery(words.get(i), SearchQuery.ID_FILENAME),
                    new SearchQuery.TermQuery("Video", SearchQuery.ID_FILETYPE)));
        }
        long results = 0;
        double[] durations = new double[2];
        for (SearchQuery query : queries) {
            Set<String>[] all = new Set[2];
            List<Integer>[] top = new List[2];
            for (int side = 0; side < 2; side++) {
                all[side] = new HashSet<>();
                for (FileMetadata meta : indexes[side].searchComplex(query, 1_000_000)) all[side].add(meta.hash().toLowerCase(Locale.ROOT));
                long start = System.nanoTime();
                List<FileMetadata> first = indexes[side].searchComplex(query, 300);
                durations[side] += (System.nanoTime() - start) / 1e6;
                top[side] = first.stream().map(FileMetadata::sourceCount).toList();
            }
            results += all[1].size();
            if (!all[0].equals(all[1])) {
                difference("search " + query + ": " + all[0].size() + " results from H2, " + all[1].size() + " from MySQL");
            } else if (!top[0].equals(top[1])) {
                difference("search " + query + ": first 300 by source count differ");
            }
        }
        time(queries.size() + " searches, limit 300 (same index code)", durations[0], durations[1]);
        out.printf("  %,d files indexed from each, %d searches, %,d results in all%n", indexes[1].fileCount(),
                queries.size(), results);
    }

    /** The same writes on both databases: new files by batch and one by one, source counts, deletions, bans, stats. */
    void applySameWrites() {
        out.println("Same writes on both databases");
        List<FileMetadata> batch = new ArrayList<>(WRITE_BATCH);
        for (int i = 0; i < WRITE_BATCH; i++) batch.add(newFile(i));
        timed(String.format("save %,d new files in one batch", WRITE_BATCH), db -> db.saveFiles(batch));
        timed(String.format("save %,d files one by one", WRITE_SINGLE), db -> {
            for (int i = 0; i < WRITE_SINGLE; i++) db.saveFile(newFile(WRITE_BATCH + i));
        });
        // Existing files: new source counts, then a few deleted
        List<FileMetadata> counted = new ArrayList<>();
        for (int i = 0; i < sampledHashes.size() && counted.size() < WRITE_BATCH; i++) {
            counted.add(FileMetadata.withoutSources(sampledHashes.get(i), "unused", 1, "Video",
                    List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 1000 + i))));
        }
        timed(String.format("update %,d source counts", counted.size()), db -> db.saveSourceCounts(counted));
        List<String> deleted = sampledHashes.subList(Math.max(0, sampledHashes.size() - WRITE_SINGLE), sampledHashes.size());
        timed(String.format("delete %,d files one by one", deleted.size()), db -> deleted.forEach(db::deleteFile));
        timed("ban 200 names, 200 more if absent, unban 50", db -> {
            for (int i = 0; i < 200; i++) db.addBannedName("equivalence banned name " + i, "admin");
            for (int i = 150; i < 350; i++) db.addBannedNameIfAbsent("equivalence banned name " + i, "child abuse");
            for (int i = 0; i < 50; i++) db.removeBannedName("equivalence banned name " + i);
        });
        timed("100 statistics, 100 metrics samples", db -> {
            for (int i = 0; i < 100; i++) db.setStat("equivalence_" + (i % 10), i);
            for (int i = 0; i < 100; i++) db.saveMetricsSample(4_000_000_000_000L + i * 300_000L, i, i, 0);
        });
    }

    private static FileMetadata newFile(int i) {
        return FileMetadata.withoutSources(String.format("EE%030X", i), "equivalence written file " + i + " é ✓.mkv",
                700_000_000L + i, i % 3 == 0 ? null : "Video",
                List.of(new Tag(Tag.TYPE_STRING, Tag.idName(SearchQuery.ID_MEDIA_CODEC), "h264"),
                        new Tag(Tag.TYPE_INTEGER, Tag.idName(SearchQuery.ID_MEDIA_BITRATE), 1000 + i),
                        new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 1 + i % 50)));
    }

    /** The durations of both databases, step by step. */
    void printTimings() {
        out.println();
        out.printf("%-48s %12s %12s %9s%n", "Step", "H2 (ms)", "MySQL (ms)", "MySQL/H2");
        for (Map.Entry<String, double[]> entry : timings.entrySet()) {
            double[] t = entry.getValue();
            out.printf("%-48s %12.1f %12.1f %9s%n", entry.getKey(), t[0], t[1],
                    t[0] > 0.05 ? String.format("%.2f", t[1] / t[0]) : "-");
        }
        out.println(differences.isEmpty() ? "No difference" : differences.size() + " differences");
    }
}
