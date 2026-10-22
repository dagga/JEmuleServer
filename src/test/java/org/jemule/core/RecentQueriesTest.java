package org.jemule.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecentQueriesTest {

    @Test
    void aQueryStartsAtMostMaxStartsTimesWithinTheWindow() {
        RecentQueries recent = new RecentQueries(1000, 3, 100);
        assertTrue(recent.tryStart("ubuntu", 0));
        assertTrue(recent.tryStart("ubuntu", 10));
        assertTrue(recent.tryStart("ubuntu", 20));
        assertFalse(recent.tryStart("ubuntu", 30));
        assertTrue(recent.tryStart("debian", 30), "other queries are counted apart");
        assertEquals(1, recent.skipped());

        // The window runs from the first start: once over, the query starts afresh
        assertTrue(recent.tryStart("ubuntu", 1000));
        assertTrue(recent.tryStart("ubuntu", 1500));
        assertTrue(recent.tryStart("ubuntu", 1999));
        assertFalse(recent.tryStart("ubuntu", 1999));
    }

    @Test
    void theOldestQueriesAreForgottenBeyondTheLimit() {
        RecentQueries recent = new RecentQueries(1000, 1, 2);
        recent.tryStart("a", 0);
        recent.tryStart("b", 1);
        recent.tryStart("c", 2);
        assertEquals(2, recent.size());
        assertTrue(recent.tryStart("a", 3), "forgotten");
        assertFalse(recent.tryStart("c", 3));
    }

    @Test
    void aZeroWindowNeverSkips() {
        RecentQueries recent = new RecentQueries(0, 1, 10);
        assertTrue(recent.tryStart("x", 5));
        assertTrue(recent.tryStart("x", 5));
    }
}
