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

package org.jemule.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The curves of the Status view. */
class StatusHistoryTest {

    @Test
    void samplesEveryMetricInItsOrderAndKeepsADay() {
        AtomicInteger clients = new AtomicInteger(4);
        StatusHistory history = new StatusHistory()
                .add("connectedClients", "Connected clients", "clients", StatusHistory.Kind.GAUGE, clients::get)
                .add("refused", "Refused", "connections", StatusHistory.Kind.COUNTER, () -> 7);
        assertEquals(List.of("connectedClients", "refused"), history.metrics().stream().map(StatusHistory.Metric::key).toList());

        long t0 = 1_000_000_000L;
        history.record(t0);
        clients.set(9);
        history.record(t0 + StatusHistory.INTERVAL_MS);
        List<StatusHistory.Sample> samples = history.since(0);
        assertEquals(2, samples.size());
        assertArrayEquals(new double[] {4, 7}, samples.get(0).values());
        assertArrayEquals(new double[] {9, 7}, samples.get(1).values());
        assertEquals(1, history.since(t0 + 1).size());

        // A day later, the first sample is forgotten
        history.record(t0 + StatusHistory.RETENTION_MS);
        assertEquals(List.of(t0 + StatusHistory.INTERVAL_MS, t0 + StatusHistory.RETENTION_MS),
                history.since(0).stream().map(StatusHistory.Sample::time).toList());
    }

    @Test
    void aValueThatCannotBeReadIsAGap() {
        StatusHistory history = new StatusHistory()
                .add("failing", "Failing", "", StatusHistory.Kind.GAUGE, () -> { throw new IllegalStateException("closed"); })
                .add("infinite", "Infinite", "", StatusHistory.Kind.GAUGE, () -> Double.POSITIVE_INFINITY)
                .add("fine", "Fine", "", StatusHistory.Kind.GAUGE, () -> 1.5);
        history.record(1);
        double[] values = history.since(0).getFirst().values();
        assertTrue(Double.isNaN(values[0]));
        assertTrue(Double.isNaN(values[1]));
        assertEquals(1.5, values[2]);
    }

    @Test
    void theMetricsAreRegisteredBeforeTheFirstSample() {
        StatusHistory history = new StatusHistory().add("a", "A", "", StatusHistory.Kind.GAUGE, () -> 1);
        history.record(1);
        assertThrows(IllegalStateException.class, () -> history.add("b", "B", "", StatusHistory.Kind.GAUGE, () -> 2));
    }
}
