package org.jemule.network;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.jemule.network.UpdateSchedule.*;
import static org.junit.jupiter.api.Assertions.*;

class UpdateScheduleTest {
    private static final long START = 1_000_000_000L;

    @Test
    void searchesOfABatchAreSecondsApartAndNeverPeriodic() {
        UpdateSchedule schedule = new UpdateSchedule(new Random(1), START);
        long now = schedule.nextSearchAt(START, 40, 300);
        assertTrue(now - START <= MAX_PAUSE, "the first search waits a random pause at most");
        List<Long> gaps = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            schedule.sent(now);
            schedule.answered(now + 300, 300, false, false);
            long next = schedule.nextSearchAt(now + 300, 1000, 10_000);
            gaps.add(next - (now + 300));
            now = next;
        }
        assertTrue(gaps.stream().allMatch(g -> g >= MIN_THINK && g <= MAX_THINK), gaps.toString());
        assertTrue(gaps.stream().distinct().count() > 20, "drawn at random: " + gaps);
    }

    @Test
    void batchesArePausesApartAndActivePeriodsAlternateWithBreaks() {
        UpdateSchedule schedule = new UpdateSchedule(new Random(2), START);
        long now = START;
        boolean sawBreak = false;
        for (int i = 0; i < 200; i++) {
            now = schedule.nextSearchAt(now, 1000, 10_000);
            schedule.sent(now);
            schedule.answered(now, 500, false, true);
            long gap = schedule.nextSearchAt(now, 1000, 10_000) - now;
            if (gap >= MIN_BREAK) {
                sawBreak = true;
                assertTrue(gap <= MAX_BREAK, "break " + gap);
            } else {
                assertTrue(gap >= MIN_PAUSE && gap <= MAX_PAUSE, "pause " + gap);
            }
        }
        assertTrue(sawBreak, "an active period ends with a break");
    }

    @Test
    void hourlyAndDailyBoundsHoldWhateverTheDraws() {
        UpdateSchedule schedule = new UpdateSchedule(new Random(3), START);
        long now = START;
        for (int i = 0; i < 3; i++) {
            now = Math.max(now, schedule.nextSearchAt(now, 3, 300));
            schedule.sent(now);
            schedule.answered(now, 100, false, false);
        }
        long first = START + 1; // at least the first search's time
        assertTrue(schedule.nextSearchAt(now, 3, 300) >= first + HOUR - MAX_PAUSE - 1,
                "3 an hour: the fourth waits for the hour");
        assertTrue(schedule.nextSearchAt(now, 1000, 3) >= START + DAY - MAX_PAUSE, "3 a day");
    }

    @Test
    void slowAnswersAndBusyServersDoubleTheDelays() {
        Random random = new Random(4);
        UpdateSchedule schedule = new UpdateSchedule(random, START);
        long now = START;
        schedule.sent(now);
        schedule.answered(now, SLOW_ANSWER + 1, false, false);
        long gap = schedule.nextSearchAt(now, 1000, 10_000) - now;
        assertTrue(gap >= 2 * MIN_THINK && gap <= 2 * MAX_THINK, "slow: " + gap);

        schedule.sent(now);
        schedule.answered(now, 100, true, false);
        gap = schedule.nextSearchAt(now, 1000, 10_000) - now;
        assertTrue(gap >= 2 * MIN_THINK && gap <= 2 * MAX_THINK, "busy: " + gap);
    }

    @Test
    void anIncidentStopsForHoursThenADayAndHalvesThePace() {
        UpdateSchedule schedule = new UpdateSchedule(new Random(5), START);
        schedule.incident(START);
        assertTrue(schedule.stopped(START + FIRST_STOP - 1));
        assertEquals(START + FIRST_STOP, schedule.nextSearchAt(START, 40, 300));
        long later = START + FIRST_STOP + HOUR;
        schedule.incident(later);
        assertEquals(later + NEXT_STOP, schedule.stoppedUntil(), "again within a week: a day");

        // Half pace for a day after the stop: half the bounds
        long resumed = later + NEXT_STOP;
        for (int i = 0; i < 20; i++) schedule.sent(resumed);
        assertTrue(schedule.nextSearchAt(resumed, 40, 300) > resumed, "20 of 40/2 an hour");
        assertEquals(2, schedule.incidents());
    }
}
