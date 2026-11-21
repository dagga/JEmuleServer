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


package org.jemule.network.natt;

/** A burst of {@code burst} requests, then one every {@code intervalMs}; times are given by the caller. */
final class TokenBucket {
    private final int burst;
    private final long intervalMs;
    private double tokens;
    private long last;

    TokenBucket(int burst, long intervalMs, long now) {
        this.burst = burst;
        this.intervalMs = intervalMs;
        this.tokens = burst;
        this.last = now;
    }

    /** Takes one token if there is one. */
    synchronized boolean tryTake(long now) {
        if (now > last) {
            tokens = Math.min(burst, tokens + (double) (now - last) / intervalMs);
            last = now;
        }
        if (tokens < 1) return false;
        tokens -= 1;
        return true;
    }
}
