package com.mysite.core.schedulers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collections;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.search.PredicateGroup;
import com.day.cq.search.Query;
import com.day.cq.search.QueryBuilder;
import com.day.cq.search.result.Hit;
import com.day.cq.search.result.SearchResult;
import com.mysite.core.reports.ReportType;
import com.mysite.core.services.ReportGeneratorService;
import com.mysite.core.services.ReportGeneratorService.ReportRunResult;
import com.mysite.core.testcontext.AppAemContext;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

@ExtendWith(AemContextExtension.class)
class ReportCsvGeneratorSchedulerTest {

    private final AemContext context = AppAemContext.newAemContext();

    private ReportCsvGeneratorScheduler scheduler;
    private QueryBuilder queryBuilder;
    private ReportGeneratorService reportService;

    @BeforeEach
    void setup() throws Exception {
        scheduler = new ReportCsvGeneratorScheduler();
        queryBuilder = mock(QueryBuilder.class);
        reportService = mock(ReportGeneratorService.class);

        final ResourceResolverFactory rrf = mock(ResourceResolverFactory.class);
        when(rrf.getServiceResourceResolver(any())).thenReturn(context.resourceResolver());

        setField(scheduler, "resolverFactory", rrf);
        setField(scheduler, "queryBuilder", queryBuilder);
        setField(scheduler, "reportService", reportService);

        final ReportCsvGeneratorScheduler.Config cfg = mock(ReportCsvGeneratorScheduler.Config.class);
        when(cfg.searchRoot()).thenReturn("/content");
        when(cfg.pauseBetweenReportsSeconds()).thenReturn(0);
        scheduler.activate(cfg);
    }

    /** Makes readAll() return one hit (the given component) for every resource-type query. */
    private void stubQueryReturns(final Resource component) {
        final Query query = mock(Query.class);
        final SearchResult result = mock(SearchResult.class);
        final Hit hit = mock(Hit.class);
        try {
            when(hit.getResource()).thenReturn(component);
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
        when(queryBuilder.createQuery(any(PredicateGroup.class), any())).thenReturn(query);
        when(query.getResult()).thenReturn(result);
        when(result.getHits()).thenReturn(component == null
                ? Collections.emptyList() : Collections.singletonList(hit));
    }

    @Test
    void generatesEachDiscoveredReport() throws Exception {
        context.build().resource("/content/cfg/stale",
                "sling:resourceType", ReportType.NOT_LIVE_STALE.getResourceType()).commit();
        stubQueryReturns(context.resourceResolver().getResource("/content/cfg/stale"));
        when(reportService.generate(any())).thenReturn(ReportRunResult.success(
                Collections.singletonList("/content/dam/mysite/workfront-reports/not-live-stale/csv/x.csv"), 3));

        scheduler.run();

        verify(reportService, atLeastOnce()).generate(any());
    }

    @Test
    void noConfigsMeansNoGeneration() throws Exception {
        stubQueryReturns(null);
        scheduler.run();
        verify(reportService, never()).generate(any());
    }

    @Test
    void reportFailureIsHandled() throws Exception {
        context.build().resource("/content/cfg/stale",
                "sling:resourceType", ReportType.NOT_LIVE_STALE.getResourceType()).commit();
        stubQueryReturns(context.resourceResolver().getResource("/content/cfg/stale"));
        when(reportService.generate(any())).thenReturn(ReportRunResult.failure("boom"));

        scheduler.run();
        verify(reportService, atLeastOnce()).generate(any());
    }

    @Test
    void reportExceptionIsHandled() throws Exception {
        context.build().resource("/content/cfg/stale",
                "sling:resourceType", ReportType.NOT_LIVE_STALE.getResourceType()).commit();
        stubQueryReturns(context.resourceResolver().getResource("/content/cfg/stale"));
        when(reportService.generate(any())).thenThrow(new RuntimeException("boom"));

        scheduler.run();
        verify(reportService, atLeastOnce()).generate(any());
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        final Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
