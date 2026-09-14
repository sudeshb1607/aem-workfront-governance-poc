package com.mysite.core.util;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Calendar;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.ValueMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared date helpers for the reports: reads a JCR date property flexibly (stored
 * as a {@code Calendar}, an ISO {@code yyyy-MM-dd} string, or an ISO offset
 * datetime) and exposes it as a {@link LocalDate}.
 */
public final class DateUtils {

    private static final Logger LOG = LoggerFactory.getLogger(DateUtils.class);

    private DateUtils() {
        // static utility
    }

    /**
     * Reads a date property as a {@link LocalDate}.
     *
     * @param vm       the ValueMap to read from
     * @param property the property name
     * @return the parsed date, or {@code null} when absent/unparseable
     */
    public static LocalDate readLocalDate(final ValueMap vm, final String property) {
        if (vm == null || property == null) {
            return null;
        }
        final Calendar cal = vm.get(property, Calendar.class);
        if (cal != null) {
            return toLocalDate(cal);
        }
        final String raw = StringUtils.trimToNull(vm.get(property, String.class));
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (final DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(raw).toLocalDate();
            } catch (final DateTimeParseException e2) {
                LOG.warn("Unparseable date '{}' for property '{}'", raw, property);
                return null;
            }
        }
    }

    /**
     * Converts a {@link Calendar} to a {@link LocalDate} in its own zone.
     *
     * @param calendar the calendar (may be {@code null})
     * @return the {@link LocalDate}, or {@code null} when {@code calendar} is null
     */
    public static LocalDate toLocalDate(final Calendar calendar) {
        if (calendar == null) {
            return null;
        }
        final ZoneId zone = calendar.getTimeZone() != null
                ? calendar.getTimeZone().toZoneId() : ZoneId.systemDefault();
        return calendar.toInstant().atZone(zone).toLocalDate();
    }
}
