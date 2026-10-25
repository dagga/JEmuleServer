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


package org.jemule.network;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.random.RandomGenerator;

/**
 * When the index update may send its next search to one server. The searches go by batches (a word and its
 * refinements), 8 to 40 seconds apart; 1 to 6 minutes separate two batches; active periods of 20 to 60 minutes
 * alternate with breaks of 30 minutes to 3 hours. The delays are drawn at random, around a median. Bounds per hour
 * and per day apply whatever the draws.
 * <p>
 * The delays double while the server answers slowly or is nearly full. A server that cuts the session, stops
 * answering or sends a warning is left alone for 6 hours, 24 hours if it happens again within a week, then at half
 * pace for a day.
 */
final class UpdateSchedule {
    static final long SECOND = 1_000L;
    static final long MINUTE = 60 * SECOND;
    static final long HOUR = 60 * MINUTE;
    static final long DAY = 24 * HOUR;

    /** Searches in one batch at most. */
    static final int MAX_PER_BATCH = 6;
    static final long MIN_THINK = 8 * SECOND;
    static final long MEDIAN_THINK = 15 * SECOND;
    static final long MAX_THINK = 40 * SECOND;
    static final long MIN_PAUSE = MINUTE;
    static final long MEDIAN_PAUSE = 150 * SECOND;
    static final long MAX_PAUSE = 6 * MINUTE;
    static final long MIN_ACTIVE = 20 * MINUTE;
    static final long MAX_ACTIVE = 60 * MINUTE;
    static final long MIN_BREAK = 30 * MINUTE;
    static final long MAX_BREAK = 3 * HOUR;
    /** An answer slower than this, or than twice the median of the last hour, doubles the delays. */
    static final long SLOW_ANSWER = 5 * SECOND;
    static final long FIRST_STOP = 6 * HOUR;
    static final long NEXT_STOP = 24 * HOUR;
    static final long HALF_PACE = DAY;
    /** Spread of the log-normal draws around their median. */
    private static final double SIGMA = 0.5;

    private final RandomGenerator random;
    private long nextAt;
    /** End of the current active period; -1 until the next search starts one. */
    private long activeUntil = -1;
    /** Times of the searches of the last day, oldest first. */
    private final Deque<Long> sent = new ArrayDeque<>();
    /** Answer times of the last hour: {time, milliseconds}. */
    private final Deque<long[]> answers = new ArrayDeque<>();
    private boolean slow;
    private long stoppedUntil;
    private long halfPaceUntil;
    private long lastIncident = Long.MIN_VALUE / 2;
    private int incidents;

    /** Starts after a random pause, so that the servers do not all start at once. */
    UpdateSchedule(RandomGenerator random, long now) {
        this.random = random;
        this.nextAt = now + uniform(0, MAX_PAUSE);
    }

    /** The earliest time the next search may be sent. */
    synchronized long nextSearchAt(long now, int maxPerHour, int maxPerDay) {
        prune(now);
        if (now < halfPaceUntil) {
            maxPerHour = Math.max(1, maxPerHour / 2);
            maxPerDay = Math.max(1, maxPerDay / 2);
        }
        long at = Math.max(nextAt, stoppedUntil);
        int inLastHour = 0;
        long firstInLastHour = Long.MAX_VALUE;
        for (long t : sent) {
            if (now - t < HOUR) {
                inLastHour++;
                firstInLastHour = Math.min(firstInLastHour, t);
            }
        }
        if (inLastHour >= maxPerHour) at = Math.max(at, firstInLastHour + HOUR);
        if (sent.size() >= maxPerDay) at = Math.max(at, sent.peekFirst() + DAY);
        return at;
    }

    /** A search was sent. */
    synchronized void sent(long now) {
        sent.addLast(now);
        if (activeUntil < 0) activeUntil = now + uniform(MIN_ACTIVE, MAX_ACTIVE);
    }

    /**
     * The answer to the last search arrived.
     *
     * @param busy       the server is nearly full
     * @param endOfBatch no refinement of this batch is left to send
     */
    synchronized void answered(long now, long answerMillis, boolean busy, boolean endOfBatch) {
        answers.removeIf(a -> now - a[0] >= HOUR);
        long median = median();
        slow = answerMillis > SLOW_ANSWER || (median > 0 && answerMillis > 2 * median);
        answers.addLast(new long[]{now, answerMillis});
        long factor = (slow ? 2 : 1) * (busy ? 2 : 1) * (now < halfPaceUntil ? 2 : 1);
        if (!endOfBatch) {
            nextAt = now + factor * logNormal(MIN_THINK, MEDIAN_THINK, MAX_THINK);
        } else if (activeUntil >= 0 && now >= activeUntil) {
            nextAt = now + uniform(MIN_BREAK, MAX_BREAK);
            activeUntil = -1;
        } else {
            nextAt = now + factor * logNormal(MIN_PAUSE, MEDIAN_PAUSE, MAX_PAUSE);
        }
    }

    /** The server cut the session, did not answer, or warned: no search for hours, then half pace for a day. */
    synchronized void incident(long now) {
        long stop = now - lastIncident < 7 * DAY ? NEXT_STOP : FIRST_STOP;
        lastIncident = now;
        incidents++;
        stoppedUntil = Math.max(stoppedUntil, now + stop);
        halfPaceUntil = stoppedUntil + HALF_PACE;
        activeUntil = -1;
    }

    synchronized boolean stopped(long now) {
        return now < stoppedUntil;
    }

    synchronized long stoppedUntil() {
        return stoppedUntil;
    }

    synchronized int incidents() {
        return incidents;
    }

    /** Searches sent in the last day. */
    synchronized int sentLastDay(long now) {
        prune(now);
        return sent.size();
    }

    private void prune(long now) {
        while (!sent.isEmpty() && now - sent.peekFirst() >= DAY) sent.pollFirst();
    }

    private long median() {
        if (answers.isEmpty()) return 0;
        long[] values = answers.stream().mapToLong(a -> a[1]).sorted().toArray();
        return values[values.length / 2];
    }

    private long uniform(long min, long max) {
        return min + (long) (random.nextDouble() * (max - min));
    }

    private long logNormal(long min, long median, long max) {
        long value = (long) (median * Math.exp(SIGMA * random.nextGaussian()));
        return Math.clamp(value, min, max);
    }
}
