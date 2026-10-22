package org.jemule.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MetricsHistoryTest {
    private static final long HOUR = 3_600_000L;

    @TempDir
    Path tempDir;

    @Test
    void samplesAreReadBackByTimeAndForgottenAfterAMonth() {
        MetricsHistory history = new MetricsHistory(null, 0);
        long start = 100 * HOUR;
        history.record(start, 10, 1);
        history.record(start + HOUR, 20, 2);
        history.record(start + 2 * HOUR, 30, 3);

        assertEquals(20, history.at(start + HOUR + 1).files());
        assertNull(history.at(start - 1));
        assertEquals(List.of(new MetricsHistory.Sample(start + 2 * HOUR, 30, 3)), history.since(start + 2 * HOUR));

        history.record(start + MetricsHistory.RETENTION_MS + HOUR, 40, 4);
        assertEquals(start + 2 * HOUR, history.oldest().time(), "the samples older than the retention are gone");
    }

    @Test
    void samplesSurviveARestart() throws Exception {
        String dbPath = tempDir.resolve("metrics").toString();
        long now = System.currentTimeMillis();
        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            MetricsHistory history = new MetricsHistory(db, now);
            history.record(now - MetricsHistory.RETENTION_MS - HOUR, 1, 1); // already too old: deleted with the next samples
            history.record(now - HOUR, 500, 7);
            history.record(now, 510, 8);
        }
        try (DatabaseManager db = new DatabaseManager(dbPath)) {
            MetricsHistory history = new MetricsHistory(db, now);
            assertEquals(List.of(new MetricsHistory.Sample(now - HOUR, 500, 7), new MetricsHistory.Sample(now, 510, 8)),
                    history.since(0));
        }
    }
}
