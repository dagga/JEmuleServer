package org.jemule.network;

import org.jemule.config.ServerConfig;
import org.jemule.core.ClientRegistry;
import org.jemule.core.FileIndex;
import org.jemule.core.FileMetadata;
import org.jemule.core.SearchExecutor;
import org.jemule.core.SearchQuery;
import org.jemule.core.ServerThreads.Tier;
import org.jemule.network.handler.PublishHandler;
import org.jemule.network.handler.SearchHandler;
import org.jemule.protocol.OpCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.jemule.network.EmuleTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The local index search of a client runs on the search threads: a long search no longer holds the carrier thread of
 * its client's virtual thread, which on a one-CPU server would hold back every other client.
 */
class SearchOffloadTest {

    /** An index whose searches burn the CPU until released, as a long Lucene search does. */
    static final class SlowIndex extends FileIndex {
        volatile boolean slow = true;
        final CountDownLatch searching;
        final Set<Thread> searchThreads = ConcurrentHashMap.newKeySet();

        SlowIndex(int searches) {
            super(null);
            searching = new CountDownLatch(searches);
        }

        @Override
        public List<FileMetadata> searchComplex(SearchQuery query, int limit) {
            searchThreads.add(Thread.currentThread());
            searching.countDown();
            while (slow) Thread.onSpinWait(); // never parks: on a virtual thread, holds its carrier
            return super.searchComplex(query, limit);
        }
    }

    /** Carrier threads of the virtual threads. */
    static final int CARRIERS = Integer.getInteger("jdk.virtualThreadScheduler.parallelism",
            Runtime.getRuntime().availableProcessors());

    SlowIndex index;
    SearchExecutor searches;
    final List<Thread> clients = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        index = new SlowIndex(CARRIERS);
        searches = SearchExecutor.create();
        index.slow = false;
        FakeContext publisher = loggedInContext("10.9.9.9", 50000, ServerConfig.DEFAULT, new ClientRegistry(), index);
        new PublishHandler().handlePublish(publisher, OpCode.OFFER_FILES, offerFiles(offeredFile(
                hash("11111111111111111111111111111111"), 0xFBFBFBFB, 0xFBFB,
                new NewTags().str(0x01, "holiday video.avi").num(0x02, 700_000_000L).num(0x03, 2))),
                new ByteArrayOutputStream());
        index.refresh();
        index.slow = true;
    }

    @AfterEach
    void tearDown() throws Exception {
        index.slow = false;
        for (Thread client : clients) client.join(10_000);
        searches.close();
        index.close();
    }

    @Test
    void aLongSearchDoesNotHoldBackTheOtherClients() throws Exception {
        assertTrue(searches.threads() >= CARRIERS, "every carrier could be taken by a search");
        // As many clients searching at once as there are carrier threads
        List<SearchAnswer> answers = new ArrayList<>();
        for (int i = 0; i < CARRIERS; i++) {
            FakeContext client = loggedInContext("10.1.2." + (i + 1), 50000, ServerConfig.DEFAULT,
                    new ClientRegistry(), index);
            client.setSearchExecutor(searches);
            clients.add(Thread.ofVirtual().name("client-" + i).start(() -> {
                try {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    new SearchHandler().handleSearch(client, new SearchTree().keyword("holiday").toBytes(), out);
                    Packet p = Packet.read(new ByteArrayInputStream(out.toByteArray()), 1 << 20);
                    synchronized (answers) {
                        answers.add(readSearchAnswer(p.data()));
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }));
        }
        assertTrue(index.searching.await(10, TimeUnit.SECONDS), "every search under way");

        // Another client's request (login, keep-alive, source request...) still gets a carrier thread
        CountDownLatch served = new CountDownLatch(1);
        Thread.ofVirtual().start(served::countDown);
        assertTrue(served.await(5, TimeUnit.SECONDS),
                "the searches hold every carrier thread: the other clients wait for them to end");

        // The clients wait parked, without holding their carriers, while the search threads search
        for (Thread search : index.searchThreads) {
            assertFalse(search.isVirtual(), search.getName());
            assertEquals(Tier.SEARCH.priority, search.getPriority());
        }
        for (Thread client : clients) assertTrue(client.isAlive(), "still waiting for its search");

        index.slow = false;
        for (Thread client : clients) client.join(10_000);
        assertEquals(CARRIERS, answers.size());
        for (SearchAnswer answer : answers) {
            assertEquals(1, answer.results().size(), "the same answer as a search in the client's thread");
        }
    }

    @Test
    void withoutSearchThreadsTheSearchRunsInTheClientThread() throws Exception {
        index.slow = false;
        FakeContext client = loggedInContext("10.1.2.3", 50000, ServerConfig.DEFAULT, new ClientRegistry(), index);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new SearchHandler().handleSearch(client, new SearchTree().keyword("holiday").toBytes(), out);
        assertEquals(Set.of(Thread.currentThread()), index.searchThreads);
        Packet p = Packet.read(new ByteArrayInputStream(out.toByteArray()), 1 << 20);
        assertEquals(1, readSearchAnswer(p.data()).results().size());
    }
}
