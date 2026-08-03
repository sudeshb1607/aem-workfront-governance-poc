package com.mysite.core.servlets;

import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.Workflow;
import com.adobe.granite.workflow.exec.WorkflowData;
import com.adobe.granite.workflow.model.WorkflowModel;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.Session;
import javax.json.Json;
import javax.servlet.Servlet;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * C3 - Custom submit endpoint for the batch-workflow wizard.
 *
 * Behaves like the OOTB Pages bulk-workflow submit but is wholly owned by the
 * project: it builds a workflow-package node covering ALL selected items and
 * starts exactly ONE workflow instance over that package. Package-aware publish
 * steps (ActivatePageProcess/ReplicatePageProcess) then expand the package and
 * activate every item inside the single instance -- Pages-like behaviour for CFs.
 *
 * Follows the project's path-servlet convention (see {@code ContentAuditServlet}).
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.paths=/bin/mysite/batchworkflow",
                "sling.servlet.methods=POST"
        }
)
public class BatchWorkflowServlet extends SlingAllMethodsServlet {

    private static final Logger LOG = LoggerFactory.getLogger(BatchWorkflowServlet.class);

    private static final String PACKAGES_ROOT = "/var/workflow/packages";
    private static final String COLLECTION_RESOURCE_TYPE = "cq/workflow/components/collection/page";
    private static final String PAYLOAD_TYPE = "JCR_PATH";

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        final String modelId = request.getParameter("model");
        final String workflowTitle = request.getParameter("workflowTitle");
        final boolean keepPackage = Boolean.parseBoolean(request.getParameter("keepPackage"));
        final String packageTitle = request.getParameter("packageTitle");
        final String[] items = request.getParameterValues("item");

        if (StringUtils.isBlank(modelId)) {
            sendError(response, SlingHttpServletResponse.SC_BAD_REQUEST, "Missing 'model' parameter");
            return;
        }
        if (items == null || items.length == 0) {
            sendError(response, SlingHttpServletResponse.SC_BAD_REQUEST, "No items selected");
            return;
        }

        final ResourceResolver resolver = request.getResourceResolver();
        final Session session = resolver.adaptTo(Session.class);
        if (session == null) {
            sendError(response, SlingHttpServletResponse.SC_INTERNAL_SERVER_ERROR, "No JCR session available");
            return;
        }

        try {
            final String packagePath = buildWorkflowPackage(session, items, packageTitle);

            final WorkflowSession wfSession = resolver.adaptTo(WorkflowSession.class);
            if (wfSession == null) {
                sendError(response, SlingHttpServletResponse.SC_INTERNAL_SERVER_ERROR, "No WorkflowSession available");
                return;
            }

            final WorkflowModel model = wfSession.getModel(modelId);
            final WorkflowData data = wfSession.newWorkflowData(PAYLOAD_TYPE, packagePath);

            final Map<String, Object> metaData = new HashMap<>();
            if (StringUtils.isNotBlank(workflowTitle)) {
                metaData.put("workflowTitle", workflowTitle);
            }
            metaData.put("keepPackage", keepPackage);

            final Workflow workflow = wfSession.startWorkflow(model, data, metaData);

            LOG.info("Batch workflow started: instance={} model={} package={} items={}",
                    workflow != null ? workflow.getId() : "?", modelId, packagePath, items.length);

            response.setStatus(SlingHttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(Json.createObjectBuilder()
                    .add("status", "started")
                    .add("instance", workflow != null && workflow.getId() != null ? workflow.getId() : "")
                    .add("package", packagePath)
                    .add("items", items.length)
                    .build().toString());

        } catch (Exception e) {
            LOG.error("Failed to start batch workflow for model {}", modelId, e);
            sendError(response, SlingHttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Failed to start workflow: " + e.getMessage());
        }
    }

    /**
     * Creates the workflow-package node consumed by package-aware activation steps:
     * <pre>
     * /var/workflow/packages/&lt;name&gt;            [cq:Page]
     *   jcr:content                                (sling:resourceType=cq/workflow/components/collection/page)
     *     filter
     *       f0..fN                                 (@root=&lt;selected path&gt;)
     * </pre>
     *
     * @return the absolute path of the created package node (the workflow payload).
     */
    private String buildWorkflowPackage(Session session, String[] items, String packageTitle) throws Exception {
        final Node packagesRoot = getOrCreatePath(session, PACKAGES_ROOT);
        final String packageName = "mysite-batch-" + UUID.randomUUID();

        final Node packageNode = packagesRoot.addNode(packageName, "cq:Page");
        final Node content = packageNode.addNode("jcr:content", "nt:unstructured");
        content.setProperty("sling:resourceType", COLLECTION_RESOURCE_TYPE);
        content.setProperty("jcr:title",
                StringUtils.isNotBlank(packageTitle) ? packageTitle : packageName);

        final Node filter = content.addNode("filter", "nt:unstructured");
        int index = 0;
        for (String path : items) {
            if (StringUtils.isBlank(path)) {
                continue;
            }
            final Node f = filter.addNode("f" + index, "nt:unstructured");
            f.setProperty("root", path);
            index++;
        }

        session.save();
        return packageNode.getPath();
    }

    private Node getOrCreatePath(Session session, String absPath) throws Exception {
        Node node = session.getRootNode();
        for (String segment : absPath.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            node = node.hasNode(segment) ? node.getNode(segment) : node.addNode(segment, "nt:unstructured");
        }
        return node;
    }

    private void sendError(SlingHttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(Json.createObjectBuilder()
                .add("status", "error")
                .add("message", message)
                .build().toString());
    }
}
