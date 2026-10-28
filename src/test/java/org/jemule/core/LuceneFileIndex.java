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

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Scorer;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.search.Weight;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.jemule.protocol.FileTypes;
import org.jemule.protocol.Tag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prototype of the file index on Lucene, to compare with {@link FileIndex} ({@code LuceneIndexComparisonTest}): the
 * files and the word postings live in a Lucene index on disk, read through mmap (outside the heap), instead of
 * {@link FileTable} and {@link Postings}. The searches keep the semantics of {@link FileIndex#searchComplex}: a file
 * name keyword matches the files with a word containing it, found by the same {@link WordDictionary}, so each keyword
 * becomes the set of those words.
 * <p>
 * A query that translates exactly (name keywords, size, source count, type, extension, media tags and their AND / OR /
 * NOT) is answered by Lucene alone, sorted by source count, loading only the files returned; any other query (complete
 * sources, a tag given by name) takes Lucene's candidates and tests each with {@link SearchQuery#test}, as
 * {@link FileIndex} does. A string media tag (artist, album, title, codec) is indexed whole, normalized: the values
 * containing the searched text are found in a dictionary of the distinct values of that tag, as the words of the
 * names are; a numeric one (length, bitrate, rating) is a point, absent when the file has no such tag.
 * <p>
 * Not covered by the prototype: the online sources of the clients (they would stay in the heap, keyed by hash), the
 * child abuse filter and the persistence (the database stays the reference; the index can be rebuilt from it).
 * Writes become visible to the searches at the next {@link #refresh()}.
 */
final class LuceneFileIndex implements AutoCloseable {
    private static final String ID = "id", HASH = "hash", NAME = "name", TYPE = "type", SIZE = "size", SOURCES = "src",
            WORD = "w", EFFECTIVE_TYPE = "t", EXTENSION = "ext", TAGS = "tags";
    /** Media tags indexed for the filters; a file only gets the first tag of each, as {@link SearchQuery} reads it. */
    private static final byte[] STRING_TAGS = {SearchQuery.ID_MEDIA_ARTIST, SearchQuery.ID_MEDIA_ALBUM,
            SearchQuery.ID_MEDIA_TITLE, SearchQuery.ID_MEDIA_CODEC};
    private static final byte[] NUMERIC_TAGS = {SearchQuery.ID_MEDIA_LENGTH, SearchQuery.ID_MEDIA_BITRATE,
            SearchQuery.ID_FILERATING};
    /** Tags whose value is too long for a Lucene term (32,766 bytes) on some file, by ID: those files get tested. */
    private static final String LONG_TAG = "longtag";
    private static final int MAX_TERM_BYTES = 32_766;
    private static final Sort BY_SOURCES = new Sort(new SortField(SOURCES, SortField.Type.LONG, true));

    private final Directory directory;
    private final IndexWriter writer;
    private final SearcherManager searchers;
    /** Every word of the index, for {@link WordDictionary}; a word left without file stays until the restart. */
    private final Set<String> words = ConcurrentHashMap.newKeySet();
    private final WordDictionary dictionary = new WordDictionary();
    /** Distinct normalized values of each string media tag, by field; a value left without file stays. */
    private final Map<String, Set<String>> tagValues = new ConcurrentHashMap<>();
    /** Fields of the string tags too long to be indexed on some file. */
    private final Set<String> longTags = ConcurrentHashMap.newKeySet();
    /** Source counts written since the last refresh, which the searcher does not see yet. */
    private final Map<String, Long> unrefreshed = new ConcurrentHashMap<>();

    /** A Lucene query and whether it selects exactly the files the search query matches. */
    private record Translation(Query query, boolean exact) {
        static final Translation ALL = new Translation(new MatchAllDocsQuery(), true);
        static final Translation NONE = new Translation(new MatchNoDocsQuery(), true);
        static final Translation CANDIDATES_ALL = new Translation(new MatchAllDocsQuery(), false);
    }

    /** Opens the index in {@code path}, created if absent; the words of an existing index go to the dictionary. */
    LuceneFileIndex(Path path) throws IOException {
        Files.createDirectories(path);
        directory = FSDirectory.open(path);
        IndexWriterConfig config = new IndexWriterConfig().setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
                .setRAMBufferSizeMB(64).setCommitOnClose(true);
        writer = new IndexWriter(directory, config);
        searchers = new SearcherManager(writer, null);
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            for (LeafReaderContext leaf : reader.leaves()) {
                Terms terms = leaf.reader().terms(WORD);
                TermsEnum it = terms == null ? TermsEnum.EMPTY : terms.iterator();
                for (BytesRef term = it.next(); term != null; term = it.next()) addWord(term.utf8ToString());
                for (byte id : STRING_TAGS) {
                    Terms values = leaf.reader().terms(tagField(id));
                    if (values == null) continue;
                    TermsEnum vt = values.iterator();
                    for (BytesRef value = vt.next(); value != null; value = vt.next()) {
                        values(tagField(id)).add(value.utf8ToString());
                    }
                }
                Terms longOnes = leaf.reader().terms(LONG_TAG);
                if (longOnes != null) {
                    TermsEnum lt = longOnes.iterator();
                    for (BytesRef field = lt.next(); field != null; field = lt.next()) longTags.add(field.utf8ToString());
                }
            }
        }
    }

    /** Indexes every file of the database, as {@link FileIndex} loads them (the first of two hashes differing by case). */
    void build(DatabaseManager db) throws IOException {
        Set<String> seen = new HashSet<>();
        IOException[] failure = new IOException[1];
        db.forEachFile(meta -> {
            if (failure[0] != null || !seen.add(meta.hash().toLowerCase(Locale.ROOT))) return;
            try {
                writer.addDocument(document(meta.indexed()));
            } catch (IOException e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) throw failure[0];
        writer.commit();
        refresh();
    }

    /**
     * Adds new files, or raises the source count of known ones (the higher count is kept, as in {@link FileIndex}).
     *
     * @return the number of new files
     */
    int addFiles(Collection<FileMetadata> files) throws IOException {
        int added = 0;
        IndexSearcher searcher = searchers.acquire();
        try {
            for (FileMetadata file : files) {
                String id = file.hash().toLowerCase(Locale.ROOT);
                long count = file.sourceCount();
                Long known = unrefreshed.get(id);
                if (known == null) known = sourceCount(searcher, id);
                if (known == null) {
                    writer.addDocument(document(file.indexed()));
                    unrefreshed.put(id, count);
                    added++;
                } else if (count > known) {
                    writer.updateNumericDocValue(new Term(ID, id), SOURCES, count);
                    unrefreshed.put(id, count);
                }
            }
        } finally {
            searchers.release(searcher);
        }
        return added;
    }

    /** Removes a file; false if it was not indexed (as of the last refresh). */
    boolean removeFile(String hash) throws IOException {
        String id = hash.toLowerCase(Locale.ROOT);
        IndexSearcher searcher = searchers.acquire();
        try {
            if (sourceCount(searcher, id) == null && !unrefreshed.containsKey(id)) return false;
        } finally {
            searchers.release(searcher);
        }
        writer.deleteDocuments(new Term(ID, id));
        unrefreshed.remove(id);
        return true;
    }

    /** Makes the writes visible to the searches (a running server would do it every second or so). */
    void refresh() throws IOException {
        searchers.maybeRefreshBlocking();
        unrefreshed.clear();
    }

    /** Commits the writes to disk. */
    void commit() throws IOException {
        writer.commit();
    }

    int fileCount() throws IOException {
        IndexSearcher searcher = searchers.acquire();
        try {
            return searcher.getIndexReader().numDocs();
        } finally {
            searchers.release(searcher);
        }
    }

    int wordCount() {
        return words.size();
    }

    /** Size of the index files on disk. */
    long diskBytes() throws IOException {
        long total = 0;
        for (String file : directory.listAll()) total += directory.fileLength(file);
        return total;
    }

    /** Up to {@code count} files drawn at random (for the comparison queries). */
    List<FileMetadata> sampleFiles(int count, Random random) throws IOException {
        IndexSearcher searcher = searchers.acquire();
        try {
            DirectoryReader reader = (DirectoryReader) searcher.getIndexReader();
            List<FileMetadata> files = new ArrayList<>(count);
            StoredFields stored = searcher.storedFields();
            Bits live = org.apache.lucene.index.MultiBits.getLiveDocs(reader);
            for (int tries = 0; files.size() < count && reader.maxDoc() > 0 && tries < count * 10; tries++) {
                int doc = random.nextInt(reader.maxDoc());
                if (live != null && !live.get(doc)) continue;
                Long sources = sourceCount(searcher, stored.document(doc, Set.of(HASH)).get(HASH).toLowerCase(Locale.ROOT));
                files.add(file(stored.document(doc), sources == null ? 0 : sources));
            }
            return files;
        } finally {
            searchers.release(searcher);
        }
    }

    /** Same contract as {@link FileIndex#searchComplex}: the matches with the most sources first. */
    List<FileMetadata> searchComplex(SearchQuery query, int limit) throws IOException {
        if (limit <= 0) return List.of();
        IndexSearcher searcher = searchers.acquire();
        try {
            Translation translation = translate(query);
            return translation.exact() ? topBySources(searcher, translation.query(), limit)
                    : tested(searcher, translation.query(), query, limit);
        } finally {
            searchers.release(searcher);
        }
    }

    /** Whether {@code query} is answered by Lucene alone (no file tested). */
    boolean isExact(SearchQuery query) {
        return translate(query).exact();
    }

    private List<FileMetadata> topBySources(IndexSearcher searcher, Query query, int limit) throws IOException {
        int n = Math.min(limit, searcher.count(query));
        if (n == 0) return List.of();
        TopFieldDocs top = searcher.search(query, n, BY_SOURCES);
        StoredFields stored = searcher.storedFields();
        List<FileMetadata> results = new ArrayList<>(top.scoreDocs.length);
        for (ScoreDoc hit : top.scoreDocs) {
            results.add(file(stored.document(hit.doc), (Long) ((FieldDoc) hit).fields[0]));
        }
        return results;
    }

    /** Every candidate is loaded and tested, as {@link FileIndex} tests its candidates. */
    private List<FileMetadata> tested(IndexSearcher searcher, Query candidates, SearchQuery query, int limit)
            throws IOException {
        List<FileMetadata> results = new ArrayList<>();
        Weight weight = searcher.createWeight(searcher.rewrite(candidates), ScoreMode.COMPLETE_NO_SCORES, 1f);
        for (LeafReaderContext leaf : searcher.getIndexReader().leaves()) {
            Scorer scorer = weight.scorer(leaf);
            if (scorer == null) continue;
            Bits live = leaf.reader().getLiveDocs();
            NumericDocValues sources = leaf.reader().getNumericDocValues(SOURCES);
            StoredFields stored = leaf.reader().storedFields();
            DocIdSetIterator it = scorer.iterator();
            for (int doc = it.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = it.nextDoc()) {
                if (live != null && !live.get(doc)) continue;
                long count = sources != null && sources.advanceExact(doc) ? sources.longValue() : 0;
                FileMetadata meta = file(stored.document(doc), count);
                if (query.test(meta)) results.add(meta);
            }
        }
        return results.stream()
                .sorted(Comparator.comparingInt(FileMetadata::sourceCount).reversed())
                .limit(limit)
                .toList();
    }

    private static Long sourceCount(IndexSearcher searcher, String id) throws IOException {
        TopFieldDocs top = searcher.search(new TermQuery(new Term(ID, id)), 1, BY_SOURCES);
        return top.scoreDocs.length == 0 ? null : (Long) ((FieldDoc) top.scoreDocs[0]).fields[0];
    }

    private Translation translate(SearchQuery query) {
        return switch (query) {
            case SearchQuery.TermQuery t -> switch (t.id()) {
                case SearchQuery.ID_FILENAME -> nameQuery(t.term());
                case SearchQuery.ID_FILETYPE -> new Translation(
                        new TermQuery(new Term(EFFECTIVE_TYPE, t.term().toLowerCase(Locale.ROOT))), true);
                // An extension with a dot ("tar.gz") is not one indexed word
                case SearchQuery.ID_FORMAT -> t.term().contains(".") ? Translation.CANDIDATES_ALL : new Translation(
                        new TermQuery(new Term(EXTENSION, t.term().toLowerCase(Locale.ROOT))), true);
                case SearchQuery.ID_MEDIA_CODEC, SearchQuery.ID_MEDIA_TITLE, SearchQuery.ID_MEDIA_ALBUM,
                     SearchQuery.ID_MEDIA_ARTIST -> tagQuery(t.id(), t.term());
                default -> Translation.ALL;
            };
            case SearchQuery.NumericQuery n -> switch (n.id()) {
                case SearchQuery.ID_FILESIZE -> new Translation(range(n.operator(), n.value(), SIZE_RANGE), true);
                case SearchQuery.ID_SOURCES -> new Translation(range(n.operator(), n.value(), SOURCES_RANGE), true);
                case SearchQuery.ID_MEDIA_LENGTH, SearchQuery.ID_MEDIA_BITRATE, SearchQuery.ID_FILERATING ->
                        new Translation(range(n.operator(), n.value(), tagRange(n.id())), true);
                // Counted on the online sources, which stay in the heap
                case SearchQuery.ID_COMPLETE_SOURCES -> Translation.CANDIDATES_ALL;
                default -> Translation.ALL;
            };
            case SearchQuery.AndQuery a -> {
                Translation left = translate(a.left()), right = translate(a.right());
                yield new Translation(new BooleanQuery.Builder().add(left.query(), Occur.FILTER)
                        .add(right.query(), Occur.FILTER).build(), left.exact() && right.exact());
            }
            case SearchQuery.OrQuery o -> {
                Translation left = translate(o.left()), right = translate(o.right());
                yield new Translation(new BooleanQuery.Builder().add(left.query(), Occur.SHOULD)
                        .add(right.query(), Occur.SHOULD).setMinimumNumberShouldMatch(1).build(),
                        left.exact() && right.exact());
            }
            case SearchQuery.NotQuery n -> {
                Translation left = translate(n.left()), right = translate(n.right());
                // Only an exact right side can be excluded: its candidates may hold files it does not match
                yield right.exact()
                        ? new Translation(new BooleanQuery.Builder().add(left.query(), Occur.FILTER)
                                .add(new MatchAllDocsQuery(), Occur.FILTER).add(right.query(), Occur.MUST_NOT).build(),
                                left.exact())
                        : new Translation(left.query(), false);
            }
            // Lambdas of the parser (a tag given by name): they match every file, but tested to be sure
            case null, default -> Translation.CANDIDATES_ALL;
        };
    }

    /**
     * A name keyword: for each of its words, the files with an indexed word containing it. Exact: a word of the
     * keyword (letters and digits only) can only lie inside one word of the name.
     */
    private Translation nameQuery(String term) {
        String[] tokens = Keywords.split(term);
        if (tokens.length == 0) return Translation.NONE;
        BooleanQuery.Builder all = new BooleanQuery.Builder();
        for (String token : tokens) {
            List<BytesRef> containing = new ArrayList<>();
            for (String word : dictionary.wordsContaining(token, words)) containing.add(new BytesRef(word));
            if (containing.isEmpty()) return Translation.NONE;
            all.add(new TermInSetQuery(WORD, containing), Occur.FILTER);
        }
        return new Translation(all.build(), true);
    }

    /**
     * A string media tag containing {@code term} (both normalized): the files with one of the values of the
     * dictionary that contain it. Exact unless a file has a value of that tag too long to be indexed.
     */
    private Translation tagQuery(byte id, String term) {
        String field = tagField(id);
        String text = Keywords.normalize(term);
        List<BytesRef> containing = new ArrayList<>();
        for (String value : values(field)) {
            if (value.contains(text)) containing.add(new BytesRef(value));
        }
        Query values = containing.isEmpty() ? new MatchNoDocsQuery() : new TermInSetQuery(field, containing);
        if (!longTags.contains(field)) return new Translation(values, true);
        return new Translation(new BooleanQuery.Builder().add(values, Occur.SHOULD)
                .add(new TermQuery(new Term(LONG_TAG, field)), Occur.SHOULD).build(), false);
    }

    /** A numeric field: the files with a value in [lo, hi], and those with a value at all. */
    private interface RangeField {
        Query between(long lo, long hi);

        Query present();
    }

    private static final RangeField SIZE_RANGE = new RangeField() {
        public Query between(long lo, long hi) {
            return LongPoint.newRangeQuery(SIZE, lo, hi);
        }

        public Query present() {
            return new MatchAllDocsQuery();
        }
    };

    private static final RangeField SOURCES_RANGE = new RangeField() {
        public Query between(long lo, long hi) {
            return NumericDocValuesField.newSlowRangeQuery(SOURCES, lo, hi);
        }

        public Query present() {
            return new MatchAllDocsQuery();
        }
    };

    /** A numeric media tag: a file without it matches no comparison, as in {@link SearchQuery.NumericQuery}. */
    private static RangeField tagRange(byte id) {
        String field = numericField(id);
        return new RangeField() {
            public Query between(long lo, long hi) {
                return LongPoint.newRangeQuery(field, lo, hi);
            }

            public Query present() {
                return LongPoint.newRangeQuery(field, Long.MIN_VALUE, Long.MAX_VALUE);
            }
        };
    }

    /** The values satisfying {@code <value> <operator> v}, strict operators as in {@link SearchQuery.NumericQuery}. */
    private static Query range(byte operator, long v, RangeField field) {
        return switch (operator) {
            case SearchQuery.OP_EQUAL -> field.between(v, v);
            case SearchQuery.OP_GREATER -> v == Long.MAX_VALUE ? new MatchNoDocsQuery() : field.between(v + 1, Long.MAX_VALUE);
            case SearchQuery.OP_LESS -> v == Long.MIN_VALUE ? new MatchNoDocsQuery() : field.between(Long.MIN_VALUE, v - 1);
            case SearchQuery.OP_GREATER_EQUAL -> field.between(v, Long.MAX_VALUE);
            case SearchQuery.OP_LESS_EQUAL -> field.between(Long.MIN_VALUE, v);
            case SearchQuery.OP_NOTEQUAL -> new BooleanQuery.Builder().add(field.present(), Occur.FILTER)
                    .add(field.between(v, v), Occur.MUST_NOT).build();
            default -> field.present();
        };
    }

    private static String tagField(byte id) {
        return "tag" + Integer.toHexString(id & 0xFF);
    }

    private static String numericField(byte id) {
        return "num" + Integer.toHexString(id & 0xFF);
    }

    private Set<String> values(String field) {
        return tagValues.computeIfAbsent(field, f -> ConcurrentHashMap.newKeySet());
    }

    private Document document(FileMetadata meta) {
        Document doc = new Document();
        doc.add(new StringField(ID, meta.hash().toLowerCase(Locale.ROOT), Field.Store.NO));
        doc.add(new StoredField(HASH, meta.hash()));
        if (meta.name() != null) {
            doc.add(new StoredField(NAME, meta.name()));
            for (String word : new LinkedHashSet<>(List.of(Keywords.split(meta.name())))) {
                doc.add(new StringField(WORD, word, Field.Store.NO));
                addWord(word);
            }
            String lower = meta.name().toLowerCase(Locale.ROOT);
            int dot = lower.lastIndexOf('.');
            if (dot >= 0) doc.add(new StringField(EXTENSION, lower.substring(dot + 1), Field.Store.NO));
        }
        if (meta.type() != null) doc.add(new StoredField(TYPE, meta.type()));
        String type = meta.type() == null || meta.type().isEmpty() ? FileTypes.fromFileName(meta.name()) : meta.type();
        doc.add(new StringField(EFFECTIVE_TYPE, type.toLowerCase(Locale.ROOT), Field.Store.NO));
        doc.add(new LongPoint(SIZE, meta.size()));
        doc.add(new StoredField(SIZE, meta.size()));
        doc.add(new NumericDocValuesField(SOURCES, meta.sourceCount()));
        addMediaTags(doc, meta.tags());
        byte[] tags = encodeTags(meta.tags());
        if (tags != null) doc.add(new StoredField(TAGS, tags));
        return doc;
    }

    /** The first string tag and the first integer tag of each ID, as {@link SearchQuery} reads them. */
    private void addMediaTags(Document doc, List<Tag> tags) {
        if (tags == null) return;
        for (byte id : STRING_TAGS) {
            String name = Tag.idName(id);
            for (Tag tag : tags) {
                if (!tag.name().equals(name) || !(tag.value() instanceof String value)) continue;
                String field = tagField(id);
                String normalized = Keywords.normalize(value);
                if (normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_TERM_BYTES) {
                    doc.add(new StringField(LONG_TAG, field, Field.Store.NO));
                    longTags.add(field);
                } else {
                    doc.add(new StringField(field, normalized, Field.Store.NO));
                    values(field).add(normalized);
                }
                break;
            }
        }
        for (byte id : NUMERIC_TAGS) {
            String name = Tag.idName(id);
            for (Tag tag : tags) {
                if (!tag.name().equals(name) || !tag.isInt()) continue;
                doc.add(new LongPoint(numericField(id), tag.unsignedValue()));
                break;
            }
        }
    }

    private void addWord(String word) {
        if (words.add(word)) dictionary.add(word);
    }

    private static FileMetadata file(Document doc, long sourceCount) {
        List<Tag> tags = new ArrayList<>(decodeTags(doc.getBinaryValue(TAGS)));
        tags.add(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, (int) sourceCount));
        return FileMetadata.withoutSources(doc.get(HASH), doc.get(NAME), doc.getField(SIZE).numericValue().longValue(),
                doc.get(TYPE), tags);
    }

    /** The kept tags but the source count (a doc value of its own); null when there is none. */
    private static byte[] encodeTags(List<Tag> tags) {
        if (tags == null) return null;
        List<Tag> kept = tags.stream().filter(t -> !Tag.NAME_SOURCES.equals(t.name())).toList();
        if (kept.isEmpty()) return null;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeShort(kept.size());
            for (Tag tag : kept) {
                out.writeByte(tag.type());
                out.writeUTF(tag.name());
                switch (tag.value()) {
                    case String s -> { out.writeByte('S'); out.writeUTF(s); }
                    case Integer i -> { out.writeByte('I'); out.writeInt(i); }
                    case Long l -> { out.writeByte('L'); out.writeLong(l); }
                    case Short s -> { out.writeByte('H'); out.writeShort(s); }
                    case Byte b -> { out.writeByte('B'); out.writeByte(b); }
                    case Float f -> { out.writeByte('F'); out.writeFloat(f); }
                    case null, default -> throw new IllegalArgumentException("Tag value not stored: " + tag);
                }
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Tag> decodeTags(BytesRef bytes) {
        if (bytes == null) return List.of();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes.bytes, bytes.offset, bytes.length))) {
            int n = in.readUnsignedShort();
            List<Tag> tags = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                byte type = in.readByte();
                String name = Tag.idName(in.readUTF().charAt(0));
                Object value = switch (in.readByte()) {
                    case 'S' -> in.readUTF();
                    case 'I' -> in.readInt();
                    case 'L' -> in.readLong();
                    case 'H' -> in.readShort();
                    case 'B' -> in.readByte();
                    case 'F' -> in.readFloat();
                    default -> throw new IllegalStateException("Unknown tag value kind");
                };
                tags.add(new Tag(type, name, value));
            }
            return tags;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        searchers.close();
        writer.close();
        directory.close();
    }
}
