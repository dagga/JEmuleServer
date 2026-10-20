/*
 * JEmuleServer - An eMule server.
 * Copyright (C) 2026 Nicolas Hernandez (hernicatgmail.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.jemule.kad;

import org.jemule.core.SearchQuery;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KadSearchTermsTest {

    private static SearchQuery word(String w) {
        return new SearchQuery.TermQuery(w, SearchQuery.ID_FILENAME);
    }

    private static SearchQuery and(SearchQuery l, SearchQuery r) {
        return new SearchQuery.AndQuery(l, r);
    }

    @Test
    void splitsWordsLikeEmuleKadIndexing() {
        // INV_KAD_KEYWORD_CHARS separates words, an apostrophe does not; words under 3 bytes are not indexed
        assertEquals(List.of("don't", "stop", "the", "music"), KadSearchTerms.words("Don't.Stop (the) MUSIC - a"));
        assertEquals(List.of("été"), KadSearchTerms.words("été"), "3 characters are 5 UTF-8 bytes");
        assertEquals(List.of(), KadSearchTerms.words("ab cd"));
    }

    @Test
    void choosesTheLongestRequiredWordAsKeyword() {
        assertEquals("reloaded", KadSearchTerms.keyword(and(word("the matrix"), word("reloaded"))));
        // A word only in the right side of NOT, or in an OR, is not required
        assertEquals("matrix", KadSearchTerms.keyword(new SearchQuery.NotQuery(word("matrix"), word("revolutions"))));
        assertEquals("matrix", KadSearchTerms.keyword(and(word("matrix"),
                new SearchQuery.OrQuery(word("reloaded"), word("revolutions")))));
        // Without any required word, the first indexable word is used
        assertEquals("debian", KadSearchTerms.keyword(new SearchQuery.OrQuery(
                new SearchQuery.NumericQuery(SearchQuery.ID_FILESIZE, SearchQuery.OP_GREATER, 500), word("debian"))));
        assertNull(KadSearchTerms.keyword(word("ab")));
    }

    @Test
    void sendsNoExpressionWhenTheKeywordAloneIsSearched() {
        assertNull(KadSearchTerms.encode(word("Linux"), "linux"));
    }

    @Test
    void encodesTheQueryInTheSearchTreeFormatReadByKadNodes() {
        SearchQuery query = and(word("linux"), new SearchQuery.TermQuery("Pro", SearchQuery.ID_FILETYPE));
        assertEquals("0000" + "010500" + "6c696e7578" + "020300" + "50726f" + "0100" + "03",
                HexFormat.of().formatHex(KadSearchTerms.encode(query, "linux")));

        // Sizes beyond 4 GB use the 64-bit numeric form
        byte[] large = KadSearchTerms.encode(and(word("linux"),
                new SearchQuery.NumericQuery(SearchQuery.ID_FILESIZE, SearchQuery.OP_GREATER, 5_000_000_000L)), "linux");
        ByteBuffer tail = ByteBuffer.wrap(large, 10, large.length - 10).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x08, tail.get());
        assertEquals(5_000_000_000L, tail.getLong());
        assertEquals(SearchQuery.OP_GREATER, tail.get());
    }

    @Test
    void widensWhatCannotBeEncoded() {
        SearchQuery unknown = meta -> true; // a filter with a string tag name, not filtered on
        assertArrayEquals(KadSearchTerms.encode(and(word("linux"), word("debian")), "linux"),
                KadSearchTerms.encode(and(and(word("linux"), unknown), word("debian")), "linux"));
        assertNull(KadSearchTerms.encode(new SearchQuery.OrQuery(word("linux"), unknown), "linux"));

        // Nodes refuse an expression nested 24 levels deep: the search is then sent without one
        SearchQuery deep = word("linux");
        for (int i = 0; i < 30; i++) deep = and(deep, word("w" + i));
        assertNull(KadSearchTerms.encode(deep, "linux"));
    }
}
