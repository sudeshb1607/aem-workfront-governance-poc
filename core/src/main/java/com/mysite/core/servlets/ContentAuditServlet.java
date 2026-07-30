package com.mysite.core.servlets;

import com.day.cq.search.*;
import com.day.cq.search.result.Hit;
import com.day.cq.search.result.SearchResult;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.apache.sling.api.resource.*;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import javax.jcr.Session;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import javax.servlet.Servlet;
import java.io.IOException;
import java.util.*;

@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/content-audit/pages",
                "sling.servlet.methods=GET"
        }
)
public class ContentAuditServlet extends SlingSafeMethodsServlet {

    @Reference
    private QueryBuilder queryBuilder;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws IOException {

        int pageSize = Integer.parseInt(Optional.ofNullable(request.getParameter("pageSize")).orElse("500"));
        int offset = Integer.parseInt(Optional.ofNullable(request.getParameter("offset")).orElse("0"));

        ResourceResolver resolver = request.getResourceResolver();
        Session session = resolver.adaptTo(Session.class);

        Map<String, String> params = new HashMap<>();
        params.put("type", "cq:Page");
        params.put("path", "/content");
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

                String path = page.getPath();
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
                        .add("path", path)
                        .add("title", title)
                        .add("lastModified", lastModified)
                        .add("lastModifiedBy", lastModifiedBy)
                        .add("lastReplicated", lastReplicated)
                        .add("owner", owner)
                        .add("franchise", franchise);

                pagesArray.add(pageJson);

            } catch (Exception ignored) {
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
