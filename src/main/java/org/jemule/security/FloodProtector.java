/*
 * JEmuleServer - An experimental eMule server.
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


package org.jemule.security;

import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class FloodProtector {
    private final int maxPerSec;
    private final ConcurrentHashMap<InetAddress, TokenBucket> buckets = new ConcurrentHashMap<>();

    public FloodProtector(int maxPerSec) {
        this.maxPerSec = maxPerSec;
    }

    public boolean allow(InetAddress ip) {
        // Nettoyage opportuniste : si la map devient trop grande, on pourrait la vider
        // Mais pour l'instant, on se concentre sur la performance du bucket.
        if (buckets.size() > 10000) {
            buckets.entrySet().removeIf(entry -> entry.getValue().isExpired());
        }
        return buckets.computeIfAbsent(ip, k -> new TokenBucket(maxPerSec)).tryConsume();
    }

    private static class TokenBucket {
        private final long maxTokens;
        private final AtomicLong tokens;
        private final AtomicLong lastRefill;

        TokenBucket(long max) {
            this.maxTokens = max;
            this.tokens = new AtomicLong(max);
            this.lastRefill = new AtomicLong(System.nanoTime());
        }

        boolean tryConsume() {
            refill();
            long current = tokens.get();
            while (current > 0) {
                if (tokens.compareAndSet(current, current - 1)) {
                    return true;
                }
                current = tokens.get();
            }
            return false;
        }

        private void refill() {
            long now = System.nanoTime();
            long last = lastRefill.get();
            if (now - last >= 1_000_000_000L) {
                if (lastRefill.compareAndSet(last, now)) {
                    tokens.set(maxTokens);
                }
            }
        }

        boolean isExpired() {
            return System.nanoTime() - lastRefill.get() > 60_000_000_000L; // 1 minute
        }
    }
}
