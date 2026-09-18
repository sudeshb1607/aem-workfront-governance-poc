package com.mysite.core.servlets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.Servlet;

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

import com.adobe.granite.ui.components.ds.DataSource;
import com.adobe.granite.ui.components.ds.SimpleDataSource;
import com.adobe.granite.ui.components.ds.ValueMapResource;

/**
 * Granite UI DataSource for the single content-root dropdown on each report
 * brand row.
 *
 *   mysite/datasource/report-brand-roots  → one option per site root under /content
 *
 * Each option is {@code value=<path>}, {@code text=<jcr:title || node name>}. The
 * author picks exactly one site root per brand (no free typing, no multifield).
 * Mirrors {@link ContentGovernanceOptionsDataSourceServlet}.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.resourceTypes=mysite/datasource/report-brand-roots",
                "sling.servlet.methods=GET"
        }
)
public class BrandRootsDataSourceServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(BrandRootsDataSourceServlet.class);

    private static final String CONTENT_ROOT = "/content";
    private static final String PN_TITLE = "jcr:title";

    @Override
    protected void doGet(final SlingHttpServletRequest request, final SlingHttpServletResponse response) {
        final ResourceResolver resolver = request.getResourceResolver();
        final List<Resource> entries = new ArrayList<>();

        try {
            final Resource content = resolver.getResource(CONTENT_ROOT);
            if (content != null) {
                for (final Resource child : content.getChildren()) {
                    entries.add(toOption(resolver, child));
                }
            } else {
                LOG.warn("Could not resolve {} for report brand-roots datasource", CONTENT_ROOT);
            }
        } catch (final Exception e) {
            LOG.error("Error building report brand-roots datasource", e);
        }

        request.setAttribute(DataSource.class.getName(), new SimpleDataSource(entries.iterator()));
    }

    private Resource toOption(final ResourceResolver resolver, final Resource siteRoot) {
        final ValueMap vm = siteRoot.getValueMap();
        final String title = StringUtils.defaultIfBlank(vm.get(PN_TITLE, String.class), siteRoot.getName());
        final Map<String, Object> props = new HashMap<>();
        props.put("value", siteRoot.getPath());
        props.put("text", title);
        return new ValueMapResource(resolver, new ResourceMetadata(),
                "nt:unstructured", new ValueMapDecorator(props));
    }
}
