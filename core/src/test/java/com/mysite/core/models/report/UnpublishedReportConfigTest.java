/*
 *  Copyright 2024 Adobe Systems Incorporated
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.mysite.core.models.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class UnpublishedReportConfigTest {

    private UnpublishedReportConfig newConfig(final List<String> scanRoots) {
        return new UnpublishedReportConfig(
                "/content/config/jcr:content/report",
                scanRoots,
                Arrays.asList("/content/test"),
                Arrays.asList(new ExcludeProperty("excludeFromDelete", "true")),
                90,
                "/content/dam/mysite/reports",
                "unpublished-pages",
                true,
                Arrays.asList(new ReportColumn("Title", ":title")));
    }

    @Test
    void exposesAllValues() {
        final UnpublishedReportConfig config = newConfig(Arrays.asList("/content", "/content/other"));
        assertEquals("/content/config/jcr:content/report", config.getComponentPath());
        assertEquals(Arrays.asList("/content", "/content/other"), config.getScanRoots());
        assertEquals(Arrays.asList("/content/test"), config.getExcludePaths());
        assertEquals(1, config.getExcludeProps().size());
        assertEquals("excludeFromDelete", config.getExcludeProps().get(0).getName());
        assertEquals(90, config.getThresholdDays());
        assertEquals("/content/dam/mysite/reports", config.getOutputFolder());
        assertEquals("unpublished-pages", config.getFileName());
        assertTrue(config.isActivateCsv());
        assertEquals(1, config.getColumns().size());
    }

    @Test
    void listsAreImmutable() {
        final UnpublishedReportConfig config = newConfig(new ArrayList<>(Arrays.asList("/content")));
        assertThrows(UnsupportedOperationException.class, () -> config.getScanRoots().add("/x"));
        assertThrows(UnsupportedOperationException.class, () -> config.getExcludePaths().add("/x"));
        assertThrows(UnsupportedOperationException.class,
                () -> config.getExcludeProps().add(new ExcludeProperty("a", "b")));
        assertThrows(UnsupportedOperationException.class,
                () -> config.getColumns().add(new ReportColumn("h", "s")));
    }
}
