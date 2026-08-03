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

import java.io.IOException;

import javax.json.Json;
import javax.json.JsonObjectBuilder;
import javax.servlet.Servlet;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mysite.core.models.report.ReportConfigConstants;
import com.mysite.core.models.report.UnpublishedReportConfig;
import com.mysite.core.models.report.UnpublishedReportConfigReader;
import com.mysite.core.services.UnpublishedPagesReportService;
import com.mysite.core.services.UnpublishedPagesReportService.ReportResult;

/**
 * On-demand trigger for the unpublished-pages report. Reads the configuration
 * of a single component (identified by the {@code configPath} POST parameter)
 * using the requesting author's resolver, then delegates generation to the
 * shared {@link UnpublishedPagesReportService}. Returns a small JSON summary.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/mysite/unpublishedreport",
                "sling.servlet.methods=POST"
        })
public class UnpublishedReportRunServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(UnpublishedReportRunServlet.class);

    @Reference
    private transient UnpublishedPagesReportService reportService;

    @Override
    protected void doPost(final SlingHttpServletRequest request, final SlingHttpServletResponse response)
            throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        final String configPath = StringUtils.trimToNull(request.getParameter("configPath"));
        if (configPath == null) {
            writeError(response, SlingHttpServletResponse.SC_BAD_REQUEST, "Missing 'configPath' parameter");
            return;
        }

        final Resource component = request.getResourceResolver().getResource(configPath);
        if (component == null) {
            writeError(response, SlingHttpServletResponse.SC_NOT_FOUND,
                    "No resource at configPath: " + configPath);
            return;
        }
        final String resourceType = component.getResourceType();
        if (!ReportConfigConstants.RESOURCE_TYPE.equals(resourceType)) {
            writeError(response, SlingHttpServletResponse.SC_BAD_REQUEST,
                    "Resource at configPath is not an Unpublished Pages Report component: " + configPath);
            return;
        }

        try {
            final UnpublishedReportConfig config = UnpublishedReportConfigReader.readOne(component);
            final ReportResult result = reportService.generateReport(config);

            final JsonObjectBuilder json = Json.createObjectBuilder()
                    .add("success", result.isSuccess())
                    .add("csvPath", StringUtils.defaultString(result.getCsvPath()))
                    .add("rowsReported", result.getRowsReported());
            if (!result.isSuccess()) {
                json.add("error", StringUtils.defaultString(result.getErrorMessage()));
                response.setStatus(SlingHttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
            response.getWriter().write(json.build().toString());
        } catch (final Exception e) {
            LOG.error("On-demand unpublished-pages report failed for {}", configPath, e);
            writeError(response, SlingHttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Report generation failed: " + e.getMessage());
        }
    }

    private void writeError(final SlingHttpServletResponse response, final int status, final String message)
            throws IOException {
        response.setStatus(status);
        response.getWriter().write(Json.createObjectBuilder()
                .add("success", false)
                .add("error", message)
                .build().toString());
    }
}
