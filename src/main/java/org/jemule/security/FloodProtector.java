/*
 * JEmuleServer - An experimental eMule server.
 * Copyright (C) 2026 Nicolas Hernandez (herniatgmail.com)
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

public class FloodProtector {
    private final int maxPerSec;
    private final ConcurrentHashMap<InetAddress, TokenBucket> buckets = new ConcurrentHashMap<>();

    public FloodProtector(int maxPerSec) {
        this.maxPerSec = maxPerSec;
    }

    public boolean allow(InetAddress ip) {
        return buckets.computeIfAbsent(ip, k -> new TokenBucket(maxPerSec)).tryConsume();
    }

    private class TokenBucket {
        private final long maxTokens;
        private long tokens;
        private long lastRefill = System.nanoTime();

        TokenBucket(long max) {
            tokens = max;
            this.maxTokens = max;
        }

        synchronized boolean tryConsume() {
            long now = System.nanoTime();
            if (now - lastRefill >= 1_000_000_000L) {
                tokens = maxTokens;
                lastRefill = now;
            }
            if (tokens > 0) {
                tokens--;
                return true;
            }
            return false;
        }
    }
}
