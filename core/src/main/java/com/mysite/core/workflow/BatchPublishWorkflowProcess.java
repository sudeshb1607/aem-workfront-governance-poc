package com.mysite.core.workflow;

import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.WorkflowProcess;
import com.adobe.granite.workflow.metadata.MetaDataMap;
import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.Replicator;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Session;
import java.util.ArrayList;
import java.util.List;

/**
 * Deliverable D - Project-owned sample workflow step that proves the single-instance
 * behaviour: it expands the workflow-package payload (built by
 * {@code BatchWorkflowServlet}), logs one line listing all N members, and activates
 * each via the {@link Replicator}.
 *
 * Wire it into a model whose {@code metaData.multiResourceSupport=true} so the wizard
 * lists it (see /conf/global/settings/workflow/models/mysite-batch-publish).
 */
@Component(
        service = WorkflowProcess.class,
        property = {
                "process.label=MySite Batch Publish"
        }
)
public class BatchPublishWorkflowProcess implements WorkflowProcess {

    private static final Logger LOG = LoggerFactory.getLogger(BatchPublishWorkflowProcess.class);

    @Reference
    private Replicator replicator;

    @Override
    public void execute(WorkItem workItem, WorkflowSession workflowSession, MetaDataMap args)
            throws WorkflowException {

        final String payload = String.valueOf(workItem.getWorkflowData().getPayload());
        final ResourceResolver resolver = workflowSession.adaptTo(ResourceResolver.class);
        if (resolver == null) {
            throw new WorkflowException("No ResourceResolver available for batch-publish step");
        }

        final List<String> members = expandPackage(resolver, payload);
        final String instanceId = workItem.getWorkflow() != null ? workItem.getWorkflow().getId() : "?";
        LOG.info("BatchPublishWorkflowProcess instance {}: {} assets -> {}", instanceId, members.size(), members);

        final Session session = resolver.adaptTo(Session.class);
        for (String path : members) {
            try {
                replicator.replicate(session, ReplicationActionType.ACTIVATE, path);
            } catch (Exception e) {
                LOG.error("Failed to activate {} in batch-publish step", path, e);
            }
        }
    }

    /**
     * Reads the member paths from the workflow-package node under
     * {@code jcr:content/filter} (each child fN carries a {@code root} property).
     * Falls back to treating the payload as a single path when it is not a package.
     */
    private List<String> expandPackage(ResourceResolver resolver, String payload) {
        final List<String> members = new ArrayList<>();
        final Resource filter = resolver.getResource(payload + "/jcr:content/filter");
        if (filter != null) {
            for (Resource f : filter.getChildren()) {
                final String root = f.getValueMap().get("root", String.class);
                if (root != null && !root.isEmpty()) {
                    members.add(root);
                }
            }
        }
        if (members.isEmpty()) {
            members.add(payload);
        }
        return members;
    }
}
