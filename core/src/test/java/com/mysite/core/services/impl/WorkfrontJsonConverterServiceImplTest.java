package com.mysite.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.json.JsonObject;

import org.junit.jupiter.api.Test;

/**
 * Exercises the pure CSV -> JSON conversion ({@link WorkfrontJsonConverterServiceImpl#buildDataset})
 * without DAM asset I/O, so the parsing/typing/skipping logic is verified directly.
 */
class WorkfrontJsonConverterServiceImplTest {

    private static final String HEADER = "Hash,Title,Path,Brand,Last Modified,Modified By,Published,"
            + "Next Review Date,Days For Next Review,Franchise,Page Owners,Template\r\n";

    private final WorkfrontJsonConverterServiceImpl service = new WorkfrontJsonConverterServiceImpl();

    @Test
    void convertsCsvToJsonWithTypedFields() {
        final String csv = HEADER
                + "abc,\"Home, sweet\",/content/mysite/us/en,mysite,2026-07-30,jdoe,True,"
                + "2026-12-31,114,retail,borrow,/conf/mysite/page\r\n";

        final JsonObject payload = service.buildDataset("us-en", csv);
        assertEquals("us-en", payload.getString("dataset"));
        assertEquals(1, payload.getInt("recordCount"));
        assertTrue(payload.containsKey("generatedAt"));

        final JsonObject record = payload.getJsonArray("records").getJsonObject(0);
        assertEquals("abc", record.getString("hash"));
        assertEquals("Home, sweet", record.getString("title"));
        assertEquals("/content/mysite/us/en", record.getString("path"));
        assertEquals("mysite", record.getString("brand"));
        assertTrue(record.getBoolean("published"), "Published should be a real boolean");
        assertEquals(114, record.getInt("daysForNextReview"), "Days should be a real int");
        assertEquals("retail", record.getString("franchise"));
        assertEquals("borrow", record.getString("pageOwners"));
    }

    @Test
    void blankReviewDateProducesNullDaysAndFalsePublished() {
        final String csv = HEADER
                + "h1,Root,/content/mysite,mysite,2026-07-30,admin,False,,,,,\r\n";

        final JsonObject record = service.buildDataset("root", csv)
                .getJsonArray("records").getJsonObject(0);
        assertFalse(record.getBoolean("published"));
        assertTrue(record.isNull("daysForNextReview"), "Blank days should be JSON null");
    }

    @Test
    void nonIntegerDaysProducesNull() {
        final String csv = HEADER
                + "h1,Root,/content/mysite,mysite,2026-07-30,admin,True,2026-12-31,notanumber,,,\r\n";

        final JsonObject record = service.buildDataset("root", csv)
                .getJsonArray("records").getJsonObject(0);
        assertTrue(record.isNull("daysForNextReview"));
    }

    @Test
    void skipsRowsWithoutPath() {
        final String csv = HEADER
                + "h1,Valid,/content/mysite/a,mysite,2026-07-30,admin,True,,,,,\r\n"
                + "h2,NoPath,,mysite,2026-07-30,admin,True,,,,,\r\n";

        final JsonObject payload = service.buildDataset("mixed", csv);
        assertEquals(1, payload.getInt("recordCount"), "Row without a Path must be skipped");
    }

    @Test
    void emptyCsvProducesZeroRecords() {
        assertEquals(0, service.buildDataset("empty", HEADER).getInt("recordCount"));
    }
}
