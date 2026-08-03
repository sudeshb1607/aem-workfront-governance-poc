package com.mysite.core.servlets;

import com.day.cq.search.PredicateGroup;
import com.day.cq.search.Query;
import com.day.cq.search.QueryBuilder;
import com.day.cq.search.result.Hit;
import com.day.cq.search.result.SearchResult;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Session;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import javax.servlet.Servlet;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/content-audit/pages",
                "sling.servlet.methods=GET"
        }
)
public class ContentAuditServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(ContentAuditServlet.class);

    /** Only subtrees of this root may be audited, so the endpoint cannot enumerate /home, /var, etc. */
    private static final String ALLOWED_ROOT = "/content";
    private static final int DEFAULT_PAGE_SIZE = 500;
    private static final int MAX_PAGE_SIZE = 5000;

    @Reference
    private transient QueryBuilder queryBuilder;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws IOException {

        // Bounded, non-throwing parsing: bad input falls back to defaults instead of a 500.
        int pageSize = Math.min(MAX_PAGE_SIZE,
                Math.max(1, NumberUtils.toInt(request.getParameter("pageSize"), DEFAULT_PAGE_SIZE)));
        int offset = Math.max(0, NumberUtils.toInt(request.getParameter("offset"), 0));

        // Configurable scan root, constrained to the allowed content tree.
        String path = StringUtils.defaultIfBlank(request.getParameter("path"), ALLOWED_ROOT);
        if (!path.equals(ALLOWED_ROOT) && !path.startsWith(ALLOWED_ROOT + "/")) {
            response.setStatus(SlingHttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write(Json.createObjectBuilder()
                    .add("error", "path must be under " + ALLOWED_ROOT)
                    .build().toString());
            return;
        }

        ResourceResolver resolver = request.getResourceResolver();
        Session session = resolver.adaptTo(Session.class);

        Map<String, String> params = new HashMap<>();
        params.put("type", "cq:Page");
        params.put("path", path);
        params.put("p.limit", String.valueOf(pageSize));
        params.put("p.offset", String.valueOf(offset));

        Query query = queryBuilder.createQuery(PredicateGroup.create(params), session);
        SearchResult result = query.getResult();

        JsonArrayBuilder pagesArray = Json.createArrayBuilder();

        for (Hit hit : result.getHits()) {
            try {
                Resource page = hit.getResource();
                Resource content = page.getChild("jcr:content");
                if (content == null) continue;

                ValueMap vm = content.getValueMap();

                String pagePath = page.getPath();
                String title = vm.get("jcr:title", "");
                String lastModified = vm.get("cq:lastModified", "").toString();
                String lastModifiedBy = vm.get("cq:lastModifiedBy", "admin");
                String lastReplicated = vm.get("cq:lastReplicated", "").toString();

                // OWNER LOGIC
                String owner = vm.get("contentOwner", String.class);
                if (owner == null || owner.isEmpty()) {
                    owner = lastModifiedBy != null ? lastModifiedBy : "admin";
                }

                // FRANCHISE
                String franchise = vm.get("franchise", "Unassigned");

                JsonObjectBuilder pageJson = Json.createObjectBuilder()
                        .add("path", pagePath)
                        .add("title", title)
                        .add("lastModified", lastModified)
                        .add("lastModifiedBy", lastModifiedBy)
                        .add("lastReplicated", lastReplicated)
                        .add("owner", owner)
                        .add("franchise", franchise);

                pagesArray.add(pageJson);

            } catch (Exception e) {
                LOG.debug("Skipping page while building content audit", e);
            }
        }

        JsonObjectBuilder responseJson = Json.createObjectBuilder()
                .add("total", result.getTotalMatches())
                .add("offset", offset)
                .add("pages", pagesArray);

        response.setContentType("application/json");
        response.getWriter().write(responseJson.build().toString());
    }
}
