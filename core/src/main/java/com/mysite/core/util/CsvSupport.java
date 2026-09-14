package com.mysite.core.util;

import java.util.List;

/**
 * Shared CSV helpers (RFC 4180) used by the report writers so escaping, the line
 * terminator, and row assembly live in one place.
 */
public final class CsvSupport {

    /** RFC 4180 line terminator. */
    public static final String NEWLINE = "\r\n";

    private CsvSupport() {
        // static utility
    }

    /**
     * Escapes a single field per RFC 4180: wraps it in quotes when it contains a
     * comma, quote, or newline, and doubles any embedded quotes.
     *
     * @param value the raw field value (may be {@code null})
     * @return the escaped field, never {@code null}
     */
    public static String escape(final String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    /**
     * Appends one CSV record (escaped, comma-separated, terminated with {@code \r\n}).
     *
     * @param csv    the buffer to append to
     * @param values the ordered field values for this row
     */
    public static void appendRow(final StringBuilder csv, final List<String> values) {
        boolean first = true;
        for (final String value : values) {
            if (!first) {
                csv.append(',');
            }
            csv.append(escape(value));
            first = false;
        }
        csv.append(NEWLINE);
    }
}
