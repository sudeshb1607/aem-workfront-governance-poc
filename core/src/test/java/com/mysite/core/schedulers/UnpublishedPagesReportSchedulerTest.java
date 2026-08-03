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
package com.mysite.core.schedulers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.search.PredicateGroup;
import com.day.cq.search.Query;
import com.day.cq.search.QueryBuilder;
import com.day.cq.search.result.Hit;
import com.day.cq.search.result.SearchResult;
import com.mysite.core.models.report.ReportConfigConstants;
import com.mysite.core.models.report.UnpublishedReportConfig;
import com.mysite.core.services.UnpublishedPagesReportService;
import com.mysite.core.services.UnpublishedPagesReportService.ReportResult;
import com.mysite.core.testcontext.AppAemContext;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

@ExtendWith(AemContextExtension.class)
class UnpublishedPagesReportSchedulerTest {

    private final AemContext context = AppAemContext.newAemContext();

    private UnpublishedPagesReportScheduler scheduler;
    private UnpublishedPagesReportService reportService;
    private QueryBuilder queryBuilder;

    @BeforeEach
    void setup() throws Exception {
        scheduler = new UnpublishedPagesReportScheduler();
        reportService = mock(UnpublishedPagesReportService.class);
        queryBuilder = mock(QueryBuilder.class);

        final ResourceResolver spyResolver = spy(context.resourceResolver());
        doNothing().when(spyResolver).close();
        final ResourceResolverFactory factory = mock(ResourceResolverFactory.class);
        when(factory.getServiceResourceResolver(any())).thenReturn(spyResolver);

        final UnpublishedPagesReportScheduler.Config cfg = mock(UnpublishedPagesReportScheduler.Config.class);
        when(cfg.searchRoot()).thenReturn("/content");
        when(cfg.pauseBetweenConfigsSeconds()).thenReturn(0);
        scheduler.activate(cfg);

        setField(scheduler, "resolverFactory", factory);
        setField(scheduler, "queryBuilder", queryBuilder);
        setField(scheduler, "reportService", reportService);
    }

    @Test
    void processesEveryConfigWithFailureIsolation() throws Exception {
        final List<Resource> components = Arrays.asList(
                configComponent("/content/a/jcr:content/report", "a"),
                configComponent("/content/b/jcr:content/report", "b"),
                configComponent("/content/c/jcr:content/report", "c"));
        stubQuery(components);

        // First succeeds, second reports a failure, third throws — all must be attempted.
        when(reportService.generateReport(any()))
                .thenReturn(ReportResult.success("/content/dam/reports/a.csv", 1))
                .thenReturn(ReportResult.failure("/content/dam/reports/b.csv", "boom"))
                .thenThrow(new RuntimeException("kaboom"));

        scheduler.run();

        verify(reportService, times(3)).generateReport(any(UnpublishedReportConfig.class));
    }

    @Test
    void doesNothingWhenNoConfigsExist() throws Exception {
        stubQuery(new ArrayList<>());

        scheduler.run();

        verify(reportService, times(0)).generateReport(any());
    }

    private Resource configComponent(final String path, final String fileName) {
        return context.create().resource(path,
                "sling:resourceType", ReportConfigConstants.RESOURCE_TYPE,
                "fileName", fileName);
    }

    private void stubQuery(final List<Resource> components) throws Exception {
        final List<Hit> hits = new ArrayList<>();
        for (final Resource r : components) {
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

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
