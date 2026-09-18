package com.mysite.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DurationUtilTest {

    @Test
    void secondsOnly() {
        assertEquals("0s", DurationUtil.format(0L));
        assertEquals("10s", DurationUtil.format(10_000L));
        assertEquals("45s", DurationUtil.format(45_000L));
    }

    @Test
    void minutesAndSeconds() {
        assertEquals("2m 5s", DurationUtil.format(125_000L));
    }

    @Test
    void hoursMinutesSeconds() {
        // 1h 3m 12s = 3792 seconds
        assertEquals("1h 3m 12s", DurationUtil.format(3_792_000L));
    }

    @Test
    void hoursWithZeroMinutes() {
        assertEquals("2h 0m 0s", DurationUtil.format(7_200_000L));
    }

    @Test
    void negativeIsTreatedAsZero() {
        assertEquals("0s", DurationUtil.format(-5_000L));
    }
}
