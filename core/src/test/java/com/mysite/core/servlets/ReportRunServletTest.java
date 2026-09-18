package com.mysite.core.servlets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;

import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.mysite.core.reports.ReportType;
import com.mysite.core.services.ReportGeneratorService;
import com.mysite.core.services.ReportGeneratorService.ReportRunResult;
import com.mysite.core.testcontext.AppAemContext;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

@ExtendWith(AemContextExtension.class)
class ReportRunServletTest {

    private static final String COMPONENT_PATH = "/content/config/jcr:content/report";

    private final AemContext context = AppAemContext.newAemContext();

    private ReportRunServlet servlet;
    private ReportGeneratorService reportService;

    @BeforeEach
    void setup() throws Exception {
        servlet = new ReportRunServlet();
        reportService = mock(ReportGeneratorService.class);
        setField(servlet, "reportService", reportService);
    }

    @Test
    void generatesReportForValidComponent() throws Exception {
        context.create().resource(COMPONENT_PATH,
                "sling:resourceType", ReportType.EXPIRING_PUBLISHED.getResourceType());
        when(reportService.generate(any(), org.mockito.ArgumentMatchers.eq(false))).thenReturn(ReportRunResult.success(
                Arrays.asList("/content/dam/mysite/workfront-reports/expiring-published/csv/expiring-published-natwest.csv"),
                12));

        context.request().setParameterMap(Collections.singletonMap("configPath", COMPONENT_PATH));
        servlet.doPost(context.request(), context.response());

        assertEquals(HttpServletResponse.SC_OK, context.response().getStatus());
        final String body = context.response().getOutputAsString();
        assertTrue(body.contains("\"success\":true"), body);
        assertTrue(body.contains("expiring-published-natwest.csv"), body);
        assertTrue(body.contains("\"rows\":12"), body);
        assertTrue(body.contains("\"reportId\":\"expiring-published\""), body);
    }

    @Test
    void reportsFailureAsInternalError() throws Exception {
        context.create().resource(COMPONENT_PATH,
                "sling:resourceType", ReportType.ARCHIVE_AGED.getResourceType());
        when(reportService.generate(any(), org.mockito.ArgumentMatchers.eq(false))).thenReturn(ReportRunResult.failure("disk full"));

        context.request().setParameterMap(Collections.singletonMap("configPath", COMPONENT_PATH));
        servlet.doPost(context.request(), context.response());

        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, context.response().getStatus());
        assertTrue(context.response().getOutputAsString().contains("disk full"));
    }

    @Test
    void handlesServiceExceptionAsInternalError() throws Exception {
        context.create().resource(COMPONENT_PATH,
                "sling:resourceType", ReportType.ALL_LIVE.getResourceType());
        when(reportService.generate(any(), org.mockito.ArgumentMatchers.eq(false))).thenThrow(new RuntimeException("kaboom"));

        context.request().setParameterMap(Collections.singletonMap("configPath", COMPONENT_PATH));
        servlet.doPost(context.request(), context.response());

        assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, context.response().getStatus());
        assertTrue(context.response().getOutputAsString().contains("Report generation failed"));
    }

    @Test
    void rejectsMissingConfigPath() throws Exception {
        servlet.doPost(context.request(), context.response());
        assertEquals(HttpServletResponse.SC_BAD_REQUEST, context.response().getStatus());
        verifyNoInteractions(reportService);
    }

    @Test
    void rejectsUnknownConfigPath() throws Exception {
        context.request().setParameterMap(Collections.singletonMap("configPath", "/content/does/not/exist"));
        servlet.doPost(context.request(), context.response());
        assertEquals(HttpServletResponse.SC_NOT_FOUND, context.response().getStatus());
        verifyNoInteractions(reportService);
    }

    @Test
    void rejectsNonReportResource() throws Exception {
        context.create().resource(COMPONENT_PATH, "sling:resourceType", "mysite/components/text");
        context.request().setParameterMap(Collections.singletonMap("configPath", COMPONENT_PATH));
        servlet.doPost(context.request(), context.response());
        assertEquals(HttpServletResponse.SC_BAD_REQUEST, context.response().getStatus());
        verifyNoInteractions(reportService);
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        final Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
