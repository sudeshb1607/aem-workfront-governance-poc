package com.mysite.core.servlets;

import java.io.IOException;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
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

import com.mysite.core.reports.ReportDefinition;
import com.mysite.core.reports.ReportDefinitionReader;
import com.mysite.core.reports.ReportType;
import com.mysite.core.services.ReportGeneratorService;
import com.mysite.core.services.ReportGeneratorService.ReportRunResult;

/**
 * On-demand trigger for any of the five reports. Reads the configuration of a
 * single component (identified by the {@code configPath} POST parameter) using
 * the requesting author's resolver, then delegates generation to the shared
 * {@link ReportGeneratorService}. Returns a small JSON summary.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/mysite/report/run",
                "sling.servlet.methods=POST"
        })
public class ReportRunServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(ReportRunServlet.class);

    @Reference
    private transient ReportGeneratorService reportService;

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
            writeError(response, SlingHttpServletResponse.SC_NOT_FOUND, "No resource at configPath: " + configPath);
            return;
        }
        if (ReportType.fromResourceType(component.getResourceType()) == null) {
            writeError(response, SlingHttpServletResponse.SC_BAD_REQUEST,
                    "Resource at configPath is not a report component: " + configPath);
            return;
        }

        try {
            final ReportDefinition definition = ReportDefinitionReader.readOne(component);
            final ReportRunResult result = reportService.generate(definition);

            final JsonArrayBuilder paths = Json.createArrayBuilder();
            result.getCsvPaths().forEach(paths::add);
            final JsonObjectBuilder json = Json.createObjectBuilder()
                    .add("success", result.isSuccess())
                    .add("reportId", definition.getReportId())
                    .add("csvPaths", paths)
                    .add("rows", result.getTotalRows());
            if (!result.isSuccess()) {
                json.add("error", StringUtils.defaultString(result.getErrorMessage()));
                response.setStatus(SlingHttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
            response.getWriter().write(json.build().toString());
        } catch (final Exception e) {
            LOG.error("On-demand report failed for {}", configPath, e);
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
