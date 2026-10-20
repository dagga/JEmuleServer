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

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * Keeps the requests of one opcode sent to each Kad node within the flood limit of eMule's CPacketTracking.
 * A node counts the requests it receives from an IP and forgets one every 60/limit seconds; above the limit it
 * drops them, and above five times the limit it bans the IP. The server is a single IP serving many clients, so
 * it replays that count for every node and does not send a request the node would drop.
 */
final class KadRequestBudget {
    private static final long CLEANUP_INTERVAL_MS = 60_000;

    private final int perMinute;
    /** Time after which the node forgets one request, with a margin for clock drift and delivery delays. */
    private final long forgetAfterMs;
    private final Map<InetAddress, Track> tracks = new HashMap<>();
    private long lastCleanup;

    private static final class Track {
        int count;
        long firstAdded;
    }

    KadRequestBudget(int perMinute) {
        if (perMinute < 1) throw new IllegalArgumentException("perMinute must be positive");
        this.perMinute = perMinute;
        this.forgetAfterMs = 60_000L / perMinute * 11 / 10;
    }

    boolean tryAcquire(InetAddress node) {
        return tryAcquire(node, System.currentTimeMillis());
    }

    /**
     * Records a request to {@code node} if the node would accept it.
     *
     * @return false if the request must not be sent
     */
    synchronized boolean tryAcquire(InetAddress node, long now) {
        if (node.isLoopbackAddress()) return true; // a node on this host (local tools, tests) is not a DHT peer
        if (now - lastCleanup > CLEANUP_INTERVAL_MS) {
            tracks.values().removeIf(t -> now - t.firstAdded > forgetAfterMs * t.count);
            lastCleanup = now;
        }
        Track track = tracks.computeIfAbsent(node, ignored -> {
            Track t = new Track();
            t.firstAdded = now;
            return t;
        });
        if (track.count > 0 && now - track.firstAdded > forgetAfterMs) {
            long forgotten = (now - track.firstAdded) / forgetAfterMs;
            if (forgotten > track.count) {
                track.count = 0;
                track.firstAdded = now;
            } else {
                track.count -= (int) forgotten;
                track.firstAdded += forgetAfterMs * forgotten;
            }
        }
        if (track.count >= perMinute) return false;
        track.count++;
        return true;
    }
}
