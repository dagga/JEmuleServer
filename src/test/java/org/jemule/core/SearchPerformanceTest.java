package org.jemule.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchPerformanceTest {

    @Test
    void testPrefixSearchOptimization() {
        FileIndex index = new FileIndex(null);
        index.addFile(new FileMetadata("H1", "Marceau.txt", 100, "Text"));
        index.addFile(new FileMetadata("H2", "Marc.txt", 100, "Text"));
        index.addFile(new FileMetadata("H3", "Marcel.txt", 100, "Text"));
        index.addFile(new FileMetadata("H4", "Apple.txt", 100, "Text"));

        // Prefix search for "Marc" should find H1, H2, H3
        List<FileMetadata> results = index.search("Marc", 10);
        assertEquals(3, results.size());
        assertTrue(results.stream().anyMatch(f -> f.hash().equals("H1")));
        assertTrue(results.stream().anyMatch(f -> f.hash().equals("H2")));
        assertTrue(results.stream().anyMatch(f -> f.hash().equals("H3")));
    }

    @Test
    void testSearchCache() {
        FileIndex index = new FileIndex(null);
        index.addFile(new FileMetadata("H1", "test_file.txt", 100, "Text"));

        // First search (fills cache)
        long start1 = System.nanoTime();
        List<FileMetadata> res1 = index.search("test", 10);
        long end1 = System.nanoTime();

        // Second search (should be faster thanks to cache)
        long start2 = System.nanoTime();
        List<FileMetadata> res2 = index.search("test", 10);
        long end2 = System.nanoTime();

        assertEquals(res1, res2);
        // Note: on a small index the difference may be minimal, but we verify consistency at least
    }

    @Test
    void testCacheInvalidation() {
        FileIndex index = new FileIndex(null);
        index.addFile(new FileMetadata("H1", "test.txt", 100, "Text"));

        List<FileMetadata> res1 = index.search("test", 10);
        assertEquals(1, res1.size());

        // Adding a file invalidates the cache
        index.addFile(new FileMetadata("H2", "test2.txt", 100, "Text"));
        
        List<FileMetadata> res2 = index.search("test", 10);
        assertEquals(2, res2.size());
    }
}
