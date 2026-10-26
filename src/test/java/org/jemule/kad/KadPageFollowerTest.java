package org.jemule.kad;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KadPageFollowerTest {
    private static final KadId TARGET = KadId.fromKeyword("concert");
    private static final int FULL = 270;

    private final List<Integer> sent = new ArrayList<>();
    private boolean refuse;
    private final KadPageFollower follower = new KadPageFollower((target, start, terms, node) -> {
        if (refuse) throw new IOException("flood guard");
        sent.add(start);
    });

    private static InetSocketAddress node(int n) throws Exception {
        return new InetSocketAddress(InetAddress.getByName("192.0.2." + n), 4672);
    }

    @Test
    void theNextPagesAreReadOneEveryIntervalWhileFull() throws Exception {
        long now = 0;
        follower.follow(TARGET, null, node(1), 900, 2700, now);
        follower.tick(now + KadPageFollower.PAGE_INTERVAL_MS - 1);
        assertTrue(sent.isEmpty(), "the first page waits an interval: the search used the node's budget");

        for (int page = 0; page < 5; page++) {
            now += KadPageFollower.PAGE_INTERVAL_MS;
            follower.tick(now);
            follower.accept(TARGET, node(1), 300, 300, FULL, now);
        }
        assertEquals(List.of(900, 1200, 1500, 1800, 2100), sent, "one page an interval, while they come back full");
        assertEquals(1, follower.followed());
    }

    @Test
    void theSearchStopsAtSixtyThousandResultsFromAllItsNodes() throws Exception {
        long now = 0;
        // Two nodes of the same search; the search already has 59,000 results
        follower.follow(TARGET, null, node(5), 900, 59_000, now);
        follower.follow(TARGET, null, node(6), 900, 59_000, now);
        for (int round = 0; round < 3; round++) {
            now += KadPageFollower.PAGE_INTERVAL_MS;
            follower.tick(now);
            follower.accept(TARGET, node(5), 300, 300, FULL, now);
            follower.accept(TARGET, node(6), 300, 300, FULL, now);
        }
        assertEquals(0, follower.followed(), "59,000 + 4 x 300 kept > 60,000: no node is asked again");
        assertEquals(4, sent.size(), "2 rounds of 2 pages");
        follower.follow(TARGET, null, node(7), 900, KadPageFollower.MAX_RESULTS_PER_SEARCH, now);
        assertEquals(0, follower.followed(), "a search already at 60,000 is not followed");
    }

    @Test
    void onlyTheResultsKeptCountTowardsTheLimit() throws Exception {
        long now = 0;
        follower.follow(TARGET, null, node(9), 900, 59_000, now);
        for (int round = 0; round < 5; round++) {
            now += KadPageFollower.PAGE_INTERVAL_MS;
            follower.tick(now);
            // Full pages, but few files with a source: the search goes on
            follower.accept(TARGET, node(9), 300, 100, FULL, now);
        }
        assertEquals(1, follower.followed(), "59,000 + 5 x 100 kept < 60,000");
        assertEquals(5, sent.size());
    }

    @Test
    void aNodeIsReadUpToTheLastStartPositionOfKad() throws Exception {
        follower.follow(TARGET, null, node(8), KadPacket.MAX_START_POSITION - 100, 0, 0);
        follower.tick(KadPageFollower.PAGE_INTERVAL_MS);
        follower.accept(TARGET, node(8), 300, 300, FULL, KadPageFollower.PAGE_INTERVAL_MS);
        assertEquals(0, follower.followed(), "no position past 32,767");
    }

    @Test
    void aPageThatIsNotFullEndsTheFollowing() throws Exception {
        follower.follow(TARGET, null, node(2), 900, 2700, 0);
        follower.tick(KadPageFollower.PAGE_INTERVAL_MS);
        assertTrue(follower.accept(TARGET, node(2), 120, 120, FULL, KadPageFollower.PAGE_INTERVAL_MS));
        follower.tick(10 * KadPageFollower.PAGE_INTERVAL_MS);
        assertEquals(List.of(900), sent);
        assertFalse(follower.accept(TARGET, node(2), 300, 300, FULL, 0), "no longer followed: an answer is not accepted");
    }

    @Test
    void unaskedAnswersAreRefusedAndSilentNodesDropped() throws Exception {
        assertFalse(follower.accept(TARGET, node(3), 300, 300, FULL, 0), "never followed");
        follower.follow(TARGET, null, node(3), 900, 2700, 0);
        assertFalse(follower.accept(TARGET, node(3), 300, 300, FULL, 1), "followed, but no page asked yet");
        follower.tick(KadPageFollower.PAGE_INTERVAL_MS);
        follower.tick(KadPageFollower.PAGE_INTERVAL_MS + KadPageFollower.ANSWER_TIMEOUT_MS + 1);
        assertEquals(0, follower.followed(), "no answer: dropped");
    }

    @Test
    void aRefusedRequestIsTriedAgainLater() throws Exception {
        follower.follow(TARGET, null, node(4), 900, 2700, 0);
        refuse = true;
        follower.tick(KadPageFollower.PAGE_INTERVAL_MS);
        refuse = false;
        follower.tick(KadPageFollower.PAGE_INTERVAL_MS + 1);
        assertTrue(sent.isEmpty(), "not before the next interval");
        follower.tick(2 * KadPageFollower.PAGE_INTERVAL_MS);
        assertEquals(List.of(900), sent);
    }

    @Test
    void theSearchHandsOverTheNodesStoppedOnAFullPage() throws Exception {
        KadSearchBridge.KadSearchSession session = new KadSearchBridge.KadSearchSession(TARGET, "concert");
        InetAddress full = InetAddress.getByName("192.0.2.10");
        InetAddress partial = InetAddress.getByName("192.0.2.11");
        session.addQueried(full, 4672);
        session.addQueried(partial, 4672);
        for (int page = 1; page < KadSearchBridge.MAX_PAGES_PER_NODE; page++) {
            assertTrue(session.nextPage(full, 4672, 300) > 0);
        }
        assertEquals(-1, session.nextPage(full, 4672, 300), "the search's page limit");
        assertEquals(-1, session.nextPage(partial, 4672, 120));
        assertEquals(Map.of(new InetSocketAddress(full, 4672), 900), session.capped());
    }
}
