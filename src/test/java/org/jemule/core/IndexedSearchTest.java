package org.jemule.core;

import org.jemule.protocol.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileIndex#searchComplex} only tests the files indexed under the words containing a keyword: it must return
 * exactly the files a full scan with {@link SearchQuery#test} returns.
 */
class IndexedSearchTest {

    private static final String[] WORDS = {"concert", "live", "paris", "2024", "documentary", "ocean", "été", "jazz",
            "x264", "remastered", "Привет", "日本語", "the", "best", "of"};

    FileIndex index;
    List<FileMetadata> files;

    @BeforeEach
    void setup() {
        index = new FileIndex(null);
        files = new ArrayList<>();
        Random random = new Random(3);
        String[] separators = {" ", ".", "-", "_", "", "(", ") "};
        for (int i = 0; i < 2000; i++) {
            StringBuilder name = new StringBuilder();
            int words = 1 + random.nextInt(5);
            for (int w = 0; w < words; w++) {
                String word = WORDS[random.nextInt(WORDS.length)];
                name.append(random.nextBoolean() ? word.toUpperCase() : word);
                // An empty separator glues two words: "liveparis" must be found by "vepa" and by "paris"
                name.append(separators[random.nextInt(separators.length)]);
            }
            name.append(i % 3 == 0 ? "mkv" : "mp3");
            FileMetadata meta = new FileMetadata(String.format("%032x", i), name.toString(),
                    1000L * random.nextInt(5000), i % 3 == 0 ? "Video" : "Audio");
            files.add(meta);
            index.addFile(meta);
        }
        FileMetadata unnamed = new FileMetadata(String.format("%032x", 99_999), null, 10, "Video");
        files.add(unnamed);
        index.addFile(unnamed);
    }

    private void assertSameAsFullScan(SearchQuery query) {
        List<String> expected = files.stream().filter(query).map(FileMetadata::hash).sorted().toList();
        List<String> actual = index.searchComplex(query, Integer.MAX_VALUE).stream().map(FileMetadata::hash)
                .sorted(Comparator.naturalOrder()).toList();
        assertEquals(expected, actual, query.toString());
    }

    private static SearchQuery term(String keyword) {
        return new SearchQuery.TermQuery(keyword, SearchQuery.ID_FILENAME);
    }

    @Test
    void keywordsMatchLikeAFullScan() {
        for (String keyword : List.of("concert", "CONCERT", "cert", "vepa", "liveparis", "2024", "x26", "été", "ÉTÉ",
                "привет", "本語", "live paris", "paris-live", "zzz", "---", "", "m")) {
            assertSameAsFullScan(term(keyword));
        }
    }

    @Test
    void booleanTreesMatchLikeAFullScan() {
        SearchQuery size = new SearchQuery.NumericQuery(SearchQuery.ID_FILESIZE, SearchQuery.OP_GREATER, 2_000_000);
        SearchQuery video = new SearchQuery.TermQuery("Video", SearchQuery.ID_FILETYPE);
        assertSameAsFullScan(new SearchQuery.AndQuery(term("concert"), term("paris")));
        assertSameAsFullScan(new SearchQuery.OrQuery(term("jazz"), term("ocean")));
        assertSameAsFullScan(new SearchQuery.NotQuery(term("live"), term("2024")));
        assertSameAsFullScan(new SearchQuery.AndQuery(term("best"), size));
        assertSameAsFullScan(new SearchQuery.AndQuery(video, term("remastered")));
        // Without a file name keyword, every file is tested
        assertSameAsFullScan(size);
        assertSameAsFullScan(new SearchQuery.OrQuery(term("jazz"), video));
        assertSameAsFullScan(new SearchQuery.AndQuery(new SearchQuery.OrQuery(term("ocean"), term("zzz")),
                new SearchQuery.NotQuery(term("documentary"), video)));
    }

    @Test
    void limitAndRenamedFilesAreHandled() {
        assertEquals(10, index.searchComplex(term("the"), 10).size());

        // A file republished under another name (no source left) is found under its new name only
        String hash = files.getFirst().hash();
        index.addFile(new FileMetadata(hash, "brand new title.avi", 1000, "Video"));
        files.set(0, new FileMetadata(hash, "brand new title.avi", 1000, "Video"));
        assertSameAsFullScan(term("brand"));
        assertSameAsFullScan(term(files.get(1).name().split("[^\\p{L}\\p{N}]+")[0]));
    }

    @Test
    void resultsAreRankedBySourceCountBeforeApplyingLimit() {
        FileIndex rankedIndex = new FileIndex(null);
        rankedIndex.addFile(withSources("00000000000000000000000000000001", "ranking low.mp3", 2));
        rankedIndex.addFile(withSources("00000000000000000000000000000002", "ranking high.mp3", 9));
        rankedIndex.addFile(withSources("00000000000000000000000000000003", "ranking medium.mp3", 5));

        assertEquals(List.of(
                        "00000000000000000000000000000002",
                        "00000000000000000000000000000003"),
                rankedIndex.searchComplex(term("ranking"), 2).stream().map(FileMetadata::hash).toList());

        SearchQuery sizeFilter = new SearchQuery.NumericQuery(SearchQuery.ID_FILESIZE, SearchQuery.OP_GREATER, 0);
        assertEquals(List.of(
                        "00000000000000000000000000000002",
                        "00000000000000000000000000000003",
                        "00000000000000000000000000000001"),
                rankedIndex.searchComplex(sizeFilter, 3).stream().map(FileMetadata::hash).toList());
    }

    @Test
    void databaseLoadedSourceCountIsCompactedWithoutChangingMetadataTags() {
        List<Tag> tags = List.of(
                new Tag(Tag.TYPE_STRING, Tag.NAME_FILETYPE, "Video"),
                new Tag(Tag.TYPE_STRING, "x", "Concert"),
                new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 12));
        FileMetadata loaded = FileMetadata.withoutSources("00000000000000000000000000000004",
                "concert.mp4", 1000, "Video", tags);

        assertTrue(FileMetadata.class.isRecord());
        assertEquals(12, loaded.sourceCount());
        assertEquals(tags, loaded.tags());
        assertEquals(loaded, new FileMetadata(loaded.hash(), loaded.name(), loaded.size(), loaded.type(),
                tags));
    }

    private static FileMetadata withSources(String hash, String name, int sourceCount) {
        return new FileMetadata(hash, name, 1000, "Audio",
                List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, sourceCount)));
    }

    @Test
    void dictionarySnapshotKeepsFindingNewAndOldWords() {
        // Enough new words to rebuild the dictionary snapshot, then words added after the rebuild
        for (int i = 0; i < WordDictionary.REBUILD_THRESHOLD + 2000; i++) {
            FileMetadata meta = new FileMetadata(String.format("%032x", 100_000 + i), "album q" + i + "z live.mp3", 1000, "Audio");
            files.add(meta);
            index.addFile(meta);
        }
        assertSameAsFullScan(term("q1234z"));
        assertSameAsFullScan(term("234z"));
        assertSameAsFullScan(term("concert"));
        FileMetadata late = new FileMetadata(String.format("%032x", 500_000), "late q1234zz arrival.mp3", 1000, "Audio");
        files.add(late);
        index.addFile(late);
        assertSameAsFullScan(term("q1234z"));
        assertSameAsFullScan(term("arrival"));
        // A removed file is no longer found, even through a stale word of the snapshot
        index.removeFile(files.get(5).hash());
        files.remove(5);
        assertSameAsFullScan(term(files.get(5).name().split("[^\\p{L}\\p{N}]+")[0]));
        assertSameAsFullScan(term("q12"));
    }
}
