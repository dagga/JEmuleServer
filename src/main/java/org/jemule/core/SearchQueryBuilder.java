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
        return new SearchQuery.TermQuery(term, id);
    }

    public SearchQuery size(long value, byte mode) {
        if (value < 0) {
            throw new IllegalArgumentException("File size cannot be negative");
        }
        if (mode != SearchQuery.MODE_MIN && mode != SearchQuery.MODE_MAX) {
            throw new IllegalArgumentException("Invalid size filter mode: " + mode);
        }
        return new SearchQuery.SizeQuery(value, mode);
    }
}
