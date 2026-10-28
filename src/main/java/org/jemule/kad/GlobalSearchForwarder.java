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

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Searches on Kad, in the background, the UDP global searches (OP_GLOBSEARCHREQ) that clients of other servers send
 * to this server, so that the files they look for are ingested into the database. The requester only gets the local
 * answer: its UDP source address can be forged.
 * <p>
 * These searches only go to Kad, never to other eD2K servers: another server forwarding global searches the same way
 * could send them back, and the two would loop. A UDP request costs nothing to forge, so each search is limited:
 * an identical query is searched once per {@link #DUPLICATE_WINDOW_MS}, at most {@link #MAX_PER_MINUTE} searches per
 * minute in all and {@link #MAX_PER_SOURCE_PER_MINUTE} per source IP, and queries matching the child abuse filter are
 * never searched.
 */
public final class GlobalSearchForwarder {
    static final long DUPLICATE_WINDOW_MS = 30 * 60_000L;
    static final int MAX_PER_MINUTE = 20;
    static final int MAX_PER_SOURCE_PER_MINUTE = 3;
    /** Queries remembered to skip duplicates; the oldest are forgotten first. */
    private static final int MAX_REMEMBERED_QUERIES = 10_000;
    private static final long MINUTE_MS = 60_000L;

    /** What became of one global search. */
    public enum Outcome { FORWARDED, DISABLED, NO_KEYWORD, CHILD_ABUSE, DUPLICATE, RATE_LIMITED, FROM_SERVER }

    private final Supplier<KadSearchBridge> bridge;
    private final ChildAbuseFilter abuseFilter;
    private final boolean enabled;

    private final Map<String, Long> recentQueries = new LinkedHashMap<>();
    private final Deque<Long> forwardTimes = new ArrayDeque<>();
    private final Map<InetAddress, Deque<Long>> sourceTimes = new HashMap<>();

    private final AtomicLong received = new AtomicLong();
    private final AtomicLong forwarded = new AtomicLong();
    private final AtomicLong results = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();

    /**
     * @param bridge      the Kad search bridge, null while Kad is not running
     * @param abuseFilter filter of the queries that are never searched
     * @param enabled     false to only count the global searches
     */
    public GlobalSearchForwarder(Supplier<KadSearchBridge> bridge, ChildAbuseFilter abuseFilter, boolean enabled) {
        this.bridge = bridge;
        this.abuseFilter = abuseFilter;
        this.enabled = enabled;
    }

    /**
     * A global search another eD2K server sent for one of its clients: not searched on Kad, which that server did
     * itself. Counted among the received and skipped searches.
     */
    public Outcome fromServer() {
        received.incrementAndGet();
        return count(Outcome.FROM_SERVER);
    }

    public Outcome forward(SearchQuery query, InetAddress source) {
        return forward(query, source, System.currentTimeMillis());
    }

    Outcome forward(SearchQuery query, InetAddress source, long now) {
        received.incrementAndGet();
        KadSearchBridge kad = bridge.get();
        if (!enabled || kad == null) return count(Outcome.DISABLED);
        if (query == null || KadSearchTerms.keyword(query) == null) return count(Outcome.NO_KEYWORD);
        if (abuseFilter != null && abuseFilter.matches(SearchQuery.terms(query))) return count(Outcome.CHILD_ABUSE);

        String key = query.toString().toLowerCase(Locale.ROOT);
        synchronized (this) {
            recentQueries.values().removeIf(at -> now - at >= DUPLICATE_WINDOW_MS);
            if (recentQueries.containsKey(key)) return count(Outcome.DUPLICATE);
            forwardTimes.removeIf(at -> now - at >= MINUTE_MS);
            sourceTimes.values().forEach(times -> times.removeIf(at -> now - at >= MINUTE_MS));
            sourceTimes.values().removeIf(Deque::isEmpty);
            Deque<Long> fromSource = sourceTimes.computeIfAbsent(source, ignored -> new ArrayDeque<>());
            if (forwardTimes.size() >= MAX_PER_MINUTE || fromSource.size() >= MAX_PER_SOURCE_PER_MINUTE) {
                if (fromSource.isEmpty()) sourceTimes.remove(source);
                return count(Outcome.RATE_LIMITED);
            }
            forwardTimes.add(now);
            fromSource.add(now);
            recentQueries.put(key, now);
            if (recentQueries.size() > MAX_REMEMBERED_QUERIES) {
                recentQueries.remove(recentQueries.keySet().iterator().next());
            }
        }
        var found = kad.forkSearch(query); // the files found are ingested by the bridge
        if (found != null) {
            found.thenAccept(files -> results.addAndGet(files.stream().map(f -> f.hash().toUpperCase(Locale.ROOT))
                    .distinct().count()));
        }
        return count(Outcome.FORWARDED);
    }

    private Outcome count(Outcome outcome) {
        switch (outcome) {
            case FORWARDED -> forwarded.incrementAndGet();
            case CHILD_ABUSE, RATE_LIMITED -> refused.incrementAndGet();
            default -> skipped.incrementAndGet();
        }
        return outcome;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Global searches received. */
    public long getReceived() {
        return received.get();
    }

    /** Global searches started on Kad. */
    public long getForwarded() {
        return forwarded.get();
    }

    /** Distinct clean files found on Kad by the forwarded searches, all handed over to ingestion. */
    public long getResults() {
        return results.get();
    }

    /** Global searches not searched: Kad disabled, no indexable keyword, or searched within the last 30 minutes. */
    public long getSkipped() {
        return skipped.get();
    }

    /** Global searches refused: above the rate limits, or matching the child abuse filter. */
    public long getRefused() {
        return refused.get();
    }
}
