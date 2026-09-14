package com.mysite.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class CsvSupportTest {

    @Test
    void escapesCommaQuoteNewline() {
        assertEquals("plain", CsvSupport.escape("plain"));
        assertEquals("\"a,b\"", CsvSupport.escape("a,b"));
        assertEquals("\"a\"\"b\"", CsvSupport.escape("a\"b"));
        assertEquals("\"a\nb\"", CsvSupport.escape("a\nb"));
        assertEquals("", CsvSupport.escape(null));
    }

    @Test
    void appendRowJoinsAndTerminates() {
        final StringBuilder sb = new StringBuilder();
        CsvSupport.appendRow(sb, Arrays.asList("x", "a,b", "y"));
        assertEquals("x,\"a,b\",y\r\n", sb.toString());
    }
}
