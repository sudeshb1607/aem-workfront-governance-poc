package com.mysite.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.jupiter.api.Test;

class DateUtilsTest {

    private static ValueMap vm(final String key, final Object value) {
        final Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return new ValueMapDecorator(map);
    }

    @Test
    void readsCalendarProperty() {
        final Calendar cal = Calendar.getInstance();
        cal.set(2026, Calendar.MARCH, 15, 10, 0, 0);
        assertEquals(LocalDate.of(2026, 3, 15), DateUtils.readLocalDate(vm("d", cal), "d"));
    }

    @Test
    void readsIsoDateString() {
        assertEquals(LocalDate.of(2026, 12, 31), DateUtils.readLocalDate(vm("d", "2026-12-31"), "d"));
    }

    @Test
    void readsOffsetDateTimeString() {
        assertEquals(LocalDate.of(2026, 4, 22),
                DateUtils.readLocalDate(vm("d", "2026-04-22T00:00:00.000+02:00"), "d"));
    }

    @Test
    void returnsNullForMissingOrUnparseable() {
        assertNull(DateUtils.readLocalDate(vm("other", "x"), "d"));
        assertNull(DateUtils.readLocalDate(vm("d", "not-a-date"), "d"));
        assertNull(DateUtils.readLocalDate(new ValueMapDecorator(new HashMap<>()), "d"));
    }

    @Test
    void nullInputsAreNull() {
        assertNull(DateUtils.readLocalDate(null, "d"));
        assertNull(DateUtils.readLocalDate(vm("d", "2026-01-01"), null));
        assertNull(DateUtils.toLocalDate(null));
    }

    @Test
    void toLocalDateConvertsCalendar() {
        final Calendar cal = Calendar.getInstance();
        cal.set(2026, Calendar.JUNE, 1, 0, 0, 0);
        assertEquals(LocalDate.of(2026, 6, 1), DateUtils.toLocalDate(cal));
    }
}
