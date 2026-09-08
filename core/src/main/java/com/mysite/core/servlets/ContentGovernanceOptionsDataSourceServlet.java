package com.mysite.core.servlets;

import com.adobe.granite.ui.components.ds.DataSource;
import com.adobe.granite.ui.components.ds.SimpleDataSource;
import com.adobe.granite.ui.components.ds.ValueMapResource;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceMetadata;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.NodeIterator;
import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;
import javax.servlet.Servlet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Granite UI DataSource that populates the Franchise select field in the
 * Content Governance page-properties tab.
 *
 *   mysite/datasource/content-governance-franchise  → franchiseOptions nodes
 *
 * Options are authored on the {@code content-governance-config} component
 * (mysite/components/content-governance-config) placed anywhere under the
 * site root. Each composite multifield row has a {@code key} (stored in page
 * properties on save) and a {@code label} (displayed to authors).
 *
 * Page Owners are now scoped per Franchise (a nested multifield under each
 * franchiseOptions row) and are served dynamically by
 * {@link ContentGovernancePageOwnersServlet}. The {@code deriveSiteRoot} and
 * {@code findConfigComponent} helpers are {@code static} so that servlet can
 * reuse them without duplication.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.resourceTypes=mysite/datasource/content-governance-franchise",
                "sling.servlet.methods=GET"
        }
)
public class ContentGovernanceOptionsDataSourceServlet extends SlingSafeMethodsServlet {

    private static final Logger LOG = LoggerFactory.getLogger(ContentGovernanceOptionsDataSourceServlet.class);

    private static final String CONFIG_RESOURCE_TYPE       = "mysite/components/content-governance-config";
    private static final String PN_FRANCHISE_OPTIONS       = "franchiseOptions";

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) {
        final ResourceResolver resolver = request.getResourceResolver();
        final List<Resource> entries    = new ArrayList<>();

        try {
            // Page properties passes the edited page as the "item" query parameter.
            // Fall back to the suffix for other dialog contexts.
            String pagePath = request.getParameter("item");
            if (StringUtils.isBlank(pagePath)) {
                pagePath = request.getRequestPathInfo().getSuffix();
            }
            if (StringUtils.isBlank(pagePath)) {
                LOG.warn("Cannot determine current page path for content-governance datasource");
            } else {
                final String siteRoot = deriveSiteRoot(pagePath);
                final Resource configComponent = findConfigComponent(resolver, siteRoot);
                if (configComponent != null) {
                    buildEntries(resolver, configComponent, PN_FRANCHISE_OPTIONS, entries);
                } else {
                    LOG.debug("No content-governance-config component found under {}", siteRoot);
                }
            }
        } catch (Exception e) {
            LOG.error("Error building content-governance datasource", e);
        }

        request.setAttribute(DataSource.class.getName(), new SimpleDataSource(entries.iterator()));
    }

    /**
     * Derives the site root as the first three path segments:
     * /content/mysite/us/en/some/page → /content/mysite/us
     */
    static String deriveSiteRoot(String pagePath) {
        final String[] segments = pagePath.split("/");
        if (segments.length >= 4) {
            return "/" + segments[1] + "/" + segments[2] + "/" + segments[3];
        }
        return "/content";
    }

    /**
     * Finds the first {@code content-governance-config} component anywhere under
     * {@code siteRoot} using a JCR SQL-2 query.
     */
    static Resource findConfigComponent(ResourceResolver resolver, String siteRoot) {
        try {
            final Session session = resolver.adaptTo(Session.class);
            if (session == null) {
                return null;
            }
            final QueryManager qm = session.getWorkspace().getQueryManager();
            final String sql = "SELECT * FROM [nt:unstructured] AS s "
                    + "WHERE ISDESCENDANTNODE(s, '" + siteRoot + "') "
                    + "AND s.[sling:resourceType] = '" + CONFIG_RESOURCE_TYPE + "'";
            final QueryResult result = qm.createQuery(sql, Query.JCR_SQL2).execute();
            final NodeIterator nodes = result.getNodes();
            if (nodes.hasNext()) {
                final String path = nodes.nextNode().getPath();
                LOG.debug("Found content-governance-config at {}", path);
                return resolver.getResource(path);
            }
        } catch (Exception e) {
            LOG.error("JCR query for content-governance-config failed under {}", siteRoot, e);
        }
        return null;
    }

    /**
     * Reads composite multifield child nodes from the config component.
     * Each child node has {@code key} (→ select value) and {@code label} (→ select text).
     */
    private void buildEntries(ResourceResolver resolver, Resource configComponent,
                              String multifieldName, List<Resource> entries) {
        final Resource multifield = configComponent.getChild(multifieldName);
        if (multifield == null) {
            LOG.debug("No '{}' node under {}", multifieldName, configComponent.getPath());
            return;
        }

        for (Resource row : multifield.getChildren()) {
            final ValueMap vm  = row.getValueMap();
            final String key   = StringUtils.trimToNull(vm.get("key",   String.class));
            final String label = StringUtils.trimToNull(vm.get("label", String.class));

            if (key == null || label == null) {
                LOG.warn("Skipping incomplete governance option row at {} (key='{}', label='{}')",
                        row.getPath(), key, label);
                continue;
            }

            final Map<String, Object> props = new HashMap<>();
            props.put("value", key);
            props.put("text",  label);
            entries.add(new ValueMapResource(resolver, new ResourceMetadata(),
                    "nt:unstructured", new ValueMapDecorator(props)));
        }
    }
}
