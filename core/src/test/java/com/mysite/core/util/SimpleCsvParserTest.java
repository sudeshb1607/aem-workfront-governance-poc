package com.mysite.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SimpleCsvParserTest {

    @Test
    void parsesSimpleRows() {
        final String csv = "Hash,Title,Path\r\n"
                + "abc,Home,/content/mysite\r\n"
                + "def,About,/content/mysite/about\r\n";
        final List<Map<String, String>> rows = SimpleCsvParser.parse(csv);

        assertEquals(2, rows.size());
        assertEquals("abc", rows.get(0).get("Hash"));
        assertEquals("Home", rows.get(0).get("Title"));
        assertEquals("/content/mysite/about", rows.get(1).get("Path"));
    }

    @Test
    void handlesQuotedFieldsWithCommasQuotesAndNewlines() {
        final String csv = "Title,Path\r\n"
                + "\"My, awesome \"\"page\"\"\",/content/a\r\n"
                + "\"Line1\nLine2\",/content/b\r\n";
        final List<Map<String, String>> rows = SimpleCsvParser.parse(csv);

        assertEquals(2, rows.size());
        assertEquals("My, awesome \"page\"", rows.get(0).get("Title"));
        assertEquals("/content/a", rows.get(0).get("Path"));
        assertEquals("Line1\nLine2", rows.get(1).get("Title"));
    }

    @Test
    void handlesLfOnlyAndTrailingNewline() {
        final String csv = "A,B\nx,y\n";
        final List<Map<String, String>> rows = SimpleCsvParser.parse(csv);
        assertEquals(1, rows.size());
        assertEquals("x", rows.get(0).get("A"));
        assertEquals("y", rows.get(0).get("B"));
    }

    @Test
    void missingTrailingColumnsBecomeEmpty() {
        final String csv = "A,B,C\r\n1,2\r\n";
        final List<Map<String, String>> rows = SimpleCsvParser.parse(csv);
        assertEquals(1, rows.size());
        assertEquals("1", rows.get(0).get("A"));
        assertEquals("2", rows.get(0).get("B"));
        assertEquals("", rows.get(0).get("C"));
    }

    @Test
    void emptyOrNullInputYieldsNoRows() {
        assertTrue(SimpleCsvParser.parse("").isEmpty());
        assertTrue(SimpleCsvParser.parse(null).isEmpty());
    }

    @Test
    void headerOnlyYieldsNoRows() {
        assertTrue(SimpleCsvParser.parse("A,B,C\r\n").isEmpty());
    }

    @Test
    void handlesLoneCarriageReturnLineEndings() {
        final List<Map<String, String>> rows = SimpleCsvParser.parse("A,B\rx,y\rp,q\r");
        assertEquals(2, rows.size());
        assertEquals("x", rows.get(0).get("A"));
        assertEquals("q", rows.get(1).get("B"));
    }

    @Test
    void quotedFieldSpanningLineIsOneRecord() {
        final List<Map<String, String>> rows = SimpleCsvParser.parse("A\r\n\"line1\r\nline2\"\r\n");
        assertEquals(1, rows.size());
        assertEquals("line1\r\nline2", rows.get(0).get("A"));
    }
}
