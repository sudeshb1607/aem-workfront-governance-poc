package com.mysite.core.schedulers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.mysite.core.services.WorkfrontJsonConverterService;
import com.mysite.core.services.WorkfrontJsonConverterService.ConversionResult;
import com.mysite.core.testcontext.AppAemContext;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

@ExtendWith(AemContextExtension.class)
class WorkfrontJsonConverterSchedulerTest {

    private static final String ROOT = "/content/dam/mysite/workfront-reports";

    private final AemContext context = AppAemContext.newAemContext();

    private WorkfrontJsonConverterScheduler scheduler;
    private WorkfrontJsonConverterService converterService;

    @BeforeEach
    void setup() throws Exception {
        scheduler = new WorkfrontJsonConverterScheduler();
        converterService = mock(WorkfrontJsonConverterService.class);

        final ResourceResolverFactory rrf = mock(ResourceResolverFactory.class);
        when(rrf.getServiceResourceResolver(any())).thenReturn(context.resourceResolver());

        setField(scheduler, "resolverFactory", rrf);
        setField(scheduler, "converterService", converterService);

        final WorkfrontJsonConverterScheduler.Config cfg = mock(WorkfrontJsonConverterScheduler.Config.class);
        when(cfg.reportsRoot()).thenReturn(ROOT);
        when(cfg.maxAttempts()).thenReturn(3);
        when(cfg.retryBackoffSeconds()).thenReturn(0);
        when(cfg.pauseBetweenFilesSeconds()).thenReturn(0);
        scheduler.activate(cfg);
    }

    @Test
    void convertsEachCsvIntoItsReportJsonFolder() throws Exception {
        context.build()
                .resource(ROOT + "/rep-a/csv/rep-a-natwest.csv")
                .resource(ROOT + "/rep-a/csv/notes.txt")           // ignored (not .csv)
                .resource(ROOT + "/rep-a/json")
                .resource(ROOT + "/rep-b/csv/rep-b-rbs.csv")
                .resource(ROOT + "/rep-b/json")
                .resource(ROOT + "/no-csv-folder/other")           // no csv child -> skipped
                .commit();
        when(converterService.convert(any(Resource.class), any(String.class)))
                .thenReturn(ConversionResult.success("x.json", 3));

        scheduler.run();

        verify(converterService).convert(any(Resource.class), eq(ROOT + "/rep-a/json"));
        verify(converterService).convert(any(Resource.class), eq(ROOT + "/rep-b/json"));
        verify(converterService, times(2)).convert(any(Resource.class), any(String.class));
    }

    @Test
    void retriesThenGivesUpOnFailure() throws Exception {
        context.build()
                .resource(ROOT + "/rep-a/csv/rep-a-natwest.csv")
                .resource(ROOT + "/rep-a/json")
                .commit();
        when(converterService.convert(any(Resource.class), any(String.class)))
                .thenReturn(ConversionResult.failure("x.json", "boom"));

        scheduler.run();

        // maxAttempts = 3 attempts for the single file.
        verify(converterService, times(3)).convert(any(Resource.class), any(String.class));
    }

    @Test
    void convertThrowingIsRetriedAndIsolated() throws Exception {
        context.build().resource(ROOT + "/rep-a/csv/rep-a-natwest.csv").resource(ROOT + "/rep-a/json").commit();
        when(converterService.convert(any(Resource.class), any(String.class)))
                .thenThrow(new RuntimeException("boom"));
        scheduler.run();
        verify(converterService, times(3)).convert(any(Resource.class), any(String.class));
    }

    @Test
    void missingRootDoesNothing() throws Exception {
        setField(scheduler, "reportsRoot", "/content/dam/mysite/does-not-exist");
        scheduler.run();
        verify(converterService, never()).convert(any(Resource.class), any(String.class));
    }

    @Test
    void loginFailureIsHandledCleanly() throws Exception {
        final ResourceResolverFactory rrf = mock(ResourceResolverFactory.class);
        when(rrf.getServiceResourceResolver(any())).thenThrow(new LoginException("no user"));
        setField(scheduler, "resolverFactory", rrf);
        scheduler.run();
        verify(converterService, never()).convert(any(Resource.class), any(String.class));
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        final Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
