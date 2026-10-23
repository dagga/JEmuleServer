package org.jemule.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OfferPacerTest {
    private static final long MS = 1_000_000L;

    @Test
    void firstBatchIsFreeThenTheNextWaitsOneInterval() {
        OfferPacer pacer = new OfferPacer(200, 500 * MS);
        assertEquals(0, pacer.reserve(200, 0));
        assertEquals(500 * MS, pacer.reserve(200, 0));
        assertEquals(1000 * MS, pacer.reserve(200, 0), "batches queue behind each other");
    }

    @Test
    void aClientSendingOneBatchPerIntervalNeverWaits() {
        OfferPacer pacer = new OfferPacer(200, 500 * MS);
        for (int i = 0; i < 100; i++) {
            assertEquals(0, pacer.reserve(200, i * 500 * MS), "batch " + i);
        }
    }

    @Test
    void smallerBatchesCostProportionallyAndSplittingGainsNothing() {
        OfferPacer pacer = new OfferPacer(200, 500 * MS);
        assertEquals(0, pacer.reserve(100, 0));
        assertEquals(0, pacer.reserve(100, 0));
        assertEquals(250 * MS, pacer.reserve(100, 0));
        assertEquals(500 * MS, pacer.reserve(100, 0));
    }

    @Test
    void idleTimeIsNotBankedBeyondOneBatch() {
        OfferPacer pacer = new OfferPacer(200, 500 * MS);
        assertEquals(0, pacer.reserve(200, 0));
        long later = 60_000 * MS;
        assertEquals(0, pacer.reserve(200, later));
        assertEquals(500 * MS, pacer.reserve(200, later));
    }

    @Test
    void largestBatchAndIntervalDoNotOverflow() {
        OfferPacer pacer = new OfferPacer(100_000, 3_600_000 * MS);
        assertEquals(0, pacer.reserve(100_000, 0));
        assertEquals(3_600_000 * MS, pacer.reserve(100_000, 0));
    }

    @Test
    void negativeNanoTimeIsHandled() {
        OfferPacer pacer = new OfferPacer(10, 1000 * MS);
        long start = Long.MIN_VALUE / 2;
        assertEquals(0, pacer.reserve(10, start));
        assertEquals(1000 * MS, pacer.reserve(10, start));
    }
}
