package com.mysite.core.schedulers;

import static org.mockito.ArgumentMatchers.any;
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

import com.mysite.core.services.WorkfrontWebhookService;
import com.mysite.core.services.WorkfrontWebhookService.SendResult;
import com.mysite.core.testcontext.AppAemContext;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

@ExtendWith(AemContextExtension.class)
class WorkfrontWebhookSchedulerTest {

    private static final String ROOT = "/content/dam/mysite/workfront-reports";

    private final AemContext context = AppAemContext.newAemContext();

    private WorkfrontWebhookScheduler scheduler;
    private WorkfrontWebhookService webhookService;

    @BeforeEach
    void setup() throws Exception {
        scheduler = new WorkfrontWebhookScheduler();
        webhookService = mock(WorkfrontWebhookService.class);

        final ResourceResolverFactory rrf = mock(ResourceResolverFactory.class);
        when(rrf.getServiceResourceResolver(any())).thenReturn(context.resourceResolver());

        setField(scheduler, "resolverFactory", rrf);
        setField(scheduler, "webhookService", webhookService);

        final WorkfrontWebhookScheduler.Config cfg = mock(WorkfrontWebhookScheduler.Config.class);
        when(cfg.reportsRoot()).thenReturn(ROOT);
        when(cfg.maxAttempts()).thenReturn(2);
        when(cfg.retryBackoffSeconds()).thenReturn(0);
        when(cfg.pauseBetweenFilesSeconds()).thenReturn(0);
        scheduler.activate(cfg);
    }

    @Test
    void sendsEachJsonAcrossReportFolders() throws Exception {
        context.build()
                .resource(ROOT + "/rep-a/json/rep-a-natwest.json")
                .resource(ROOT + "/rep-a/json/readme.md")          // ignored
                .resource(ROOT + "/rep-b/json/rep-b-rbs.json")
                .resource(ROOT + "/no-json-folder/csv")            // no json child -> skipped
                .commit();
        when(webhookService.send(any(Resource.class))).thenReturn(SendResult.success(200));

        scheduler.run();

        verify(webhookService, times(2)).send(any(Resource.class));
    }

    @Test
    void retriesThenGivesUpOnFailure() throws Exception {
        context.build().resource(ROOT + "/rep-a/json/rep-a-natwest.json").commit();
        when(webhookService.send(any(Resource.class))).thenReturn(SendResult.failure(500, "server error"));

        scheduler.run();

        verify(webhookService, times(2)).send(any(Resource.class)); // maxAttempts = 2
    }

    @Test
    void sendThrowingIsRetriedAndIsolated() throws Exception {
        context.build().resource(ROOT + "/rep-a/json/rep-a-natwest.json").commit();
        when(webhookService.send(any(Resource.class))).thenThrow(new RuntimeException("boom"));
        scheduler.run();
        verify(webhookService, times(2)).send(any(Resource.class));
    }

    @Test
    void missingRootDoesNothing() throws Exception {
        setField(scheduler, "reportsRoot", "/content/dam/mysite/does-not-exist");
        scheduler.run();
        verify(webhookService, never()).send(any(Resource.class));
    }

    @Test
    void loginFailureIsHandledCleanly() throws Exception {
        final ResourceResolverFactory rrf = mock(ResourceResolverFactory.class);
        when(rrf.getServiceResourceResolver(any())).thenThrow(new LoginException("no user"));
        setField(scheduler, "resolverFactory", rrf);
        scheduler.run();
        verify(webhookService, never()).send(any(Resource.class));
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        final Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
