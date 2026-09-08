package com.mysite.core.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal RFC 4180 CSV parser used to turn the Workfront dashboard CSV exports
 * back into structured rows for JSON conversion. Handles quoted fields, doubled
 * quotes ({@code ""}), embedded commas/newlines inside quotes, and {@code \r\n},
 * {@code \n} or lone {@code \r} line endings. It is the inverse of the escaping
 * done by the CSV generator.
 *
 * <p>The first record is treated as the header; each subsequent record is mapped
 * header column -> value. Missing trailing columns map to an empty string; fully
 * empty trailing lines are ignored.</p>
 */
public final class SimpleCsvParser {

    private SimpleCsvParser() {
        // static utility
    }

    /**
     * Parses CSV text into a list of rows keyed by header column name.
     *
     * @param csvText the full CSV document (may be {@code null}/empty)
     * @return one map per data row, in file order; empty when there is no data
     */
    public static List<Map<String, String>> parse(final String csvText) {
        final List<Map<String, String>> rows = new ArrayList<>();
        if (csvText == null || csvText.isEmpty()) {
            return rows;
        }

        final List<List<String>> records = splitRecords(csvText);
        if (records.isEmpty()) {
            return rows;
        }

        final List<String> header = records.get(0);
        for (int r = 1; r < records.size(); r++) {
            final List<String> fields = records.get(r);
            // Skip a fully empty trailing line (single empty field).
            if (fields.size() == 1 && fields.get(0).isEmpty()) {
                continue;
            }
            final Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                row.put(header.get(c), c < fields.size() ? fields.get(c) : "");
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * Splits the document into records (lists of raw field values), honouring
     * quoting so delimiters and line breaks inside quotes are preserved.
     */
    private static List<List<String>> splitRecords(final String text) {
        final List<List<String>> records = new ArrayList<>();
        List<String> current = new ArrayList<>();
        final StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        final int n = text.length();

        for (int i = 0; i < n; i++) {
            final char ch = text.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(ch);
                }
                continue;
            }

            switch (ch) {
                case '"':
                    inQuotes = true;
                    break;
                case ',':
                    current.add(field.toString());
                    field.setLength(0);
                    break;
                case '\n':
                    current.add(field.toString());
                    field.setLength(0);
                    records.add(current);
                    current = new ArrayList<>();
                    break;
                case '\r':
                    // CRLF: let the following LF close the record; lone CR closes it here.
                    if (!(i + 1 < n && text.charAt(i + 1) == '\n')) {
                        current.add(field.toString());
                        field.setLength(0);
                        records.add(current);
                        current = new ArrayList<>();
                    }
                    break;
                default:
                    field.append(ch);
                    break;
            }
        }

        // Flush the final field/record (file may not end with a newline).
        current.add(field.toString());
        records.add(current);
        return records;
    }
}
