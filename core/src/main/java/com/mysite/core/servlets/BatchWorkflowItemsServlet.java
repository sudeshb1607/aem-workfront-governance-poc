package com.mysite.core.servlets;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import javax.servlet.Servlet;
import java.io.IOException;

/**
 * C2 - Resolves the selected {@code item} paths to {title, path} rows consumed by
 * the wizard's Scope step (see clientlib {@code batchworkflow.js}). Keeping the
 * resolution server-side means the Scope list shows friendly asset/CF titles.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/mysite/batchworkflow/items",
                "sling.servlet.methods=GET"
        }
)
public class BatchWorkflowItemsServlet extends SlingSafeMethodsServlet {

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        final ResourceResolver resolver = request.getResourceResolver();
        final String[] items = request.getParameterValues("item");

        final JsonArrayBuilder rows = Json.createArrayBuilder();
        if (items != null) {
            for (String path : items) {
                if (path == null || path.isEmpty()) {
                    continue;
                }
                final JsonObjectBuilder row = Json.createObjectBuilder()
                        .add("path", path)
                        .add("title", resolveTitle(resolver, path));
                rows.add(row);
            }
        }

        final JsonObjectBuilder result = Json.createObjectBuilder().add("items", rows);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(result.build().toString());
    }

    private String resolveTitle(ResourceResolver resolver, String path) {
        final Resource resource = resolver.getResource(path);
        if (resource == null) {
            return path.substring(path.lastIndexOf('/') + 1);
        }
        // Assets / Content Fragments store the title under jcr:content/metadata/dc:title
        final Resource metadata = resolver.getResource(path + "/jcr:content/metadata");
        if (metadata != null) {
            final String dcTitle = metadata.getValueMap().get("dc:title", String.class);
            if (dcTitle != null && !dcTitle.isEmpty()) {
                return dcTitle;
            }
        }
        final Resource content = resource.getChild("jcr:content");
        if (content != null) {
            final ValueMap vm = content.getValueMap();
            final String jcrTitle = vm.get("jcr:title", String.class);
            if (jcrTitle != null && !jcrTitle.isEmpty()) {
                return jcrTitle;
            }
        }
        return resource.getName();
    }
}
