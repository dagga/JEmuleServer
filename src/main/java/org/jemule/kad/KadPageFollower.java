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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Asks the Kad nodes for the pages of a keyword index past those of the search, in the background. A search reads at
 * most {@link KadSearchBridge#MAX_PAGES_PER_NODE} pages of 300 from each node, within the time the client waits; a
 * node whose last page was full holds more, up to tens of thousands of entries. Its next pages are requested one
 * every {@link #PAGE_INTERVAL_MS}, under the 3 KADEMLIA2_SEARCH_KEY_REQ a minute a node accepts from one IP, while
 * they come back full, until the search has kept {@link #MAX_RESULTS_PER_SEARCH} results from all its nodes together:
 * files with at least the sources required and clean, the others do not count.
 * Their files only go to the index: the client has had its answer.
 */
final class KadPageFollower {
    /** Results kept by one search from all its nodes, the pages read while the client waited included. */
    static final int MAX_RESULTS_PER_SEARCH = 60_000;
    /** Time between two page requests to one node. */
    static final long PAGE_INTERVAL_MS = 25_000;
    /** A page not answered within this time ends the following of that node. */
    static final long ANSWER_TIMEOUT_MS = 30_000;
    /** Nodes followed at once at most; the oldest are dropped first. */
    static final int MAX_FOLLOWED = 300;

    /** Sends a KADEMLIA2_SEARCH_KEY_REQ; throws when the flood guard keeps it. */
    interface Sender {
        void send(KadId target, int startPosition, byte[] searchTerms, InetSocketAddress node) throws IOException;
    }

    /** A search: its keyword and its whole expression (restrictive search). */
    private record SearchKey(KadId target, String terms) {
        static SearchKey of(KadId target, byte[] searchTerms) {
            return new SearchKey(target, searchTerms == null ? "" : java.util.HexFormat.of().formatHex(searchTerms));
        }
    }

    private record Key(KadId target, InetSocketAddress node) {}

    /** Results kept by a search from all its nodes. */
    private static final class Total {
        int kept;
    }

    private static final class Follow {
        final byte[] searchTerms;
        final SearchKey search;
        final Total total;
        int nextStart;
        long nextAt;
        /** When the page in progress was requested; 0 while waiting to request it. */
        long sentAt;

        Follow(byte[] searchTerms, SearchKey search, Total total, int nextStart, long nextAt) {
            this.searchTerms = searchTerms;
            this.search = search;
            this.total = total;
            this.nextStart = nextStart;
            this.nextAt = nextAt;
        }
    }

    private final Sender sender;
    private final Map<Key, Follow> follows = new LinkedHashMap<>();
    private final Map<SearchKey, Total> totals = new java.util.HashMap<>();
    private final AtomicLong nodesFollowed = new AtomicLong();
    private final AtomicLong pagesRequested = new AtomicLong();
    private final AtomicLong pagesAnswered = new AtomicLong();

    KadPageFollower(Sender sender) {
        this.sender = sender;
    }

    /**
     * Follows a node whose last page of the search was full.
     *
     * @param nextStart the start position of the next page
     * @param kept      the results the search already kept from all its nodes
     */
    synchronized void follow(KadId target, byte[] searchTerms, InetSocketAddress node, int nextStart, int kept,
                             long now) {
        if (nextStart > KadPacket.MAX_START_POSITION || kept >= MAX_RESULTS_PER_SEARCH) return;
        Key key = new Key(target, node);
        if (follows.containsKey(key)) return;
        if (follows.size() >= MAX_FOLLOWED) {
            Iterator<Map.Entry<Key, Follow>> oldest = follows.entrySet().iterator();
            Follow dropped = oldest.next().getValue();
            oldest.remove();
            forgetIfDone(dropped.search);
        }
        SearchKey search = SearchKey.of(target, searchTerms);
        Total total = totals.computeIfAbsent(search, ignored -> new Total());
        total.kept = Math.max(total.kept, kept);
        follows.put(key, new Follow(searchTerms, search, total, nextStart, now + PAGE_INTERVAL_MS));
        nodesFollowed.incrementAndGet();
    }

    private void forgetIfDone(SearchKey search) {
        if (follows.values().stream().noneMatch(f -> f.search.equals(search))) totals.remove(search);
    }

    /** Requests the pages due, and drops the nodes that did not answer. */
    void tick(long now) {
        List<Map.Entry<Key, Follow>> due = new ArrayList<>();
        synchronized (this) {
            List<SearchKey> touched = new ArrayList<>();
            follows.values().removeIf(f -> {
                boolean silent = f.sentAt > 0 && now - f.sentAt > ANSWER_TIMEOUT_MS;
                if (silent) touched.add(f.search);
                return silent;
            });
            touched.forEach(this::forgetIfDone);
            for (Map.Entry<Key, Follow> entry : follows.entrySet()) {
                Follow f = entry.getValue();
                if (f.sentAt == 0 && now >= f.nextAt) {
                    f.sentAt = now;
                    due.add(entry);
                }
            }
        }
        for (Map.Entry<Key, Follow> entry : due) {
            Follow f = entry.getValue();
            try {
                sender.send(entry.getKey().target(), f.nextStart, f.searchTerms, entry.getKey().node());
                pagesRequested.incrementAndGet();
            } catch (IOException e) {
                // The flood guard kept it (a search used the node's budget), or the socket failed: later
                synchronized (this) {
                    f.sentAt = 0;
                    f.nextAt = now + PAGE_INTERVAL_MS;
                }
            }
        }
    }

    /** True when the answer of this node is a page this follower asked for: its files go to the index. */
    synchronized boolean expects(KadId target, InetSocketAddress node) {
        Follow f = follows.get(new Key(target, node));
        return f != null && f.sentAt != 0;
    }

    /**
     * Takes the answer of a followed node.
     *
     * @param entries the entries of the page, which tell whether it was full
     * @param kept    the files of the page kept for the index, which count towards the search's limit
     * @return true if the answer is a page this follower asked for
     */
    synchronized boolean accept(KadId target, InetSocketAddress node, int entries, int kept, int fullPage, long now) {
        Key key = new Key(target, node);
        Follow f = follows.get(key);
        if (f == null || f.sentAt == 0) return false;
        pagesAnswered.incrementAndGet();
        f.total.kept += kept;
        int next = f.nextStart + KadSearchBridge.RESULTS_PER_PAGE;
        if (f.total.kept >= MAX_RESULTS_PER_SEARCH) {
            // The search has its results: none of its nodes is asked again
            follows.values().removeIf(other -> other.search.equals(f.search));
            totals.remove(f.search);
        } else if (entries < fullPage || next > KadPacket.MAX_START_POSITION) {
            follows.remove(key);
            forgetIfDone(f.search);
        } else {
            f.nextStart = next;
            f.sentAt = 0;
            f.nextAt = now + PAGE_INTERVAL_MS;
        }
        return true;
    }

    /** Stops following every node (the index is full). */
    synchronized void clear() {
        follows.clear();
        totals.clear();
    }

    synchronized int followed() {
        return follows.size();
    }

    long nodesFollowed() {
        return nodesFollowed.get();
    }

    long pagesRequested() {
        return pagesRequested.get();
    }

    long pagesAnswered() {
        return pagesAnswered.get();
    }
}
