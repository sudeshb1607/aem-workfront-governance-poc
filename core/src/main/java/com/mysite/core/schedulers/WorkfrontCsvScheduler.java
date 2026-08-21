package com.mysite.core.schedulers;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.search.QueryBuilder;
import com.mysite.core.models.workfront.ContentTreeConfig;
import com.mysite.core.models.workfront.WorkfrontConfigReader;
import com.mysite.core.services.WorkfrontCsvGeneratorService;
import com.mysite.core.services.WorkfrontCsvGeneratorService.CsvGenerationResult;

/**
 * Periodically exports page metadata to CSV files in the DAM for Workfront
 * dashboards. At each run it discovers every configuration component in the
 * repository, then generates one CSV per configured content tree. Each tree is
 * isolated: a failure on one never prevents the others from exporting, and any
 * failure raises an AEM Inbox notification for the administrators group.
 */
@Designate(ocd = WorkfrontCsvScheduler.Config.class)
@Component(service = Runnable.class)
public class WorkfrontCsvScheduler implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(WorkfrontCsvScheduler.class);

    private static final String SUBSERVICE = "workfront-csv-write";

    @ObjectClassDefinition(
            name = "Workfront CSV Scheduler",
            description = "Generates Workfront dashboard CSV exports on a schedule.")
    public @interface Config {

        @AttributeDefinition(name = "Cron expression",
                description = "Quartz cron expression driving the export run.")
        String scheduler_expression() default "0 0 10 ? * SAT";

        @AttributeDefinition(name = "Allow concurrent execution")
        boolean scheduler_concurrent() default false;

        @AttributeDefinition(name = "Search root",
                description = "Repository path scanned for configuration components.")
        String searchRoot() default "/content";

        @AttributeDefinition(name = "Pause between trees (seconds)",
                description = "Cool-down applied after each content tree is exported, to let CPU "
                        + "settle before starting the next one. Set to 0 to disable.")
        int pauseBetweenTreesSeconds() default 5;
    }

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private QueryBuilder queryBuilder;

    @Reference
    private WorkfrontCsvGeneratorService csvGeneratorService;

    private String searchRoot;
    private int pauseBetweenTreesSeconds;

    @Activate
    protected void activate(final Config config) {
        this.searchRoot = config.searchRoot();
        this.pauseBetweenTreesSeconds = Math.max(0, config.pauseBetweenTreesSeconds());
        LOG.info("WorkfrontCsvScheduler activated. searchRoot={}, pauseBetweenTreesSeconds={}",
                searchRoot, pauseBetweenTreesSeconds);
    }

    @Override
    public void run() {
        LOG.info("WorkfrontCsvScheduler run started.");
        try (ResourceResolver resolver = getServiceResolver()) {
            final List<ContentTreeConfig> trees =
                    WorkfrontConfigReader.readAllConfiguredTrees(resolver, queryBuilder, searchRoot);
            if (trees.isEmpty()) {
                LOG.info("No Workfront configuration components found under {}", searchRoot);
                return;
            }

            for (int i = 0; i < trees.size(); i++) {
                generateForTree(resolver, trees.get(i));
                // Cool-down after every tree except the last, so CPU can settle
                // before traversing the next tree.
                if (i < trees.size() - 1 && !SchedulerSupport.pause(pauseBetweenTreesSeconds)) {
                    LOG.warn("Workfront CSV export interrupted during cool-down; stopping run.");
                    break;
                }
            }
        } catch (final LoginException e) {
            LOG.error("Could not obtain service resolver for Workfront CSV export", e);
        } catch (final Exception e) {
            LOG.error("Unexpected error during Workfront CSV export run", e);
        }
        LOG.info("WorkfrontCsvScheduler run finished.");
    }

    private void generateForTree(final ResourceResolver resolver, final ContentTreeConfig tree) {
        try {
            final CsvGenerationResult result = csvGeneratorService.generateCsv(tree);
            if (result.isSuccess()) {
                LOG.info("Exported {} page(s) to {}", result.getPagesExported(), result.getCsvPath());
            } else {
                final String detail = String.format(
                        "Workfront CSV export failed for tree '%s' (target: %s): %s",
                        tree.getContentTreePath(), result.getCsvPath(), result.getErrorMessage());
                LOG.error(detail);
                WorkfrontInboxNotifier.notifyFailure(resolver,
                        "Workfront CSV export failed", detail, result.getCsvPath());
            }
        } catch (final Exception e) {
            final String detail = String.format(
                    "Workfront CSV export threw for tree '%s': %s",
                    tree.getContentTreePath(), e.getMessage());
            LOG.error(detail, e);
            WorkfrontInboxNotifier.notifyFailure(resolver,
                    "Workfront CSV export failed", detail, tree.getContentTreePath());
        }
    }

    private ResourceResolver getServiceResolver() throws LoginException {
        final Map<String, Object> authInfo =
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, (Object) SUBSERVICE);
        return resolverFactory.getServiceResourceResolver(authInfo);
    }
}
