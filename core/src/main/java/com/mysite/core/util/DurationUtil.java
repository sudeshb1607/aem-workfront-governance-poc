package com.mysite.core.util;

/**
 * Formats elapsed millisecond durations into a compact, human-readable string
 * for the report logs, e.g. {@code "1h 3m 12s"}, {@code "45s"}, {@code "10s"}.
 * Only the non-zero leading units are shown; seconds are always present.
 */
public final class DurationUtil {

    private static final long MILLIS_PER_SECOND = 1000L;
    private static final long SECONDS_PER_MINUTE = 60L;
    private static final long SECONDS_PER_HOUR = 3600L;

    private DurationUtil() {
        // static utility
    }

    /**
     * Formats a duration in milliseconds as {@code "<h>h <m>m <s>s"}, dropping
     * leading zero units (hours, then minutes). Seconds are always shown so an
     * instant reads as {@code "0s"}. Negative input is treated as zero.
     *
     * @param millis the elapsed time in milliseconds
     * @return the human-readable duration
     */
    public static String format(final long millis) {
        final long totalSeconds = Math.max(0L, millis) / MILLIS_PER_SECOND;
        final long hours = totalSeconds / SECONDS_PER_HOUR;
        final long minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE;
        final long seconds = totalSeconds % SECONDS_PER_MINUTE;

        final StringBuilder sb = new StringBuilder();
        if (hours > 0) {
            sb.append(hours).append("h ");
        }
        if (hours > 0 || minutes > 0) {
            sb.append(minutes).append("m ");
        }
        sb.append(seconds).append('s');
        return sb.toString();
    }
}
