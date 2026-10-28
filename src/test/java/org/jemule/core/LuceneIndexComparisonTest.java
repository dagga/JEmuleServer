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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link FileIndex} on Lucene against the heap index of 1.3 ({@link HeapFileIndex}), loaded from the same database:
 * the same searches must give the same files (all of them, and the first 300 by source count), before and after the
 * same writes and the same clients publishing files; the load, heap, disk, search latency and throughput and the cost
 * of the writes are measured.
 * <p>
 * The production run works on a copy of a real H2 database, outside the regular suite:
 * {@code ./gradlew benchmark --tests '*LuceneIndexComparisonTest' -Ph2=/path/to/jemule_db.mv.db -Pheap=4g -Pcpus=6}.
 */
class LuceneIndexComparisonTest {
    private static final PrintStream out = System.out;
    private static final int TOP = 300;

    /** A search of the comparison, with its family for the timings. */
    record Search(String family, SearchQuery query) {}

    @Test
    void aSyntheticIndexAnswersLikeFileIndex(@TempDir Path dir) throws Exception {
        String dbPath = dir.resolve("synthetic").toString();
        Random random = new Random(11);
        String[] words = {"film", "Film", "élan", "elan", "東京", "música", "s01e02", "1080p", "x264", "linux", "iso",
                "album", "live", "remaster", "dvdrip", "noël", "naïve", "the", "matrix", "reloaded", "ubuntu", "a", "b2"};
        String[] extensions = {"avi", "mkv", "mp3", "iso", "zip", "jpg", "pdf", "tar.gz", ""};
        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            List<FileMetadata> files = new ArrayList<>();
            for (int i = 0; i < 20_000; i++) {
                StringBuilder name = new StringBuilder();
                for (int w = 0, n = 1 + random.nextInt(5); w < n; w++) {
                    name.append(words[random.nextInt(words.length)]).append(random.nextBoolean() ? ' ' : '.');
                }
                String extension = extensions[random.nextInt(extensions.length)];
                name.append(i).append(extension.isEmpty() ? "" : "." + extension);
                List<Tag> tags = new ArrayList<>();
                if (i % 3 == 0) tags.add(new Tag(Tag.TYPE_STRING, Tag.idName(SearchQuery.ID_MEDIA_CODEC), i % 2 == 0 ? "xvid" : "h264"));
                if (i % 5 == 0) tags.add(new Tag(Tag.TYPE_INTEGER, Tag.idName(SearchQuery.ID_MEDIA_LENGTH), random.nextInt(7_200)));
                if (i % 6 == 0) tags.add(new Tag(Tag.TYPE_INT16, Tag.idName(SearchQuery.ID_MEDIA_BITRATE), (short) (i % 12 * 32)));
                if (i % 8 == 0) tags.add(new Tag(Tag.TYPE_STRING, Tag.idName(SearchQuery.ID_MEDIA_ARTIST),
                        words[i % words.length] + " " + words[(i / 8) % words.length]));
                if (i == 4_242) tags.add(new Tag(Tag.TYPE_STRING, Tag.idName(SearchQuery.ID_MEDIA_TITLE), "film ".repeat(7_000)));
                if (i % 7 != 0) tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, random.nextInt(i % 11 == 0 ? 5_000 : 30)));
                String type = i % 4 == 0 ? null : i % 4 == 1 ? "Video" : i % 4 == 2 ? "Audio" : "";
                files.add(FileMetadata.withoutSources(String.format("%016X%016X", random.nextLong(), i), name.toString(),
                        i % 9 == 0 ? 5_000_000_000L + i : 1_000L + random.nextInt(900_000_000), type, tags));
            }
            db.saveFiles(files);
            Comparison comparison = new Comparison(db, dir.resolve("lucene"), false);
            try (comparison) {
                comparison.load();
                List<Search> searches = comparison.searches(new Random(5), 120);
                comparison.compare(searches, "loaded");
                comparison.applySameWrites(new Random(9), 2_000);
                comparison.compare(searches, "after the writes");
                comparison.publishByClients(new Random(13), 2_000);
                comparison.compare(searches, "after clients published files");
            }
            assertEquals(List.of(), comparison.differences);
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("benchmark")
    void aProductionDatabaseAnswersLikeFileIndex() throws Exception {
        String source = System.getProperty("jemule.equivalence.h2");
        Assumptions.assumeTrue(source != null && !source.isBlank(), "no H2 database given (-Ph2)");
        Path work = Path.of("build", "lucene-comparison").toAbsolutePath();
        deleteTree(work);
        Files.createDirectories(work);
        Files.copy(Path.of(source), work.resolve("production.mv.db"), StandardCopyOption.REPLACE_EXISTING);
        out.printf("%n=== FileIndex / Lucene: %s (%,d MB), %d CPUs, heap %,d MB ===%n", source,
                Files.size(work.resolve("production.mv.db")) / 1_048_576, Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory() / 1_048_576);
        Comparison comparison;
        try (DatabaseManager db = new DatabaseManager(work.resolve("production").toString())) {
            comparison = new Comparison(db, work.resolve("lucene"), true);
            try (comparison) {
                comparison.load();
                List<Search> searches = comparison.searches(new Random(5), 300);
                comparison.compare(searches, "loaded");
                comparison.timeSearches(searches, TOP, 3);
                comparison.timeSearches(searches, 1_200, 1);
                comparison.throughput(searches);
                comparison.applySameWrites(new Random(9), 20_000);
                comparison.compare(searches, "after the writes");
                comparison.publishByClients(new Random(13), 20_000);
                comparison.compare(searches, "after clients published files");
                comparison.printSummary();
            }
        } finally {
            deleteTree(work);
        }
        assertEquals(List.of(), comparison.differences);
    }

    /** The two indexes of one database, and what was measured on them. */
    static final class Comparison implements AutoCloseable {
        private final DatabaseManager db;
        private final Path lucenePath;
        private final boolean verbose;
        private HeapFileIndex current;
        private FileIndex lucene;
        final List<String> differences = new ArrayList<>();
        /** Lines of the summary table: what, FileIndex, Lucene. */
        private final List<String[]> summary = new ArrayList<>();

        Comparison(DatabaseManager db, Path lucenePath, boolean verbose) {
            this.db = db;
            this.lucenePath = lucenePath;
            this.verbose = verbose;
        }

        /** Loads both indexes from the database, timed, with the heap each one takes. */
        void load() throws IOException {
            long base = usedHeapAfterGc();
            long start = System.nanoTime();
            current = new HeapFileIndex(db);
            double currentLoad = seconds(start);
            current.setPersistenceExecutor(task -> { }); // the writes below are not timed with the database's
            long currentHeap = usedHeapAfterGc() - base;
            start = System.nanoTime();
            lucene = new FileIndex(db, null, org.jemule.security.ChildAbuseFilter.defaults(), lucenePath);
            double luceneBuild = seconds(start);
            lucene.setPersistenceExecutor(task -> { });
            long luceneHeap = usedHeapAfterGc() - base - currentHeap;
            row("files indexed", String.format("%,d", current.fileCount()), String.format("%,d", lucene.fileCount()));
            row("distinct words", String.format("%,d", current.wordCount()), String.format("%,d", lucene.wordCount()));
            row("load from the database", String.format("%.1f s", currentLoad),
                    String.format("%.1f s (the database already read once)", luceneBuild));
            row("heap after GC", mb(currentHeap), mb(luceneHeap) + " (words for the substrings included)");
            row("disk", "- (the database only)", mb(lucene.indexBytes()) + " of index, read through mmap");
            if (current.fileCount() != lucene.fileCount()) {
                difference("indexed files: " + current.fileCount() + " in FileIndex, " + lucene.fileCount() + " in Lucene");
            }
        }

        /**
         * Searches of every kind, built from words of random files: frequent and random words, 2 and 3 character
         * substrings, two words in one keyword, AND / OR / NOT, size, type, extension, sources, media tags, and a
         * few without keyword.
         */
        List<Search> searches(Random random, int perFamily) throws IOException {
            List<FileMetadata> sample = current.sampleFiles(perFamily * 4, random);
            List<String> sampled = new ArrayList<>();
            for (FileMetadata file : sample) {
                String[] split = Keywords.split(file.name());
                if (split.length > 0) sampled.add(split[random.nextInt(split.length)]);
            }
            List<String> frequent = current.topWords(perFamily);
            List<Search> searches = new ArrayList<>();
            for (String word : frequent) searches.add(new Search("frequent word", name(word)));
            for (String word : sampled.subList(0, Math.min(perFamily, sampled.size()))) {
                searches.add(new Search("random word", name(word)));
            }
            for (int i = 0; i < perFamily / 3 && i < sampled.size(); i++) {
                String word = sampled.get(i);
                int length = i % 4 == 0 ? 2 : 3;
                if (word.length() < length) continue;
                int from = random.nextInt(word.length() - length + 1);
                searches.add(new Search(length + "-character substring", name(word.substring(from, from + length))));
            }
            for (int i = 0; i < perFamily / 3 && i < sample.size(); i++) {
                String[] split = Keywords.split(sample.get(i).name());
                if (split.length >= 2) searches.add(new Search("2 words in 1 keyword", name(split[0] + " " + split[1])));
            }
            for (int i = 0; i < perFamily / 3 && i < sample.size(); i++) {
                String[] split = Keywords.split(sample.get(sample.size() - 1 - i).name());
                if (split.length >= 2) {
                    searches.add(new Search("AND", and(name(split[0]), name(split[split.length - 1]))));
                }
            }
            int filtered = Math.min(perFamily / 6, sampled.size());
            String[] types = {"Video", "Audio", "Pro", "Doc", "Image", "Arc"};
            String[] codecs = {"xvid", "h264", "divx", "aac", "mp3"};
            for (int i = 0; i < filtered; i++) {
                SearchQuery word = name(frequent.get(i % frequent.size()));
                SearchQuery random1 = name(sampled.get(i));
                searches.add(new Search("+ size > 100 MB", and(word, new SearchQuery.NumericQuery(
                        SearchQuery.ID_FILESIZE, SearchQuery.OP_GREATER, 100_000_000L))));
                searches.add(new Search("+ size < 10 MB", and(random1, new SearchQuery.NumericQuery(
                        SearchQuery.ID_FILESIZE, SearchQuery.OP_LESS, 10_000_000L))));
                searches.add(new Search("+ type", and(word, new SearchQuery.TermQuery(types[i % types.length],
                        SearchQuery.ID_FILETYPE))));
                String extension = extension(sample.get(i).name());
                if (!extension.isEmpty()) {
                    searches.add(new Search("+ extension", and(random1, new SearchQuery.TermQuery(extension,
                            SearchQuery.ID_FORMAT))));
                }
                searches.add(new Search("+ sources > 4", and(word, new SearchQuery.NumericQuery(
                        SearchQuery.ID_SOURCES, SearchQuery.OP_GREATER, 4))));
                searches.add(new Search("OR", new SearchQuery.OrQuery(random1, name(sampled.get(sampled.size() - 1 - i)))));
                searches.add(new Search("NOT", new SearchQuery.NotQuery(word, name(frequent.get(
                        (i + 1) % frequent.size())))));
                searches.add(new Search("+ codec", and(word, new SearchQuery.TermQuery(codecs[i % codecs.length],
                        SearchQuery.ID_MEDIA_CODEC))));
                searches.add(new Search("+ length > 10 min", and(word, new SearchQuery.NumericQuery(
                        SearchQuery.ID_MEDIA_LENGTH, SearchQuery.OP_GREATER, 600))));
                searches.add(new Search("+ bitrate != 128", and(random1, new SearchQuery.NumericQuery(
                        SearchQuery.ID_MEDIA_BITRATE, SearchQuery.OP_NOTEQUAL, 128))));
                searches.add(new Search("+ artist", and(random1, new SearchQuery.TermQuery(
                        sampled.get((i + 7) % sampled.size()), SearchQuery.ID_MEDIA_ARTIST))));
                searches.add(new Search("+ title", and(word, new SearchQuery.TermQuery(
                        sampled.get((i + 3) % sampled.size()), SearchQuery.ID_MEDIA_TITLE))));
                searches.add(new Search("+ complete sources (tested)", and(word, new SearchQuery.NumericQuery(
                        SearchQuery.ID_COMPLETE_SOURCES, SearchQuery.OP_LESS, 1))));
                searches.add(new Search("+ complete sources > 0", and(random1, new SearchQuery.NumericQuery(
                        SearchQuery.ID_COMPLETE_SOURCES, SearchQuery.OP_GREATER, 0))));
            }
            searches.add(new Search("complete sources > 0 alone", new SearchQuery.NumericQuery(
                    SearchQuery.ID_COMPLETE_SOURCES, SearchQuery.OP_GREATER, 0)));
            searches.add(new Search("complete sources > 0 alone", new SearchQuery.NumericQuery(
                    SearchQuery.ID_COMPLETE_SOURCES, SearchQuery.OP_GREATER_EQUAL, 2)));
            for (int i = 0; i < 3; i++) {
                searches.add(new Search("no keyword", and(new SearchQuery.TermQuery(types[i], SearchQuery.ID_FILETYPE),
                        new SearchQuery.NumericQuery(SearchQuery.ID_FILESIZE, SearchQuery.OP_GREATER, 4_000_000_000L))));
            }
            long exact = searches.stream().filter(s -> lucene.isExact(s.query())).count();
            if (verbose) {
                out.printf("%,d searches; %,d answered by Lucene alone, %,d with the candidates tested%n",
                        searches.size(), exact, searches.size() - exact);
            }
            return searches;
        }

        /** All the files of every search, and the first 300 by source count, must be the same in both. */
        void compare(List<Search> searches, String when) throws IOException {
            long results = 0;
            int before = differences.size();
            for (Search search : searches) {
                Set<String> all = hashes(current.searchComplex(search.query(), Integer.MAX_VALUE));
                Set<String> luceneAll = hashes(lucene.searchComplex(search.query(), Integer.MAX_VALUE));
                results += all.size();
                if (!all.equals(luceneAll)) {
                    Set<String> missing = new HashSet<>(all);
                    missing.removeAll(luceneAll);
                    Set<String> extra = new HashSet<>(luceneAll);
                    extra.removeAll(all);
                    difference(when + ", " + search.query() + ": " + all.size() + " files in FileIndex, "
                            + luceneAll.size() + " in Lucene (" + missing.size() + " missing, " + extra.size() + " more)");
                    continue;
                }
                List<Integer> top = sourceCounts(current.searchComplex(search.query(), TOP));
                List<Integer> luceneTop = sourceCounts(lucene.searchComplex(search.query(), TOP));
                if (!top.equals(luceneTop)) difference(when + ", " + search.query() + ": first " + TOP + " by sources differ");
            }
            if (verbose) {
                out.printf("Compared %s: %,d searches, %,d files found in all, %d difference(s)%n", when,
                        searches.size(), results, differences.size() - before);
            }
        }

        /** Latency of every search (the best of {@code passes}), alone on the machine. */
        void timeSearches(List<Search> searches, int limit, int passes) throws IOException {
            double[][] best = new double[2][searches.size()];
            for (double[] side : best) Arrays.fill(side, Double.MAX_VALUE);
            for (int pass = 0; pass < passes; pass++) {
                for (int i = 0; i < searches.size(); i++) {
                    long start = System.nanoTime();
                    current.searchComplex(searches.get(i).query(), limit);
                    best[0][i] = Math.min(best[0][i], (System.nanoTime() - start) / 1e6);
                    start = System.nanoTime();
                    lucene.searchComplex(searches.get(i).query(), limit);
                    best[1][i] = Math.min(best[1][i], (System.nanoTime() - start) / 1e6);
                }
            }
            row(String.format("%,d searches, %,d results at most: total", searches.size(), limit),
                    String.format("%.2f s", sum(best[0]) / 1000), String.format("%.2f s", sum(best[1]) / 1000));
            for (double p : new double[]{50, 95, 99, 100}) {
                row("  " + (p == 100 ? "slowest" : "p" + (int) p), ms(percentile(best[0], p)), ms(percentile(best[1], p)));
            }
            if (!verbose || limit != TOP) return;
            Map<String, double[]> families = new LinkedHashMap<>();
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (int i = 0; i < searches.size(); i++) {
                double[] total = families.computeIfAbsent(searches.get(i).family(), f -> new double[2]);
                total[0] += best[0][i];
                total[1] += best[1][i];
                counts.merge(searches.get(i).family(), 1, Integer::sum);
            }
            out.printf("Mean latency by kind of search, %d results at most (FileIndex / Lucene):%n", limit);
            families.forEach((family, total) -> {
                int n = counts.get(family);
                out.printf("  %-28s %4d  %9s  %9s%n", family, n, ms(total[0] / n), ms(total[1] / n));
            });
        }

        /** Every search once on as many threads as processors, in a different order on each. */
        void throughput(List<Search> searches) throws Exception {
            int threads = Runtime.getRuntime().availableProcessors();
            double[] perSecond = new double[2];
            for (int side = 0; side < 2; side++) {
                int engine = side;
                try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
                    List<Future<?>> runs = new ArrayList<>();
                    long start = System.nanoTime();
                    for (int t = 0; t < threads; t++) {
                        List<Search> order = new ArrayList<>(searches);
                        Collections.shuffle(order, new Random(t));
                        runs.add(pool.submit(() -> {
                            for (Search search : order) {
                                if (engine == 0) current.searchComplex(search.query(), TOP);
                                else lucene.searchComplex(search.query(), TOP);
                            }
                            return null;
                        }));
                    }
                    for (Future<?> run : runs) run.get();
                    perSecond[side] = (double) threads * searches.size() / seconds(start);
                }
            }
            row(String.format("throughput, %d threads", threads), String.format("%,.0f searches/s", perSecond[0]),
                    String.format("%,.0f searches/s", perSecond[1]));
        }

        /**
         * The same writes on both, by batches of 1,000 (Lucene refreshed after each, so that the next search sees
         * them): new files, higher source counts of known ones, then deletions.
         */
        void applySameWrites(Random random, int count) throws IOException {
            List<FileMetadata> known = current.sampleFiles(count, random);
            List<String> words = new ArrayList<>();
            for (FileMetadata file : known.subList(0, Math.min(500, known.size()))) {
                words.addAll(Arrays.asList(Keywords.split(file.name())));
            }
            List<FileMetadata> added = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String name = Stream.generate(() -> words.get(random.nextInt(words.size()))).limit(2 + random.nextInt(4))
                        .reduce((a, b) -> a + " " + b).orElse("file") + " new" + i + (i % 2 == 0 ? ".mkv" : ".mp3");
                added.add(FileMetadata.withoutSources(String.format("AA%030X", i), name, 1_000_000L + i * 7919L,
                        i % 2 == 0 ? "Video" : "Audio", List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, i % 50))));
            }
            List<FileMetadata> counted = new ArrayList<>(known.size());
            for (FileMetadata file : known) counted.add(file.withSourceCount(file.sourceCount() + 1 + random.nextInt(20)));
            timedWrites(String.format("add %,d new files", added.size()), added);
            timedWrites(String.format("raise %,d source counts", counted.size()), counted);
            List<String> removed = known.stream().limit(count / 10).map(FileMetadata::hash).toList();
            long start = System.nanoTime();
            for (String hash : removed) current.removeFile(hash);
            double currentTime = seconds(start);
            start = System.nanoTime();
            for (String hash : removed) lucene.removeFile(hash);
            lucene.refresh();
            row(String.format("remove %,d files", removed.size()), String.format("%.2f s", currentTime),
                    String.format("%.2f s", seconds(start)));
        }

        private void timedWrites(String what, List<FileMetadata> files) throws IOException {
            long start = System.nanoTime();
            for (int i = 0; i < files.size(); i += 1_000) current.addFiles(files.subList(i, Math.min(files.size(), i + 1_000)));
            double currentTime = seconds(start);
            start = System.nanoTime();
            for (int i = 0; i < files.size(); i += 1_000) {
                lucene.addFiles(files.subList(i, Math.min(files.size(), i + 1_000)));
                lucene.refresh();
            }
            row(what + ", by 1,000", String.format("%.2f s", currentTime), String.format("%.2f s", seconds(start)));
        }

        /**
         * Clients of 50 files each publish known files and new ones, with their own source on each, to both indexes
         * (a copy of each file for each, the source maps being the indexes' own): the searches on the source count
         * and the complete sources then count the online sources.
         */
        void publishByClients(Random random, int count) throws IOException {
            List<FileMetadata> known = current.sampleFiles(count / 2, random);
            List<FileMetadata> files = new ArrayList<>(known);
            for (int i = 0; i < count - known.size(); i++) {
                files.add(FileMetadata.withoutSources(String.format("BB%030X", i), "published by a client " + i
                        + (i % 3 == 0 ? ".avi" : ".flac"), 5_000L + i, i % 3 == 0 ? "Video" : "Audio",
                        List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, i % 7))));
            }
            Collections.shuffle(files, random);
            double[] seconds = new double[2];
            for (int from = 0; from < files.size(); from += 50) {
                List<FileMetadata> offered = files.subList(from, Math.min(files.size(), from + 50));
                ClientState client = new ClientState(java.net.InetAddress.getLoopbackAddress(), 4662, from,
                        System.currentTimeMillis(), new java.util.concurrent.atomic.AtomicLong());
                // A few files are partial on the client: not counted among the complete sources
                for (int i = 0; i < offered.size(); i += 5) client.partialHashes().add(offered.get(i).hash());
                long start = System.nanoTime();
                for (FileMetadata file : offered) current.addFile(publishedBy(file, client));
                seconds[0] += seconds(start);
                start = System.nanoTime();
                for (FileMetadata file : offered) lucene.addFile(publishedBy(file, client));
                lucene.refresh();
                seconds[1] += seconds(start);
            }
            row(String.format("%,d files published by clients", files.size()), String.format("%.2f s", seconds[0]),
                    String.format("%.2f s", seconds[1]));
        }

        private static FileMetadata publishedBy(FileMetadata file, ClientState client) {
            java.util.concurrent.ConcurrentHashMap<String, ClientState> sources = new java.util.concurrent.ConcurrentHashMap<>();
            sources.put(FileIndex.sourceKey(client), client);
            List<Tag> tags = file.tags() == null ? List.of() : file.tags().stream()
                    .filter(t -> !Tag.NAME_SOURCES.equals(t.name())).toList();
            return new FileMetadata(file.hash(), file.name(), file.size(), file.type(), tags, sources);
        }

        void printSummary() {
            out.println();
            out.printf("%-44s %-24s %s%n", "", "HeapFileIndex (1.3)", "FileIndex (Lucene)");
            for (String[] line : summary) out.printf("%-44s %-24s %s%n", line[0], line[1], line[2]);
            out.printf("%n%d difference(s)%n", differences.size());
            differences.stream().limit(30).forEach(d -> out.println("  DIFFERENCE " + d));
        }

        private void row(String what, String fileIndex, String lucene) {
            summary.add(new String[]{what, fileIndex, lucene});
            if (verbose) out.printf("  %-44s %-24s %s%n", what, fileIndex, lucene);
        }

        private void difference(String text) {
            differences.add(text);
            if (verbose && differences.size() <= 30) out.println("  DIFFERENCE " + text);
        }

        @Override
        public void close() throws IOException {
            if (lucene != null) lucene.close();
        }
    }

    private static SearchQuery name(String keyword) {
        return new SearchQuery.TermQuery(keyword, SearchQuery.ID_FILENAME);
    }

    private static SearchQuery and(SearchQuery left, SearchQuery right) {
        return new SearchQuery.AndQuery(left, right);
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static Set<String> hashes(List<FileMetadata> files) {
        Set<String> hashes = new HashSet<>(files.size() * 2);
        for (FileMetadata file : files) hashes.add(file.hash());
        return hashes;
    }

    private static List<Integer> sourceCounts(List<FileMetadata> files) {
        return files.stream().map(FileMetadata::sourceCount).toList();
    }

    private static long usedHeapAfterGc() {
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static double seconds(long start) {
        return (System.nanoTime() - start) / 1e9;
    }

    private static String mb(long bytes) {
        return String.format("%,d MB", bytes / 1_048_576);
    }

    private static String ms(double millis) {
        return millis < 1 ? String.format("%.0f µs", millis * 1000) : String.format("%.1f ms", millis);
    }

    private static double sum(double[] values) {
        double total = 0;
        for (double v : values) total += v;
        return total;
    }

    private static double percentile(double[] values, double p) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(p / 100 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Collections.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
