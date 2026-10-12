package org.jemule.core;

import java.util.Objects;

/**
 * Builder for SearchQuery to ensure readable construction and validation.
 */
public class SearchQueryBuilder {

    public SearchQuery and(SearchQuery left, SearchQuery right) {
        Objects.requireNonNull(left, "Left query cannot be null");
        Objects.requireNonNull(right, "Right query cannot be null");
        return new SearchQuery.AndQuery(left, right);
    }

    public SearchQuery or(SearchQuery left, SearchQuery right) {
        Objects.requireNonNull(left, "Left query cannot be null");
        Objects.requireNonNull(right, "Right query cannot be null");
        return new SearchQuery.OrQuery(left, right);
    }

    public SearchQuery not(SearchQuery left, SearchQuery right) {
        Objects.requireNonNull(left, "Left query cannot be null");
        Objects.requireNonNull(right, "Right query cannot be null");
        return new SearchQuery.NotQuery(left, right);
    }

    public SearchQuery term(String term, byte id) {
        Objects.requireNonNull(term, "Search term cannot be null");
        if (term.trim().isEmpty()) {
            throw new IllegalArgumentException("Search term cannot be empty or whitespace-only");
        }
        return new SearchQuery.TermQuery(term, id);
    }

    public SearchQuery size(long value, byte operator) {
        return numeric(SearchQuery.ID_FILESIZE, operator, value);
    }

    public SearchQuery numeric(byte tagId, byte operator, long value) {
        if (value < 0) {
            throw new IllegalArgumentException("Numeric search value cannot be negative");
        }
        if (operator < SearchQuery.OP_EQUAL || operator > SearchQuery.OP_NOTEQUAL) {
            throw new IllegalArgumentException("Invalid comparison operator: " + operator);
        }
        return new SearchQuery.NumericQuery(tagId, operator, value);
    }
}
