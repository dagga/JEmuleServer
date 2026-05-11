package org.jemule.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SearchQueryBuilderTest {

    private final SearchQueryBuilder builder = new SearchQueryBuilder();

    @Test
    void testTermBuilder() {
        SearchQuery query = builder.term("test", SearchQuery.ID_FILENAME);
        assertTrue(query instanceof SearchQuery.TermQuery);
        
        FileMetadata meta = new FileMetadata("HASH", "test_file.txt", 100, "Text", java.util.List.of());
        assertTrue(query.test(meta));
    }

    @Test
    void testTermBuilderValidation() {
        assertThrows(NullPointerException.class, () -> builder.term(null, SearchQuery.ID_FILENAME));
        assertThrows(IllegalArgumentException.class, () -> builder.term("", SearchQuery.ID_FILENAME));
        assertThrows(IllegalArgumentException.class, () -> builder.term("  ", SearchQuery.ID_FILENAME));
    }

    @Test
    void testSizeBuilder() {
        SearchQuery query = builder.size(1000, SearchQuery.MODE_MIN);
        assertTrue(query instanceof SearchQuery.SizeQuery);
        
        FileMetadata meta = new FileMetadata("HASH", "file.txt", 2000, "Text", java.util.List.of());
        assertTrue(query.test(meta));
    }

    @Test
    void testSizeBuilderValidation() {
        assertThrows(IllegalArgumentException.class, () -> builder.size(-1, SearchQuery.MODE_MIN));
        assertThrows(IllegalArgumentException.class, () -> builder.size(100, (byte) 0xFF));
    }

    @Test
    void testCompositeBuilders() {
        SearchQuery left = builder.term("A", SearchQuery.ID_FILENAME);
        SearchQuery right = builder.term("B", SearchQuery.ID_FILENAME);
        
        SearchQuery andQuery = builder.and(left, right);
        assertTrue(andQuery instanceof SearchQuery.AndQuery);
        
        SearchQuery orQuery = builder.or(left, right);
        assertTrue(orQuery instanceof SearchQuery.OrQuery);
        
        SearchQuery notQuery = builder.not(left, right);
        assertTrue(notQuery instanceof SearchQuery.NotQuery);
    }

    @Test
    void testCompositeBuilderValidation() {
        SearchQuery query = builder.term("A", SearchQuery.ID_FILENAME);
        assertThrows(NullPointerException.class, () -> builder.and(null, query));
        assertThrows(NullPointerException.class, () -> builder.and(query, null));
    }
}
