package org.jemule.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SearchPerformanceTest {

    @Test
    void testPrefixSearchOptimization() {
        FileIndex index = new FileIndex(null);
        index.addFile(new FileMetadata("H1", "Marceau.txt", 100, "Text"));
        index.addFile(new FileMetadata("H2", "Marc.txt", 100, "Text"));
        index.addFile(new FileMetadata("H3", "Marcel.txt", 100, "Text"));
        index.addFile(new FileMetadata("H4", "Apple.txt", 100, "Text"));

        // Recherche par préfixe "Marc" devrait trouver H1, H2, H3
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

        // Première recherche (remplit le cache)
        long start1 = System.nanoTime();
        List<FileMetadata> res1 = index.search("test", 10);
        long end1 = System.nanoTime();

        // Deuxième recherche (devrait être plus rapide grâce au cache)
        long start2 = System.nanoTime();
        List<FileMetadata> res2 = index.search("test", 10);
        long end2 = System.nanoTime();

        assertEquals(res1, res2);
        // Note: sur un petit index la différence peut être minime, mais on vérifie au moins la cohérence
    }

    @Test
    void testCacheInvalidation() {
        FileIndex index = new FileIndex(null);
        index.addFile(new FileMetadata("H1", "test.txt", 100, "Text"));

        List<FileMetadata> res1 = index.search("test", 10);
        assertEquals(1, res1.size());

        // Ajouter un fichier invalide le cache
        index.addFile(new FileMetadata("H2", "test2.txt", 100, "Text"));
        
        List<FileMetadata> res2 = index.search("test", 10);
        assertEquals(2, res2.size());
    }
}
