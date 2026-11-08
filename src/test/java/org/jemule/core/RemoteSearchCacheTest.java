package org.jemule.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class RemoteSearchCacheTest {
    private static final long TTL = 15 * 60_000L;

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final AtomicLong ttl = new AtomicLong(TTL);
    private final AtomicInteger played = new AtomicInteger();

    private RemoteSearchCache cache(int maxEntries, int maxResultsPerEntry, int maxResults) {
        return new RemoteSearchCache(ttl::get, now::get, maxEntries, maxResultsPerEntry, maxResults);
    }

    private static FileMetadata file(int i) {
        return new FileMetadata("%032x".formatted(i), "file " + i + ".avi", 1000, "Video", List.of());
    }

    private static List<FileMetadata> files(int count) {
        List<FileMetadata> files = new ArrayList<>();
        for (int i = 0; i < count; i++) files.add(file(i + 1));
        return files;
    }

    /** A search on the network returning {@code results} at once, counted. */
    private Supplier<CompletableFuture<List<FileMetadata>>> network(List<FileMetadata> results) {
        return () -> {
            played.incrementAndGet();
            return CompletableFuture.completedFuture(results);
        };
    }

    @Test
    void theSameSearchWithinTheTimeToLiveIsNotPlayedAgain() throws Exception {
        RemoteSearchCache cache = cache(10, 100, 1000);
        List<FileMetadata> results = files(3);
        assertEquals(results, cache.search("k", network(results)).get());

        now.addAndGet(TTL - 1);
        assertEquals(results, cache.search("k", network(List.of(file(9)))).get(), "the results found then");
        assertEquals(1, played.get());
        assertEquals(1, cache.hits());
        assertEquals(1, cache.misses());
        assertEquals(0, cache.joins());
    }

    @Test
    void aSearchIsPlayedAgainOnceItsTimeToLiveIsOver() throws Exception {
        RemoteSearchCache cache = cache(10, 100, 1000);
        cache.search("k", network(files(2))).get();
        now.addAndGet(TTL);
        assertEquals(List.of(file(9)), cache.search("k", network(List.of(file(9)))).get());
        assertEquals(2, played.get());
        assertEquals(1, cache.size());
        assertEquals(1, cache.storedResults(), "the expired results are forgotten");
    }

    @Test
    void aShorterTimeToLiveAppliesAtOnceToTheStoredSearches() throws Exception {
        RemoteSearchCache cache = cache(10, 100, 1000);
        cache.search("k", network(files(2))).get();
        now.addAndGet(5 * 60_000L);
        ttl.set(5 * 60_000L);
        cache.search("k", network(files(2))).get();
        assertEquals(2, played.get());
    }

    @Test
    void theSameSearchStillRunningIsJoined() throws Exception {
        RemoteSearchCache cache = cache(10, 100, 1000);
        CompletableFuture<List<FileMetadata>> network = new CompletableFuture<>();
        CompletableFuture<List<FileMetadata>> first = cache.search("k", () -> {
            played.incrementAndGet();
            return network;
        });
        CompletableFuture<List<FileMetadata>> second = cache.search("k", network(files(5)));
        assertFalse(second.isDone());
        assertEquals(1, cache.joins());

        network.complete(files(2));
        assertEquals(files(2), first.get());
        assertEquals(files(2), second.get());
        assertEquals(1, played.get());
        assertTrue(cache.search("k", network(files(5))).isDone(), "then stored");
        assertEquals(1, cache.hits());
    }

    @Test
    void aJoinedSearchFailingFailsForEveryClientAndIsNotStored() {
        RemoteSearchCache cache = cache(10, 100, 1000);
        CompletableFuture<List<FileMetadata>> network = new CompletableFuture<>();
        CompletableFuture<List<FileMetadata>> first = cache.search("k", () -> network);
        CompletableFuture<List<FileMetadata>> second = cache.search("k", network(files(1)));
        network.completeExceptionally(new IllegalStateException("socket closed"));
        assertTrue(first.isCompletedExceptionally());
        assertTrue(second.isCompletedExceptionally());
        assertEquals(0, cache.size());
    }

    @Test
    void anEmptyAnswerIsNotStored() throws Exception {
        RemoteSearchCache cache = cache(10, 100, 1000);
        cache.search("k", network(List.of())).get();
        cache.search("k", network(List.of())).get();
        assertEquals(2, played.get());
        assertEquals(0, cache.size());
    }

    @Test
    void aZeroTimeToLiveDisablesTheCache() throws Exception {
        RemoteSearchCache cache = cache(10, 100, 1000);
        cache.search("k", network(files(1))).get();
        ttl.set(0);
        cache.search("k", network(files(1))).get();
        cache.search("k", network(files(1))).get();
        assertEquals(3, played.get());
        assertEquals(0, cache.size(), "the stored searches are dropped");
        assertEquals(0, cache.hits());

        CompletableFuture<List<FileMetadata>> running = new CompletableFuture<>();
        cache.search("r", () -> running);
        cache.search("r", network(files(1)));
        assertEquals(0, cache.joins(), "nothing joined either");
    }

    @Test
    void aSearchWithoutKeyIsPlayedWithoutTheCache() throws Exception {
        RemoteSearchCache cache = cache(10, 100, 1000);
        cache.search(null, network(files(1))).get();
        cache.search(null, network(files(1))).get();
        assertEquals(2, played.get());
        assertEquals(0, cache.size());
    }

    @Test
    void theNumberOfSearchesIsBoundedTheOldestForgottenFirst() throws Exception {
        RemoteSearchCache cache = cache(3, 100, 1000);
        for (int i = 0; i < 5; i++) {
            now.incrementAndGet();
            cache.search("k" + i, network(files(1))).get();
        }
        assertEquals(3, cache.size());
        cache.search("k4", network(files(1))).get();
        assertEquals(5, played.get(), "the newest is kept");
        cache.search("k0", network(files(1))).get();
        assertEquals(6, played.get(), "the oldest was forgotten");
    }

    @Test
    void theResultsAreBoundedPerSearchAndTogether() throws Exception {
        RemoteSearchCache cache = cache(100, 10, 25);
        List<FileMetadata> many = files(40);
        assertEquals(40, cache.search("a", network(many)).get().size(), "the client that played it gets them all");
        assertEquals(10, cache.search("a", network(many)).get().size(), "10 are stored");
        cache.search("b", network(files(10))).get();
        assertEquals(20, cache.storedResults());
        cache.search("c", network(files(10))).get();
        assertEquals(2, cache.size(), "the oldest search forgotten beyond 25 files");
        assertEquals(20, cache.storedResults());
        cache.search("a", network(many)).get();
        assertEquals(4, played.get(), "a played again after b and c");
    }

    @Test
    void theStatsArePrefixed() throws Exception {
        RemoteSearchCache cache = cache(10, 100, 1000);
        cache.search("k", network(files(1))).get();
        cache.search("k", network(files(1))).get();
        assertEquals(java.util.Map.of("kadCacheHits", 1L, "kadCacheMisses", 1L, "kadCacheJoins", 0L,
                "kadCacheEntries", 1), cache.stats("kadCache"));
    }

    @Test
    void theKeyIsTheSameForTheSameSearchEncodedOtherwise() {
        SearchQuery oneTerm = new SearchQuery.TermQuery("Linux  ISO", SearchQuery.ID_FILENAME);
        SearchQuery twoTerms = new SearchQuery.AndQuery(new SearchQuery.TermQuery("iso", SearchQuery.ID_FILENAME),
                new SearchQuery.TermQuery("LINUX", SearchQuery.ID_FILENAME));
        assertEquals(RemoteSearchCache.key(oneTerm), RemoteSearchCache.key(twoTerms));

        SearchQuery video = new SearchQuery.TermQuery("Video", SearchQuery.ID_FILETYPE);
        SearchQuery size = new SearchQuery.NumericQuery(SearchQuery.ID_FILESIZE, SearchQuery.OP_GREATER, 1000);
        // AND is commutative and associative
        assertEquals(RemoteSearchCache.key(new SearchQuery.AndQuery(oneTerm, new SearchQuery.AndQuery(video, size))),
                RemoteSearchCache.key(new SearchQuery.AndQuery(new SearchQuery.AndQuery(size, twoTerms),
                        new SearchQuery.TermQuery("video", SearchQuery.ID_FILETYPE))));
        // OR is commutative
        SearchQuery debian = new SearchQuery.TermQuery("debian", SearchQuery.ID_FILENAME);
        assertEquals(RemoteSearchCache.key(new SearchQuery.OrQuery(oneTerm, debian)),
                RemoteSearchCache.key(new SearchQuery.OrQuery(debian, twoTerms)));
    }

    @Test
    void theKeyTellsDifferentSearchesApart() {
        SearchQuery linux = new SearchQuery.TermQuery("linux", SearchQuery.ID_FILENAME);
        SearchQuery iso = new SearchQuery.TermQuery("iso", SearchQuery.ID_FILENAME);
        assertNotEquals(RemoteSearchCache.key(new SearchQuery.NotQuery(linux, iso)),
                RemoteSearchCache.key(new SearchQuery.NotQuery(iso, linux)), "NOT keeps its order");
        assertNotEquals(RemoteSearchCache.key(new SearchQuery.AndQuery(linux, iso)),
                RemoteSearchCache.key(new SearchQuery.OrQuery(linux, iso)));
        assertNotEquals(RemoteSearchCache.key(new SearchQuery.AndQuery(linux,
                        new SearchQuery.NumericQuery(SearchQuery.ID_FILESIZE, SearchQuery.OP_GREATER, 1000))),
                RemoteSearchCache.key(new SearchQuery.AndQuery(linux,
                        new SearchQuery.NumericQuery(SearchQuery.ID_FILESIZE, SearchQuery.OP_LESS, 1000))));
        assertNotEquals(RemoteSearchCache.key(linux),
                RemoteSearchCache.key(new SearchQuery.TermQuery("linux", SearchQuery.ID_FORMAT)));
        assertNull(RemoteSearchCache.key(null));
        assertNull(RemoteSearchCache.key(new SearchQuery.TermQuery("x".repeat(2000), SearchQuery.ID_FILETYPE)),
                "too large to be cached");
    }
}
