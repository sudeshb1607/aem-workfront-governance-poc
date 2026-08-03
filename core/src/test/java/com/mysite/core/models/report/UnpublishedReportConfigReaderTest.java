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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.search.PredicateGroup;
import com.day.cq.search.Query;
import com.day.cq.search.QueryBuilder;
import com.day.cq.search.result.Hit;
import com.day.cq.search.result.SearchResult;
import com.mysite.core.testcontext.AppAemContext;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

@ExtendWith(AemContextExtension.class)
class UnpublishedReportConfigReaderTest {

    private static final String PATH = "/content/config/jcr:content/report";

    private final AemContext context = AppAemContext.newAemContext();

    @Test
    void readsFullyAuthoredConfig() {
        context.build().resource(PATH,
                "sling:resourceType", ReportConfigConstants.RESOURCE_TYPE,
                "scanRoots", new String[] {"/content/a", "/content/b"},
                "excludePaths", new String[] {"/content/x"},
                "thresholdDays", 45L,
                "outputFolder", "/content/dam/foo/",
                "fileName", "myreport.csv",
                "activateCsv", false);
        context.create().resource(PATH + "/excludeProps/item0",
                "propertyName", "archived", "propertyValue", "yes");
        context.create().resource(PATH + "/columns/item0", "header", "Path", "source", ":path");
        context.create().resource(PATH + "/columns/item1", "header", "URL", "source", ":url");

        final UnpublishedReportConfig config = UnpublishedReportConfigReader.readOne(resource());

        assertEquals(Arrays.asList("/content/a", "/content/b"), config.getScanRoots());
        assertEquals(Arrays.asList("/content/x"), config.getExcludePaths());
        assertEquals(45, config.getThresholdDays());
        assertEquals("/content/dam/foo", config.getOutputFolder(), "trailing slash removed");
        assertEquals("myreport", config.getFileName(), ".csv extension removed");
        assertFalse(config.isActivateCsv());
        assertEquals(1, config.getExcludeProps().size());
        assertEquals("archived", config.getExcludeProps().get(0).getName());
        assertEquals("yes", config.getExcludeProps().get(0).getValue());
        assertEquals(2, config.getColumns().size());
        assertEquals("Path", config.getColumns().get(0).getHeader());
        assertEquals(":url", config.getColumns().get(1).getSource());
    }

    @Test
    void appliesDefaultsForEmptyComponent() {
        context.build().resource(PATH, "sling:resourceType", ReportConfigConstants.RESOURCE_TYPE);

        final UnpublishedReportConfig config = UnpublishedReportConfigReader.readOne(resource());

        assertEquals(Arrays.asList(ReportConfigConstants.DEFAULT_SCAN_ROOT), config.getScanRoots());
        assertEquals(Arrays.asList(ReportConfigConstants.DEFAULT_EXCLUDE_PATH), config.getExcludePaths());
        assertEquals(ReportConfigConstants.DEFAULT_THRESHOLD_DAYS, config.getThresholdDays());
        assertEquals(ReportConfigConstants.DEFAULT_OUTPUT_FOLDER, config.getOutputFolder());
        assertEquals(ReportConfigConstants.DEFAULT_FILE_NAME, config.getFileName());
        assertTrue(config.isActivateCsv());

        assertEquals(1, config.getExcludeProps().size());
        assertEquals(ReportConfigConstants.DEFAULT_EXCLUDE_PROP_NAME, config.getExcludeProps().get(0).getName());
        assertEquals(ReportConfigConstants.DEFAULT_EXCLUDE_PROP_VALUE, config.getExcludeProps().get(0).getValue());

        assertEquals(UnpublishedReportConfigReader.defaultColumns().size(), config.getColumns().size());
        assertEquals("Hash", config.getColumns().get(0).getHeader());
        assertEquals(ReportConfigConstants.SOURCE_HASH, config.getColumns().get(0).getSource());
        assertEquals("Title", config.getColumns().get(1).getHeader());
        assertEquals(ReportConfigConstants.SOURCE_URL, config.getColumns().get(2).getSource());
    }

    @Test
    void parsesThresholdDaysStoredAsString() {
        context.build().resource(PATH,
                "sling:resourceType", ReportConfigConstants.RESOURCE_TYPE,
                "thresholdDays", "30");

        assertEquals(30, UnpublishedReportConfigReader.readOne(resource()).getThresholdDays());
    }

    @Test
    void fallsBackToDefaultThresholdWhenInvalid() {
        context.build().resource(PATH,
                "sling:resourceType", ReportConfigConstants.RESOURCE_TYPE,
                "thresholdDays", "not-a-number");

        assertEquals(ReportConfigConstants.DEFAULT_THRESHOLD_DAYS,
                UnpublishedReportConfigReader.readOne(resource()).getThresholdDays());
    }

    @Test
    void skipsIncompleteMultifieldRows() {
        context.build().resource(PATH, "sling:resourceType", ReportConfigConstants.RESOURCE_TYPE);
        context.create().resource(PATH + "/excludeProps/item0",
                "propertyName", "keep", "propertyValue", "1");
        context.create().resource(PATH + "/excludeProps/item1", "propertyName", "orphan");
        context.create().resource(PATH + "/columns/item0", "header", "Only Header");
        context.create().resource(PATH + "/columns/item1", "header", "Title", "source", ":title");

        final UnpublishedReportConfig config = UnpublishedReportConfigReader.readOne(resource());
        assertEquals(1, config.getExcludeProps().size(), "incomplete exclude-prop row skipped");
        assertEquals("keep", config.getExcludeProps().get(0).getName());
        assertEquals(1, config.getColumns().size(), "incomplete column row skipped");
        assertEquals("Title", config.getColumns().get(0).getHeader());
    }

    @Test
    void trimsAndDropsBlankMultiValueEntries() {
        context.build().resource(PATH,
                "sling:resourceType", ReportConfigConstants.RESOURCE_TYPE,
                "scanRoots", new String[] {"  /content/a  ", "", "   "});

        assertEquals(Arrays.asList("/content/a"),
                UnpublishedReportConfigReader.readOne(resource()).getScanRoots());
    }

    @Test
    void readAllDiscoversEveryComponent() throws Exception {
        final Resource one = context.create().resource("/content/a/jcr:content/report",
                "sling:resourceType", ReportConfigConstants.RESOURCE_TYPE, "fileName", "a");
        final Resource two = context.create().resource("/content/b/jcr:content/report",
                "sling:resourceType", ReportConfigConstants.RESOURCE_TYPE, "fileName", "b");

        final QueryBuilder queryBuilder = stubQuery(Arrays.asList(one, two));

        final List<UnpublishedReportConfig> configs =
                UnpublishedReportConfigReader.readAll(context.resourceResolver(), queryBuilder, "/content");

        assertEquals(2, configs.size());
        assertEquals("a", configs.get(0).getFileName());
        assertEquals("b", configs.get(1).getFileName());
    }

    private Resource resource() {
        return context.resourceResolver().getResource(PATH);
    }

    private QueryBuilder stubQuery(final List<Resource> resources) throws Exception {
        final List<Hit> hits = new ArrayList<>();
        for (final Resource r : resources) {
            final Hit hit = mock(Hit.class);
            when(hit.getResource()).thenReturn(r);
            hits.add(hit);
        }
        final SearchResult result = mock(SearchResult.class);
        when(result.getHits()).thenReturn(hits);
        final Query query = mock(Query.class);
        when(query.getResult()).thenReturn(result);
        final QueryBuilder queryBuilder = mock(QueryBuilder.class);
        when(queryBuilder.createQuery(any(PredicateGroup.class), any())).thenReturn(query);
        return queryBuilder;
    }
}
