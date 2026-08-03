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
package com.mysite.core.services.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.dam.api.AssetManager;
import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.Replicator;
import com.day.cq.search.PredicateGroup;
import com.day.cq.search.Query;
import com.day.cq.search.QueryBuilder;
import com.day.cq.search.result.Hit;
import com.day.cq.search.result.SearchResult;
import com.mysite.core.models.report.ExcludeProperty;
import com.mysite.core.models.report.UnpublishedReportConfig;
import com.mysite.core.models.report.UnpublishedReportConfigReader;
import com.mysite.core.services.UnpublishedPagesReportService.ReportResult;
import com.mysite.core.testcontext.AppAemContext;
import com.mysite.core.util.PageHashUtil;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

@ExtendWith(AemContextExtension.class)
class UnpublishedPagesReportServiceImplTest {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final String OUTPUT_FOLDER = "/content/dam/reports";

    private final AemContext context = AppAemContext.newAemContext();

    private UnpublishedPagesReportServiceImpl service;
    private QueryBuilder queryBuilder;
    private Replicator replicator;

    /** CSV content captured from the (mocked) AssetManager.createAsset call. */
    private String capturedCsv;
    private String capturedAssetPath;

    @BeforeEach
    void setup() throws Exception {
        service = new UnpublishedPagesReportServiceImpl();
        queryBuilder = mock(QueryBuilder.class);
        replicator = mock(Replicator.class);

        // Capture the CSV directly from AssetManager instead of round-tripping through the
        // DAM (MockAssetManager triggers AEM XMP/XML metadata handling that is unavailable here).
        final AssetManager assetManager = mock(AssetManager.class);
        when(assetManager.createAsset(anyString(), any(InputStream.class), anyString(), anyBoolean()))
                .thenAnswer(invocation -> {
                    capturedAssetPath = invocation.getArgument(0);
                    capturedCsv = readStream(invocation.getArgument(1));
                    return null;
                });

        final ResourceResolver spyResolver = spy(context.resourceResolver());
        doNothing().when(spyResolver).close();
        doReturn(assetManager).when(spyResolver).adaptTo(AssetManager.class);
        final ResourceResolverFactory factory = mock(ResourceResolverFactory.class);
        when(factory.getServiceResourceResolver(any())).thenReturn(spyResolver);

        setField(service, "resolverFactory", factory);
        setField(service, "queryBuilder", queryBuilder);
        setField(service, "replicator", replicator);
        setField(service, "pageBatchSize", 500);
    }

    @Test
    void reportsOnlyUnpublishedNonExcludedPages() throws Exception {
        final List<Resource> pages = new ArrayList<>();
        // never published -> included
        pages.add(page("/content/site/never", "Never", null, null, null));
        // stale activate (200 days ago) -> included
        pages.add(page("/content/site/stale", "Stale", daysAgo(200), "Activate", null));
        // fresh activate -> excluded
        pages.add(page("/content/site/fresh", "Fresh", daysAgo(1), "Activate", null));
        // recently deactivated -> included (action != Activate)
        pages.add(page("/content/site/deactivated", "Deactivated", daysAgo(1), "Deactivate", null));
        // excluded by path
        pages.add(page("/content/test/secret", "Secret", null, null, null));
        // excluded by property (even though never published)
        pages.add(page("/content/site/flagged", "Flagged", null, null, "true"));

        stubQuery(pages);

        final ReportResult result = service.generateReport(config());

        assertTrue(result.isSuccess(), result.getErrorMessage());
        assertEquals(3, result.getRowsReported());

        final String[] lines = capturedCsv.split("\r\n");
        assertEquals(4, lines.length, "header + 3 data rows");
        assertEquals("Hash,Title,Page URL,Last Published,Replication Action,Last Modified,Modified By,Template,Resource Type",
                lines[0]);
        assertTrue(capturedCsv.contains("Never"));
        assertTrue(capturedCsv.contains("Stale"));
        assertTrue(capturedCsv.contains("Deactivated"));
        assertFalse(capturedCsv.contains("Fresh"), "recently published page excluded");
        assertFalse(capturedCsv.contains("Secret"), "page under /content/test excluded");
        assertFalse(capturedCsv.contains("Flagged"), "page with excludeFromDelete=true excluded");
    }

    @Test
    void urlAndHashColumnsDerivedFromPath() throws Exception {
        stubQuery(Arrays.asList(page("/content/site/never", "Never", null, null, null)));

        final ReportResult result = service.generateReport(config());
        assertTrue(result.isSuccess());
        assertTrue(capturedCsv.contains("/content/site/never.html"), "url column defaults to path + .html");
        assertTrue(capturedCsv.contains(PageHashUtil.hash("/content/site/never.html")),
                "hash column is the SHA-256 of the page url");
    }

    @Test
    void writesToConfiguredPathAndActivates() throws Exception {
        stubQuery(Arrays.asList(page("/content/site/never", "Never", null, null, null)));

        final ReportResult result = service.generateReport(config());

        assertTrue(result.isSuccess());
        assertNotNull(capturedAssetPath);
        assertTrue(capturedAssetPath.startsWith(OUTPUT_FOLDER + "/report-"), capturedAssetPath);
        assertTrue(capturedAssetPath.endsWith(".csv"));
        assertEquals(capturedAssetPath, result.getCsvPath());
        verify(replicator).replicate(any(), eq(ReplicationActionType.ACTIVATE), eq(result.getCsvPath()));
    }

    @Test
    void skipsActivationWhenDisabled() throws Exception {
        stubQuery(Arrays.asList(page("/content/site/never", "Never", null, null, null)));

        service.generateReport(new UnpublishedReportConfig(
                "/content/config/jcr:content/report",
                Arrays.asList("/content/site"),
                Arrays.asList("/content/test"),
                Arrays.asList(new ExcludeProperty("excludeFromDelete", "true")),
                90, OUTPUT_FOLDER, "report", false,
                UnpublishedReportConfigReader.defaultColumns()));

        verify(replicator, org.mockito.Mockito.never()).replicate(any(), any(), anyString());
    }

    // --- helpers ---

    private UnpublishedReportConfig config() {
        return new UnpublishedReportConfig(
                "/content/config/jcr:content/report",
                Arrays.asList("/content"),
                Arrays.asList("/content/test"),
                Arrays.asList(new ExcludeProperty("excludeFromDelete", "true")),
                90,
                OUTPUT_FOLDER,
                "report",
                true,
                UnpublishedReportConfigReader.defaultColumns());
    }

    private Resource page(final String path, final String title, final Calendar lastReplicated,
                          final String action, final String excludeFromDelete) {
        context.create().resource(path);
        final Map<String, Object> props = new HashMap<>();
        props.put("jcr:title", title);
        if (lastReplicated != null) {
            props.put("cq:lastReplicated", lastReplicated);
        }
        if (action != null) {
            props.put("cq:lastReplicationAction", action);
        }
        if (excludeFromDelete != null) {
            props.put("excludeFromDelete", excludeFromDelete);
        }
        context.create().resource(path + "/jcr:content", props);
        return context.resourceResolver().getResource(path);
    }

    private Calendar daysAgo(final int days) {
        final Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(System.currentTimeMillis() - days * DAY_MS);
        return cal;
    }

    private void stubQuery(final List<Resource> pages) throws Exception {
        final List<Hit> hits = new ArrayList<>();
        for (final Resource r : pages) {
            final Hit hit = mock(Hit.class);
            when(hit.getResource()).thenReturn(r);
            hits.add(hit);
        }
        final SearchResult result = mock(SearchResult.class);
        when(result.getHits()).thenReturn(hits);
        final Query query = mock(Query.class);
        when(query.getResult()).thenReturn(result);
        when(queryBuilder.createQuery(any(PredicateGroup.class), any())).thenReturn(query);
    }

    private static String readStream(final InputStream in) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
