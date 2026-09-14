package com.mysite.core.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReportTypeTest {

    @Test
    void resourceTypeRoundTrips() {
        for (final ReportType type : ReportType.values()) {
            assertEquals("mysite/components/reports/" + type.getReportId(), type.getResourceType());
            assertEquals(type, ReportType.fromResourceType(type.getResourceType()));
        }
    }

    @Test
    void unknownResourceTypeIsNull() {
        assertNull(ReportType.fromResourceType("mysite/components/text"));
        assertNull(ReportType.fromResourceType(null));
    }

    @Test
    void allLiveIsUnlimitedNotSentWithFixedFolder() {
        assertEquals("all-live", ReportType.ALL_LIVE.getReportId());
        assertFalse(ReportType.ALL_LIVE.isSendToWorkfront());
        assertEquals(0, ReportType.ALL_LIVE.getDefaultMaxRecords());
        assertEquals("/content/dam/mysite/reports/all-live", ReportType.ALL_LIVE.getDefaultOutputFolder());
    }

    @Test
    void sentReportsDefaultUnderReportsRootCappedAt1000() {
        for (final ReportType type : ReportType.values()) {
            if (type == ReportType.ALL_LIVE) {
                continue;
            }
            assertTrue(type.isSendToWorkfront(), type.name());
            assertEquals(1000, type.getDefaultMaxRecords(), type.name());
            assertEquals("/content/dam/mysite/workfront-reports/" + type.getReportId(),
                    type.getDefaultOutputFolder(), type.name());
        }
    }
}
