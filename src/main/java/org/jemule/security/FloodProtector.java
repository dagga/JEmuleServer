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


package org.jemule.security;

import java.net.InetAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class FloodProtector {
    private volatile int maxPerSec;
    private final ConcurrentHashMap<InetAddress, TokenBucket> buckets = new ConcurrentHashMap<>();

    public FloodProtector(int maxPerSec) {
        this.maxPerSec = maxPerSec;
    }

    /** Applies a new per-IP rate immediately; buckets already in use pick it up at their next refill. */
    public void setMaxPerSec(int maxPerSec) {
        this.maxPerSec = maxPerSec;
    }

    public int getMaxPerSec() {
        return maxPerSec;
    }

    public boolean allow(InetAddress ip) {
        // Opportunistic cleanup: if map becomes too large, clear expired entries
        if (buckets.size() > 10000) {
            buckets.entrySet().removeIf(entry -> entry.getValue().isExpired());
        }
        return buckets.computeIfAbsent(ip, k -> new TokenBucket(this::getMaxPerSec)).tryConsume();
    }

    private static class TokenBucket {
        private final java.util.function.IntSupplier maxTokens;
        private final AtomicLong tokens;
        private final AtomicLong lastRefill;

        TokenBucket(java.util.function.IntSupplier maxTokens) {
            this.maxTokens = maxTokens;
            this.tokens = new AtomicLong(maxTokens.getAsInt());
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
                    tokens.set(maxTokens.getAsInt());
                }
            }
        }

        boolean isExpired() {
            return System.nanoTime() - lastRefill.get() > 60_000_000_000L; // 1 minute
        }
    }
}
