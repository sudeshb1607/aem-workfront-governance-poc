package com.mysite.core.workflow;

import com.adobe.granite.workflow.WorkflowException;
import com.adobe.granite.workflow.WorkflowSession;
import com.adobe.granite.workflow.exec.WorkItem;
import com.adobe.granite.workflow.exec.WorkflowProcess;
import com.adobe.granite.workflow.metadata.MetaDataMap;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObjectBuilder;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs AFTER the Reviewer Approval step. Expands the workflow-package payload (all
 * selected paths, whether one or many), builds a JSON array describing each asset
 * (path + a few properties) and prints it to the logs.
 *
 * Wired into the model as: START -> Publish -> Reviewer Approval -> Log Asset JSON -> END.
 */
@Component(
        service = WorkflowProcess.class,
        property = {
                "process.label=MySite Batch Asset JSON"
        }
)
public class BatchAssetJsonProcess implements WorkflowProcess {

    private static final Logger LOG = LoggerFactory.getLogger(BatchAssetJsonProcess.class);

    @Override
    public void execute(WorkItem workItem, WorkflowSession workflowSession, MetaDataMap args)
            throws WorkflowException {

        final String payload = String.valueOf(workItem.getWorkflowData().getPayload());
        final ResourceResolver resolver = workflowSession.adaptTo(ResourceResolver.class);
        if (resolver == null) {
            throw new WorkflowException("No ResourceResolver available for asset-JSON step");
        }

        final List<String> paths = expandPackage(resolver, payload);
        final String instanceId = workItem.getWorkflow() != null ? workItem.getWorkflow().getId() : "?";

        final JsonArrayBuilder assets = Json.createArrayBuilder();
        for (String path : paths) {
            assets.add(describeAsset(resolver, path));
        }

        final String json = Json.createObjectBuilder()
                .add("instance", instanceId)
                .add("count", paths.size())
                .add("assets", assets)
                .build()
                .toString();

        LOG.info("BatchAssetJsonProcess instance {}: {} asset(s) JSON => {}", instanceId, paths.size(), json);
    }

    private JsonObjectBuilder describeAsset(ResourceResolver resolver, String path) {
        final Resource asset = resolver.getResource(path);
        final ValueMap assetVm = asset != null ? asset.getValueMap() : null;
        final Resource content = resolver.getResource(path + "/jcr:content");
        final ValueMap contentVm = content != null ? content.getValueMap() : null;
        final Resource metadata = resolver.getResource(path + "/jcr:content/metadata");
        final ValueMap metaVm = metadata != null ? metadata.getValueMap() : null;

        final JsonObjectBuilder obj = Json.createObjectBuilder();
        obj.add("path", path);
        obj.add("name", path.substring(path.lastIndexOf('/') + 1));
        obj.add("primaryType", assetVm != null ? assetVm.get("jcr:primaryType", "") : "");
        obj.add("isContentFragment", contentVm != null && contentVm.get("contentFragment", false));

        obj.add("title", firstNonEmpty(
                str(metaVm, "dc:title"),
                str(contentVm, "jcr:title"),
                path.substring(path.lastIndexOf('/') + 1)));
        obj.add("description", firstNonEmpty(str(metaVm, "dc:description"), ""));
        obj.add("mimeType", firstNonEmpty(str(metaVm, "dc:format"), str(contentVm, "jcr:mimeType"), ""));
        obj.add("size", metaVm != null ? metaVm.get("dam:size", 0L) : 0L);
        obj.add("width", firstNonEmpty(str(metaVm, "tiff:ImageWidth"), str(metaVm, "exif:PixelXDimension"), ""));
        obj.add("height", firstNonEmpty(str(metaVm, "tiff:ImageLength"), str(metaVm, "exif:PixelYDimension"), ""));

        obj.add("createdBy", assetVm != null ? assetVm.get("jcr:createdBy", "") : "");
        obj.add("created", assetVm != null ? String.valueOf(assetVm.get("jcr:created", "")) : "");
        obj.add("lastModifiedBy", firstNonEmpty(str(contentVm, "jcr:lastModifiedBy"), str(metaVm, "dc:modifier"), ""));
        obj.add("lastModified", contentVm != null ? String.valueOf(contentVm.get("jcr:lastModified", "")) : "");

        // Replication / publication status
        obj.add("lastReplicated", contentVm != null ? String.valueOf(contentVm.get("cq:lastReplicated", "")) : "");
        obj.add("lastReplicationAction", firstNonEmpty(str(contentVm, "cq:lastReplicationAction"), ""));
        obj.add("published", contentVm != null
                && "Activate".equals(contentVm.get("cq:lastReplicationAction", String.class)));

        // Tags (cq:tags is a multi-value String[])
        final JsonArrayBuilder tags = Json.createArrayBuilder();
        final String[] tagValues = metaVm != null ? metaVm.get("cq:tags", String[].class) : null;
        if (tagValues != null) {
            for (String tag : tagValues) {
                tags.add(tag);
            }
        }
        obj.add("tags", tags);

        return obj;
    }

    private String str(ValueMap vm, String key) {
        return vm != null ? vm.get(key, String.class) : null;
    }

    private String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    /** Reads member paths from the workflow-package node; falls back to the single payload path. */
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
