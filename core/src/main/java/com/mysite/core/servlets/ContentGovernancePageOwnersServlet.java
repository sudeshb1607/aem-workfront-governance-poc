package com.mysite.core.servlets;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import javax.servlet.Servlet;
import java.io.IOException;

import static com.mysite.core.servlets.ContentGovernanceOptionsDataSourceServlet.deriveSiteRoot;
import static com.mysite.core.servlets.ContentGovernanceOptionsDataSourceServlet.findConfigComponent;

/**
 * Cascade endpoint that returns the Page Owner options for a given Franchise.
 *
 * The Content Governance page-properties tab uses a client-side clientlib
 * (cq.authoring.dialog) to re-populate the Page Owners select whenever the
 * Franchise select changes. That clientlib GETs:
 *
 *   /bin/mysite/content-governance/page-owners.json?item=&lt;pagePath&gt;&franchise=&lt;key&gt;
 *
 * Request parameters:
 *   item      — the edited page path (used to derive the site root and locate config)
 *   franchise — the selected franchise key
 *
 * Response: a JSON array of {@code [{"value":&lt;key&gt;,"text":&lt;label&gt;}, ...]}
 * built from the nested {@code pageOwners} multifield under the matching
 * {@code franchiseOptions} row on the {@code content-governance-config} component.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/mysite/content-governance/page-owners",
                "sling.servlet.methods=GET",
                "sling.servlet.extensions=json"
        }
)
public class ContentGovernancePageOwnersServlet extends SlingSafeMethodsServlet {

    private static final Logger LOG = LoggerFactory.getLogger(ContentGovernancePageOwnersServlet.class);

    private static final String PN_FRANCHISE_OPTIONS = "franchiseOptions";
    private static final String PN_PAGE_OWNERS       = "pageOwners";

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        final ResourceResolver resolver = request.getResourceResolver();
        final String pagePath  = StringUtils.trimToNull(request.getParameter("item"));
        final String franchise = StringUtils.trimToNull(request.getParameter("franchise"));

        final JsonArrayBuilder owners = Json.createArrayBuilder();
        try {
            if (pagePath == null || franchise == null) {
                LOG.warn("Missing required parameter(s): item='{}', franchise='{}'", pagePath, franchise);
            } else {
                final String siteRoot = deriveSiteRoot(pagePath);
                final Resource configComponent = findConfigComponent(resolver, siteRoot);
                if (configComponent == null) {
                    LOG.debug("No content-governance-config component found under {}", siteRoot);
                } else {
                    buildOwners(configComponent, franchise, owners);
                }
            }
        } catch (Exception e) {
            LOG.error("Error building content-governance page owners for franchise '{}'", franchise, e);
        }

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(owners.build().toString());
    }

    /**
     * Navigates {@code franchiseOptions} → the row whose {@code key} equals the given
     * franchise → its nested {@code pageOwners} rows, emitting each as
     * {@code {"value":key,"text":label}}. Rows missing a key or label are skipped
     * (mirrors the datasource validation style).
     */
    private void buildOwners(Resource configComponent, String franchise, JsonArrayBuilder owners) {
        final Resource franchiseOptions = configComponent.getChild(PN_FRANCHISE_OPTIONS);
        if (franchiseOptions == null) {
            LOG.debug("No '{}' node under {}", PN_FRANCHISE_OPTIONS, configComponent.getPath());
            return;
        }

        Resource franchiseRow = null;
        for (Resource row : franchiseOptions.getChildren()) {
            if (franchise.equals(StringUtils.trimToNull(row.getValueMap().get("key", String.class)))) {
                franchiseRow = row;
                break;
            }
        }
        if (franchiseRow == null) {
            LOG.debug("No franchise row with key '{}' under {}", franchise, franchiseOptions.getPath());
            return;
        }

        final Resource pageOwners = franchiseRow.getChild(PN_PAGE_OWNERS);
        if (pageOwners == null) {
            LOG.debug("No '{}' node under franchise row {}", PN_PAGE_OWNERS, franchiseRow.getPath());
            return;
        }

        for (Resource row : pageOwners.getChildren()) {
            final ValueMap vm  = row.getValueMap();
            final String key   = StringUtils.trimToNull(vm.get("key",   String.class));
            final String label = StringUtils.trimToNull(vm.get("label", String.class));

            if (key == null || label == null) {
                LOG.warn("Skipping incomplete page owner row at {} (key='{}', label='{}')",
                        row.getPath(), key, label);
                continue;
            }

            final JsonObjectBuilder owner = Json.createObjectBuilder()
                    .add("value", key)
                    .add("text",  label);
            owners.add(owner);
        }
    }
}
