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
package com.mysite.core.servlets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collections;

import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.mysite.core.models.report.ReportConfigConstants;
import com.mysite.core.services.UnpublishedPagesReportService;
import com.mysite.core.services.UnpublishedPagesReportService.ReportResult;
import com.mysite.core.testcontext.AppAemContext;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

@ExtendWith(AemContextExtension.class)
class UnpublishedReportRunServletTest {

    private static final String COMPONENT_PATH = "/content/config/jcr:content/report";

    private final AemContext context = AppAemContext.newAemContext();

    private UnpublishedReportRunServlet servlet;
    private UnpublishedPagesReportService reportService;

    @BeforeEach
    void setup() throws Exception {
        servlet = new UnpublishedReportRunServlet();
        reportService = mock(UnpublishedPagesReportService.class);
        setField(servlet, "reportService", reportService);
    }

    @Test
    void generatesReportForValidComponent() throws Exception {
        context.create().resource(COMPONENT_PATH,
                "sling:resourceType", ReportConfigConstants.RESOURCE_TYPE);
        when(reportService.generateReport(any()))
                .thenReturn(ReportResult.success("/content/dam/mysite/reports/unpublished-pages-20260731.csv", 7));

        context.request().setParameterMap(Collections.singletonMap("configPath", COMPONENT_PATH));
        servlet.doPost(context.request(), context.response());

        assertEquals(HttpServletResponse.SC_OK, context.response().getStatus());
        final String body = context.response().getOutputAsString();
        assertTrue(body.contains("\"success\":true"), body);
        assertTrue(body.contains("unpublished-pages-20260731.csv"), body);
        assertTrue(body.contains("\"rowsReported\":7"), body);
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
    void rejectsWrongResourceType() throws Exception {
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
