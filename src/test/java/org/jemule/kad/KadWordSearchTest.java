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

package org.jemule.kad;

import org.jemule.core.FileIndex;
import org.jemule.core.FileMetadata;
import org.jemule.core.FileSource;
import org.jemule.core.SearchQuery;
import org.jemule.protocol.Tag;
import org.jemule.security.ChildAbuseFilter;
import org.jemule.security.FakeFileDetector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The client gets the single Kad search of its query, as before; the other words of the query are searched alone in
 * the background, within bounds, and only feed the index with their clean files.
 */
class KadWordSearchTest {
    private static final String WHOLE = "aa000000000000000000000000000001";
    private static final String WHOLE_NAME = "matrix reloaded complete trilogy extended edition remastered.avi";

    private final KadUdpService udp = mock(KadUdpService.class);
    private final FileIndex index = mock(FileIndex.class);
    private final KadRoutingTable table = new KadRoutingTable(KadId.random());
    /** The keyword search requests sent, by keyword hash. */
    private final Map<KadId, List<KadPacket.SearchKeyReq>> requests = new ConcurrentHashMap<>();
    /** The files ingested into the index. */
    private final Set<String> ingested = ConcurrentHashMap.newKeySet();
    private KadSearchBridge bridge;

    @BeforeEach
    void setUp() {
        doAnswer(inv -> {
            Collection<FileMetadata> files = inv.getArgument(0);
            files.forEach(f -> ingested.add(f.hash()));
            return List.copyOf(files);
        }).when(index).addFiles(any(), any(FileSource.class));
    }

    @AfterEach
    void tearDown() {
        if (bridge != null) bridge.close();
    }

    private void addContact() throws Exception {
        table.addOrUpdate(new KadContact(table.generateRandomIdForBucket(1), InetAddress.getByName("192.0.2.10"), 4672));
    }

    /** Starts a bridge whose Kad nodes answer at once with the files of the words given, and not for the others. */
    private void start(ChildAbuseFilter filter, BiFunction<String, Integer, List<FileMetadata>> answers,
                       String... words) throws Exception {
        Map<KadId, String> byHash = java.util.Arrays.stream(words)
                .collect(Collectors.toMap(KadId::fromKeyword, w -> w));
        bridge = new KadSearchBridge(table, udp, filter, new FakeFileDetector(), index, null, 3, 500, true, 1);
        doAnswer(inv -> {
            KadPacket packet = inv.getArgument(0);
            if (packet.getOpCode() != KadOpCode.SEARCH_KEY_REQ) return null; // the lookup gets no answer
            KadPacket.SearchKeyReq req = KadPacket.parseSearchKeyReq(packet.data());
            requests.computeIfAbsent(req.keywordHash(), k -> new CopyOnWriteArrayList<>()).add(req);
            String word = byHash.get(req.keywordHash());
            if (word != null) {
                List<FileMetadata> files = answers.apply(word, req.startPosition());
                bridge.handleSearchRes(KadPacket.createSearchRes(req.keywordHash(), files),
                        inv.getArgument(1), inv.getArgument(2));
            }
            return null;
        }).when(udp).send(any(), any(), anyInt());
        bridge.start();
    }

    private void start(String... words) throws Exception {
        start(ChildAbuseFilter.defaults(), (word, start) -> wordIndex(word), words);
    }

    private static FileMetadata file(String hash, String name) {
        return new FileMetadata(hash, name, 700_000_000L, "Video", List.of(new Tag(Tag.TYPE_INTEGER, Tag.NAME_SOURCES, 3)));
    }

    private static String onlyHash(String word) {
        return String.format("%032x", word.hashCode() & 0xFFFFFFFFL);
    }

    /** The file holding every word, and one holding only {@code word}. */
    private static List<FileMetadata> wordIndex(String word) {
        return List.of(file(WHOLE, WHOLE_NAME), file(onlyHash(word), word + " only.avi"));
    }

    /** Runs the background word searches until {@code done}, or fails after 15 seconds. */
    private void runWordSearchesUntil(BooleanSupplier done) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!done.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "background word searches not over in time");
            bridge.startQueuedWordSearches(System.currentTimeMillis());
            Thread.sleep(50);
        }
    }

    private Set<KadId> hashes(String... words) {
        return java.util.Arrays.stream(words).map(KadId::fromKeyword).collect(Collectors.toSet());
    }

    @Test
    void clientGetsTheSingleSearchWhileTheOtherWordsOnlyFeedTheIndex() throws Exception {
        addContact();
        start("remastered", "reloaded", "complete", "extended", "trilogy", "edition", "matrix");
        SearchQuery query = new SearchQuery.TermQuery(
                "matrix reloaded complete trilogy extended edition remastered", SearchQuery.ID_FILENAME);

        long started = System.currentTimeMillis();
        List<FileMetadata> results = bridge.forkSearch(query).get(5, TimeUnit.SECONDS);

        // As before: the files of the longest word's search, which carries the whole query, within its timeout
        assertTrue(System.currentTimeMillis() - started < 2_500, "the client never waits for the word searches");
        assertEquals(Set.of(WHOLE, onlyHash("remastered")),
                results.stream().map(FileMetadata::hash).collect(Collectors.toSet()));
        assertArrayEquals(KadSearchTerms.encode(query, "remastered"),
                requests.get(KadId.fromKeyword("remastered")).getFirst().searchTerms());
        assertEquals(1, bridge.getSearchesForked(), "one client search");

        // Five other words at most, longest first: "matrix", the shortest, is not searched
        runWordSearchesUntil(() -> bridge.getWordSearches() == 5 && ingested.size() == 7);
        assertEquals(hashes("remastered", "reloaded", "complete", "extended", "trilogy", "edition"), requests.keySet());
        requests.values().forEach(sent -> assertEquals(1, sent.size(), "one search per word"));
        assertNull(requests.get(KadId.fromKeyword("edition")).getFirst().searchTerms(), "searched alone");
        // Every clean file found by a word goes to the index, even those not matching the query
        assertTrue(ingested.containsAll(Set.of(WHOLE, onlyHash("reloaded"), onlyHash("complete"),
                onlyHash("extended"), onlyHash("trilogy"), onlyHash("edition"))));
        assertEquals(0, bridge.getQueuedWordSearches());
    }

    @Test
    void wordsNeedFourCharactersToBeSearchedInTheBackground() throws Exception {
        addContact();
        start("documentary", "rover", "dune", "élan", "été");

        bridge.forkSearch(new SearchQuery.TermQuery("documentary rover dune élan été", SearchQuery.ID_FILENAME))
                .get(5, TimeUnit.SECONDS);
        runWordSearchesUntil(() -> bridge.getWordSearches() == 3 && ingested.size() == 5);

        // "été" has 3 characters (5 bytes): never searched alone; "dune" and "élan" (5 bytes) have 4
        assertEquals(hashes("documentary", "rover", "dune", "élan"), requests.keySet());
        assertTrue(ingested.containsAll(Set.of(onlyHash("rover"), onlyHash("dune"), onlyHash("élan"))));
    }

    @Test
    void theQueryKeywordIsSearchedForTheClientEvenWhenShort() throws Exception {
        addContact();
        start("dune", "sky");

        List<FileMetadata> results = bridge.forkSearch(new SearchQuery.TermQuery("dune sky", SearchQuery.ID_FILENAME))
                .get(5, TimeUnit.SECONDS);
        bridge.startQueuedWordSearches(System.currentTimeMillis());

        assertEquals(2, results.size(), "as before");
        assertEquals(hashes("dune"), requests.keySet(), "no other word of 4 characters");
        assertEquals(0, bridge.getWordSearches());
    }

    @Test
    void wordsOfExcludedTermsAreNotSearched() throws Exception {
        addContact();
        start("reloaded", "matrix", "trilogy");
        SearchQuery query = new SearchQuery.NotQuery(
                new SearchQuery.TermQuery("matrix reloaded", SearchQuery.ID_FILENAME),
                new SearchQuery.TermQuery("trilogy", SearchQuery.ID_FILENAME));

        bridge.forkSearch(query).get(5, TimeUnit.SECONDS);
        runWordSearchesUntil(() -> bridge.getWordSearches() == 1 && ingested.contains(onlyHash("matrix")));

        assertEquals(hashes("reloaded", "matrix"), requests.keySet());
    }

    @Test
    void childAbuseQueryIsNeverSent() throws Exception {
        addContact();
        start("pthc", "documentary");

        List<FileMetadata> results = bridge.forkSearch(
                new SearchQuery.TermQuery("documentary pthc", SearchQuery.ID_FILENAME)).get(1, TimeUnit.SECONDS);
        bridge.startQueuedWordSearches(System.currentTimeMillis());

        assertTrue(results.isEmpty());
        verify(udp, never()).send(any(), any(), anyInt()); // not even a lookup
        assertEquals(0, bridge.getQueuedWordSearches());
        assertEquals(0, bridge.getSearchesForked());
        assertEquals(0, bridge.getWordSearches());
    }

    @Test
    void noWordSearchIsSentForAWordTheFilterMatches() throws Exception {
        addContact();
        ChildAbuseFilter filter = mock(ChildAbuseFilter.class);
        when(filter.matches(anyString())).thenAnswer(inv -> "forbidden".equals(inv.getArgument(0)));
        start(filter, (word, start) -> wordIndex(word), "forbidden", "documentary", "nature");

        bridge.forkSearch(new SearchQuery.TermQuery("nature forbidden documentary", SearchQuery.ID_FILENAME))
                .get(5, TimeUnit.SECONDS);
        runWordSearchesUntil(() -> bridge.getWordSearches() == 1 && ingested.contains(onlyHash("nature")));

        assertEquals(hashes("documentary", "nature"), requests.keySet());
        assertFalse(requests.containsKey(KadId.fromKeyword("forbidden")));
    }

    @Test
    void wordSearchesAreQueuedWithinBoundsAndDroppedWhenTheQueueIsFull() throws Exception {
        // Isolated DHT: every search is over at once, so only the bounds hold the word searches back
        start();
        int queries = KadSearchBridge.MAX_QUEUED_WORD_SEARCHES + 10;
        for (int i = 0; i < queries; i++) {
            bridge.forkSearch(new SearchQuery.TermQuery("documentary keyword" + i, SearchQuery.ID_FILENAME));
        }
        assertEquals(10, bridge.getWordSearchesDropped());

        long now = System.currentTimeMillis();
        bridge.startQueuedWordSearches(now);
        bridge.startQueuedWordSearches(now);
        assertEquals(KadSearchBridge.MAX_WORD_SEARCHES_PER_MINUTE, bridge.getWordSearches(), "a minute's share");
        assertEquals(KadSearchBridge.MAX_QUEUED_WORD_SEARCHES - KadSearchBridge.MAX_WORD_SEARCHES_PER_MINUTE,
                bridge.getQueuedWordSearches());
    }

    @Test
    void wordSearchesNeverUseUpTheReplaysOfTheClientSearches() throws Exception {
        start(); // isolated DHT: every search is over at once
        bridge.forkSearch(new SearchQuery.TermQuery("documentary rover", SearchQuery.ID_FILENAME));
        bridge.startQueuedWordSearches(System.currentTimeMillis());
        assertEquals(1, bridge.getWordSearches());

        for (int i = 0; i < KadSearchBridge.MAX_REPLAYS; i++) bridge.forkSearch("rover");
        assertEquals(1 + KadSearchBridge.MAX_REPLAYS, bridge.getSearchesForked(), "every client search sent");
        assertEquals(0, bridge.getSearchesSkippedAsRecent());

        // A word is searched in the background once per replay window
        bridge.forkSearch(new SearchQuery.TermQuery("rover documentary", SearchQuery.ID_FILENAME));
        bridge.startQueuedWordSearches(System.currentTimeMillis());
        assertEquals(1, bridge.getWordSearches());
    }

    @Test
    void fewWordSearchesRunAtTheSameTime() throws Exception {
        addContact();
        start(); // the nodes never answer: each search runs until its timeout
        bridge.forkSearch(new SearchQuery.TermQuery("documentary nature planet ocean", SearchQuery.ID_FILENAME));

        bridge.startQueuedWordSearches(System.currentTimeMillis());

        assertEquals(KadSearchBridge.MAX_CONCURRENT_WORD_SEARCHES, bridge.getWordSearches());
        assertEquals(3 - KadSearchBridge.MAX_CONCURRENT_WORD_SEARCHES, bridge.getQueuedWordSearches());
    }

    @Test
    void onlyTheClientSearchReadsFullNodesOnInTheBackground() throws Exception {
        addContact();
        // Endless indexes: every page comes back full
        start(ChildAbuseFilter.defaults(), (word, start) -> IntStream.range(start, start + KadSearchBridge.RESULTS_PER_PAGE)
                .mapToObj(i -> file(String.format("%016x%016x", word.hashCode() & 0xFFFFFFFFL, i), word + " " + i + ".avi"))
                .toList(), "reloaded", "trilogy");

        bridge.forkSearch(new SearchQuery.TermQuery("trilogy reloaded", SearchQuery.ID_FILENAME))
                .get(5, TimeUnit.SECONDS);
        runWordSearchesUntil(() -> bridge.getWordSearches() == 1 && bridge.getActiveSessionCount() == 0);
        Thread.sleep(200); // a follow is registered once the session's results are out

        // Both words read their pages while their search runs
        assertEquals(KadSearchBridge.MAX_PAGES_PER_NODE, requests.get(KadId.fromKeyword("reloaded")).size());
        assertEquals(KadSearchBridge.MAX_PAGES_PER_NODE, requests.get(KadId.fromKeyword("trilogy")).size());
        // Only the client's search goes on in the background
        assertEquals(1, bridge.getBackgroundNodesFollowed());
    }

    @Test
    void keywordsPutTheRequiredLongestWordFirstThenTheOthersLongestFirst() {
        SearchQuery query = new SearchQuery.AndQuery(
                new SearchQuery.TermQuery("the Matrix ab", SearchQuery.ID_FILENAME),
                new SearchQuery.OrQuery(
                        new SearchQuery.TermQuery("documentaries", SearchQuery.ID_FILENAME),
                        new SearchQuery.TermQuery("films the", SearchQuery.ID_FILENAME)));
        // "ab" is too short for Kad; "the" comes once
        assertEquals(List.of("matrix", "documentaries", "films", "the"), KadSearchTerms.keywords(query));
        assertEquals(List.of(), KadSearchTerms.keywords(
                new SearchQuery.NumericQuery(SearchQuery.ID_FILESIZE, SearchQuery.OP_GREATER, 500)));
    }
}
