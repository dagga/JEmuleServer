package org.jemule.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HeapWarningTest {
    private static final long MB = 1024 * 1024;

    @Test
    void warnsWhenTheDefaultHeapOfASmallServerIsTooSmallForItsFiles() {
        // Production on 2026-10-20: a quarter of 947 MB of RAM for 262,902 files ended in an OutOfMemoryError
        String warning = Server.heapWarning(237 * MB, 262_902);
        assertNotNull(warning);
        assertTrue(warning.contains("-XX:MaxRAMPercentage=75"), warning);
    }

    @Test
    void acceptsTheBenchmarkedHeap() {
        assertNull(Server.heapWarning(768 * MB, 1_000_000), "1,000,000 files run in a 768 MB heap");
        assertNull(Server.heapWarning(520 * MB, 1_000_000), "the reference server: -Xmx520m for maxFiles=1000000");
        assertNotNull(Server.heapWarning(480 * MB, 1_000_000), "too tight for 1,000,000 files");
        assertNull(Server.heapWarning(710 * MB, 262_902), "75% of a 1 GB server");
    }
}
