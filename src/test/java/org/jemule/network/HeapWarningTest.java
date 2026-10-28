package org.jemule.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HeapWarningTest {
    private static final long MB = 1024 * 1024;

    @Test
    void warnsWhenTheDefaultHeapOfASmallServerIsTooSmallForItsFiles() {
        // A quarter of 947 MB of RAM, a third of it young generation: 158 MB of old generation
        String warning = Server.heapWarning(237 * MB, 158 * MB, 2_000_000);
        assertNotNull(warning);
        assertTrue(warning.contains("-Xmx"), warning);
        assertTrue(warning.contains("beyond about 1656750 files"), warning);
        assertNull(Server.heapWarning(237 * MB, 158 * MB, 1_000_000), "the Lucene index keeps the files on disk");
    }

    @Test
    void acceptsTheProductionHeap() {
        // -Xmx600m -Xmn48m: 552 MB of old generation, 365 MB live for 1,521,140 files with 1.2.24
        assertNull(Server.heapWarning(600 * MB, 552 * MB, 1_521_140), "the reference server");
        assertNull(Server.heapWarning(600 * MB, 552 * MB, 2_400_000), "the new reference server's files");
        assertNotNull(Server.heapWarning(600 * MB, 552 * MB, 6_000_000), "beyond what the heap guard lets in");
        assertNull(Server.heapWarning(710 * MB, 473 * MB, 262_902), "75% of a 1 GB server");
        assertNotNull(Server.heapWarning(237 * MB, 0, 2_500_000), "old generation unknown: the whole heap counts");
    }
}
