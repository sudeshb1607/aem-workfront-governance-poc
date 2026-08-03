package com.mysite.core.servlets;

import com.adobe.granite.ui.components.ds.DataSource;
import com.adobe.granite.ui.components.ds.SimpleDataSource;
import com.adobe.granite.ui.components.ds.ValueMapResource;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.model.WorkflowModel;
import com.adobe.granite.workflow.model.WorkflowNode;
import com.adobe.granite.workflow.model.WorkflowTransition;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceMetadata;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * C1 - Datasource that lists the available workflow models for the batch-workflow
 * wizard's "Workflow Model" select. Populates {value=model id, text=model title}.
 *
 * Decoupled from OOTB: it only relies on the stable {@link WorkflowSession} API
 * available in the 6.5 uber-jar.
 */
@Component(
        service = Servlet.class,
        property = {
                "sling.servlet.resourceTypes=mysite/gui/batchworkflow/models",
                "sling.servlet.methods=GET"
        }
)
public class BatchWorkflowModelsDataSourceServlet extends SlingSafeMethodsServlet {

    private static final Logger LOG = LoggerFactory.getLogger(BatchWorkflowModelsDataSourceServlet.class);

    /** Title of the project-owned model (Deliverable D). */
    private static final String MODEL_TITLE = "MySite Batch Publish";
    /** FQCN of BatchPublishWorkflowProcess. The engine resolves PROCESS steps by service
     *  PID/class name (like OOTB ActivatePageProcess), NOT by process.label. */
    private static final String PROCESS_ID = "com.mysite.core.workflow.BatchPublishWorkflowProcess";
    /** Dialog Participant (review) step: assignee + the completion dialog that collects the reviewer name. */
    private static final String REVIEW_PARTICIPANT = "admin";
    private static final String REVIEW_DIALOG_PATH = "/apps/mysite/workflow/dialogs/reviewer";
    private static final String REVIEW_STEP_TITLE = "Reviewer Approval";
    /** Post-review step that logs a JSON of all payload assets. */
    private static final String JSON_PROCESS_ID = "com.mysite.core.workflow.BatchAssetJsonProcess";
    private static final String JSON_STEP_TITLE = "Log Asset JSON";

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) {
        final ResourceResolver resolver = request.getResourceResolver();
        final List<Resource> entries = new ArrayList<>();

        try {
            final WorkflowSession wfSession = resolver.adaptTo(WorkflowSession.class);
            if (wfSession != null) {
                // Self-register the project model so it appears in the dropdown on first use
                // (a /conf model page alone is not listed by getModels() until its runtime
                // model under /var/workflow/models exists). Idempotent and author-scoped.
                ensureModelRegistered(wfSession);

                for (WorkflowModel model : wfSession.getModels()) {
                    final Map<String, Object> props = new HashMap<>();
                    props.put("value", model.getId());
                    props.put("text", model.getTitle() != null ? model.getTitle() : model.getId());
                    // Default-select our project model so the wizard is preset to it.
                    if (MODEL_TITLE.equals(model.getTitle())) {
                        props.put("selected", true);
                    }
                    entries.add(new ValueMapResource(resolver, new ResourceMetadata(),
                            "nt:unstructured", new ValueMapDecorator(props)));
                }
            }
        } catch (Exception e) {
            LOG.error("Unable to load workflow models for batch-workflow wizard", e);
        }

        final DataSource dataSource = new SimpleDataSource(entries.iterator());
        request.setAttribute(DataSource.class.getName(), dataSource);
    }

    /**
     * Ensures a runtime workflow model for {@value #MODEL_TITLE} exists under
     * /var/workflow/models so {@code getModels()} lists it.
     *
     * Builds: START -> PROCESS(BatchPublishWorkflowProcess) -> PARTICIPANT(review dialog) -> END.
     * If a model with this title already exists but lacks the review (participant) step, it is
     * deleted and rebuilt so deployments pick up the new step.
     */
    private void ensureModelRegistered(WorkflowSession wfSession) {
        try {
            // If an up-to-date model already exists, stop here -- never create duplicates.
            for (WorkflowModel existing : wfSession.getModels()) {
                if (MODEL_TITLE.equals(existing.getTitle()) && hasJsonStep(existing)) {
                    return;
                }
            }
            // No current model: best-effort remove any stale ones with our title, then build one.
            for (WorkflowModel existing : wfSession.getModels()) {
                if (MODEL_TITLE.equals(existing.getTitle())) {
                    try {
                        wfSession.deleteModel(existing.getId());
                    } catch (Exception delEx) {
                        LOG.warn("Could not delete stale batch-publish model {}", existing.getId(), delEx);
                    }
                }
            }

            final WorkflowModel model = wfSession.createNewModel(MODEL_TITLE);
            final WorkflowNode start = model.getRootNode();
            final WorkflowNode end = model.getEndNode();

            // createNewModel seeds START -> <middle> -> END. Reuse the middle node as our
            // PROCESS step so transitions stay intact and no node is orphaned.
            WorkflowNode process = null;
            for (WorkflowNode n : model.getNodes()) {
                final String type = n.getType();
                if (!WorkflowNode.TYPE_START.equals(type) && !WorkflowNode.TYPE_END.equals(type)) {
                    process = n;
                    break;
                }
            }
            if (process == null) {
                process = model.createNode();
                model.createTransition(start, process, null);
                model.createTransition(process, end, null);
            }
            process.setType(WorkflowNode.TYPE_PROCESS);
            process.setTitle("Publish Assets");
            process.getMetaDataMap().remove("PARTICIPANT");
            process.getMetaDataMap().put("PROCESS", PROCESS_ID);
            process.getMetaDataMap().put("PROCESS_AUTO_ADVANCE", "true");

            // Dialog Participant (review) step: the workflow stops here; the assignee opens the
            // work item, fills the reviewer name in the dialog and completes it.
            final WorkflowNode review = model.createNode();
            review.setType(WorkflowNode.TYPE_PARTICIPANT);
            review.setTitle(REVIEW_STEP_TITLE);
            review.getMetaDataMap().put("PARTICIPANT", REVIEW_PARTICIPANT);
            review.getMetaDataMap().put("DIALOG_PATH", REVIEW_DIALOG_PATH);

            // Post-review PROCESS step: logs a JSON of every payload asset.
            final WorkflowNode jsonStep = model.createNode();
            jsonStep.setType(WorkflowNode.TYPE_PROCESS);
            jsonStep.setTitle(JSON_STEP_TITLE);
            jsonStep.getMetaDataMap().put("PROCESS", JSON_PROCESS_ID);
            jsonStep.getMetaDataMap().put("PROCESS_AUTO_ADVANCE", "true");

            // Rewire START -> PROCESS -> REVIEW -> JSON -> END (repoint the PROCESS -> END transition).
            WorkflowTransition processOut = null;
            for (WorkflowTransition t : process.getTransitions()) {
                if (t.getTo() != null && t.getTo().getId().equals(end.getId())) {
                    processOut = t;
                    break;
                }
            }
            if (processOut != null) {
                processOut.setTo(review);
            } else {
                model.createTransition(process, review, null);
            }
            model.createTransition(review, jsonStep, null);
            model.createTransition(jsonStep, end, null);

            model.validate();
            wfSession.deployModel(model);
            LOG.info("Registered batch-publish workflow model '{}' (id={})", MODEL_TITLE, model.getId());
        } catch (Exception e) {
            // Non-fatal: the wizard still lists OOTB package-aware models (e.g. activationmodel).
            LOG.warn("Could not auto-register batch-publish workflow model; OOTB models remain available", e);
        }
    }

    private boolean hasJsonStep(WorkflowModel model) {
        try {
            for (WorkflowNode n : model.getNodes()) {
                if (WorkflowNode.TYPE_PROCESS.equals(n.getType())
                        && JSON_PROCESS_ID.equals(n.getMetaDataMap().get("PROCESS", String.class))) {
                    return true;
                }
            }
        } catch (Exception e) {
            LOG.debug("Unable to inspect model nodes for {}", model.getId(), e);
        }
        return false;
    }
}
