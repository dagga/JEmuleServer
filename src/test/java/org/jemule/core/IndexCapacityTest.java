package org.jemule.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** maxFiles and the heap guard stop the new files of every channel; known files still get their sources. */
class IndexCapacityTest {

    private static FileMetadata file(char c, String name) {
        return new FileMetadata(String.valueOf(c).repeat(32), name, 100, "Video");
    }

    @Test
    void maxFilesStopsTheSearchChannelsToo() {
        FileIndex index = new FileIndex(null);
        index.setCapacity(() -> 2, () -> false);

        List<FileMetadata> stored = index.addFiles(List.of(file('a', "one.avi"), file('b', "two.avi"),
                file('c', "three.avi")), FileSource.KAD_SEARCH);

        assertEquals(2, stored.size());
        assertEquals(2, index.fileCount());
        assertTrue(index.isFull());
        assertNull(index.addFile(file('d', "four.avi"), FileSource.SERVER_SEARCH));
        assertEquals(2, index.refusedFullCount());
        // A known file is still updated
        assertNotNull(index.addFile(file('a', "one.avi"), FileSource.CLIENT_PUSH));
    }

    @Test
    void aFullHeapStopsNewFilesUntilACollectionFreesIt() {
        FileIndex index = new FileIndex(null);
        AtomicBoolean heapFull = new AtomicBoolean(true);
        index.setCapacity(() -> Integer.MAX_VALUE, heapFull::get);

        assertNull(index.addFile(file('a', "one.avi"), FileSource.KAD_SEARCH));
        heapFull.set(false);
        assertNotNull(index.addFile(file('a', "one.avi"), FileSource.KAD_SEARCH));
        assertEquals(1, index.fileCount());
    }

    @Test
    void theGuardComparesTheLiveDataAfterACollectionWithTheOldGeneration() {
        AtomicLong live = new AtomicLong();
        HeapGuard guard = new HeapGuard(live::get, () -> 1000);
        live.set(799);
        assertFalse(guard.full());
        live.set(800);
        assertTrue(guard.full());
        assertFalse(new HeapGuard(() -> 900, () -> 0).full(), "an unknown maximum never blocks");
    }

    @Test
    void theJvmGuardReadsThisJvm() {
        HeapGuard guard = HeapGuard.jvm();
        assertTrue(guard.maxBytes() >= 0);
        assertTrue(guard.liveBytes() >= 0);
    }
}
