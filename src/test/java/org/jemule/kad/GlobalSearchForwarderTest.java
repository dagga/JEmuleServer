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
import org.jemule.security.ChildAbuseFilter;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.jemule.kad.GlobalSearchForwarder.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GlobalSearchForwarderTest {

    private final KadSearchBridge kad = mock(KadSearchBridge.class);
    private final GlobalSearchForwarder forwarder = new GlobalSearchForwarder(() -> kad, ChildAbuseFilter.defaults(), true);

    private static SearchQuery query(String words) {
        return new SearchQuery.TermQuery(words, SearchQuery.ID_FILENAME);
    }

    private static InetAddress ip(int last) throws Exception {
        return InetAddress.getByAddress(new byte[]{(byte) 198, 51, 100, (byte) last});
    }

    @Test
    void searchesTheQueryOnKadOncePerHalfHour() throws Exception {
        long t = 1_000_000;
        assertEquals(FORWARDED, forwarder.forward(query("shemale housewife"), ip(1), t));
        verify(kad).forkSearch(query("shemale housewife"));

        assertEquals(DUPLICATE, forwarder.forward(query("Shemale Housewife"), ip(2), t + 60_000),
                "the same query from another client is not searched again");
        assertEquals(FORWARDED, forwarder.forward(query("shemale housewife"), ip(2),
                t + GlobalSearchForwarder.DUPLICATE_WINDOW_MS));
        verify(kad, times(2)).forkSearch(any(SearchQuery.class));
        assertEquals(3, forwarder.getReceived());
        assertEquals(2, forwarder.getForwarded());
        assertEquals(1, forwarder.getSkipped());
    }

    @Test
    void limitsTheSearchesPerSourceAndPerMinute() throws Exception {
        long t = 1_000_000;
        for (int i = 0; i < GlobalSearchForwarder.MAX_PER_SOURCE_PER_MINUTE; i++) {
            assertEquals(FORWARDED, forwarder.forward(query("movie number " + i), ip(1), t));
        }
        assertEquals(RATE_LIMITED, forwarder.forward(query("another movie"), ip(1), t));
        assertEquals(FORWARDED, forwarder.forward(query("another movie"), ip(2), t),
                "a refused query is not remembered as searched");

        int sent = GlobalSearchForwarder.MAX_PER_SOURCE_PER_MINUTE + 1;
        for (int i = 0; sent < GlobalSearchForwarder.MAX_PER_MINUTE; i++, sent++) {
            assertEquals(FORWARDED, forwarder.forward(query("series episode " + i), ip(10 + i), t));
        }
        assertEquals(RATE_LIMITED, forwarder.forward(query("one too many"), ip(200), t));
        assertEquals(FORWARDED, forwarder.forward(query("one too many"), ip(200), t + 60_000));
        assertEquals(2, forwarder.getRefused());
    }

    @Test
    void neverSearchesChildAbuseQueries() throws Exception {
        assertEquals(CHILD_ABUSE, forwarder.forward(query("pthc"), ip(1), 0));
        assertEquals(CHILD_ABUSE, forwarder.forward(new SearchQuery.AndQuery(query("video"), query("pthc")), ip(1), 0));
        verify(kad, never()).forkSearch(any(SearchQuery.class));
        assertEquals(2, forwarder.getRefused());
    }

    @Test
    void skipsWhenKadIsOffOrNoKeywordCanBeSearched() throws Exception {
        assertEquals(NO_KEYWORD, forwarder.forward(query("ab"), ip(1), 0), "Kad indexes words of 3 bytes or more");
        assertEquals(DISABLED, new GlobalSearchForwarder(() -> null, null, true).forward(query("linux"), ip(1), 0));
        assertEquals(DISABLED, new GlobalSearchForwarder(() -> kad, null, false).forward(query("linux"), ip(1), 0));
        verify(kad, never()).forkSearch(any(SearchQuery.class));
    }
}
