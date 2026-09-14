package com.mysite.core.util;

import java.util.Locale;

/**
 * Shared helpers for the "brand" concept used by the reports. The brand is the
 * content-path segment after {@code /content} (e.g. {@code /content/natwest/...}
 * &rarr; {@code natwest}) and is also sanitised for safe use in file names.
 */
public final class BrandUtil {

    private BrandUtil() {
        // static utility
    }

    /**
     * Extracts the brand segment that follows {@code /content} in a page path,
     * e.g. {@code /content/natwest/gb/en} &rarr; {@code natwest}. Returns an empty
     * string for paths that are not under {@code /content}.
     *
     * @param path the content path (may be {@code null})
     * @return the brand segment, or an empty string
     */
    public static String brandName(final String path) {
        if (path == null) {
            return "";
        }
        final String[] segments = path.split("/");
        // segments[0]="" , segments[1]="content", segments[2]=brand
        if (segments.length >= 3 && "content".equals(segments[1])) {
            return segments[2];
        }
        return "";
    }

    /**
     * Sanitises a brand key into a lowercase, file-safe token (letters, digits and
     * hyphens), e.g. {@code "NatWest UK"} &rarr; {@code "natwest-uk"}. Used when
     * building CSV/JSON file names {@code <reportId>-<brand>}.
     *
     * @param brand the raw brand key (may be {@code null})
     * @return a sanitised token, or {@code "unknown"} when nothing usable remains
     */
    public static String sanitize(final String brand) {
        if (brand == null) {
            return "unknown";
        }
        final String cleaned = brand.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }
}
