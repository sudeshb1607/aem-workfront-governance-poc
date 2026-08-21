package com.mysite.core.schedulers;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
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

import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.Rendition;
import com.day.cq.search.QueryBuilder;
import com.mysite.core.models.workfront.ContentTreeConfig;
import com.mysite.core.models.workfront.WorkfrontConfigReader;
import com.mysite.core.services.WorkfrontCsvGeneratorService;
import com.mysite.core.services.WorkfrontCsvGeneratorService.CsvGenerationResult;

/**
 * Self-healing companion to {@link WorkfrontCsvScheduler}. Runs on a separate,
 * lower-frequency schedule and re-generates any CSV that is unhealthy, where a
 * healthy CSV is one that is present, non-empty, and was updated within the
 * configured freshness window. This catches trees that failed in the main run,
 * assets that never got replicated, empty exports, and stale files.
 */
@Designate(ocd = WorkfrontCsvVerificationScheduler.Config.class)
@Component(service = Runnable.class)
public class WorkfrontCsvVerificationScheduler implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(WorkfrontCsvVerificationScheduler.class);

    private static final String SUBSERVICE = "workfront-csv-write";
    private static final String CSV_EXTENSION = ".csv";
    private static final long MILLIS_PER_MINUTE = 60_000L;

    @ObjectClassDefinition(
            name = "Workfront CSV Verification Scheduler",
            description = "Re-generates Workfront dashboard CSV files that are missing, empty, or stale.")
    public @interface Config {

        @AttributeDefinition(name = "Cron expression",
                description = "Quartz cron expression driving the verification run.")
        String scheduler_expression() default "0 0 8 ? * SUN";

        @AttributeDefinition(name = "Allow concurrent execution")
        boolean scheduler_concurrent() default false;

        @AttributeDefinition(name = "Search root",
                description = "Repository path scanned for configuration components.")
        String searchRoot() default "/content";

        @AttributeDefinition(name = "Freshness threshold (minutes)",
                description = "A CSV last modified longer ago than this is considered stale and re-generated. "
                        + "Set to 0 to disable the staleness check (only missing/empty CSVs are re-generated).")
        int freshnessThresholdMinutes() default 60;

        @AttributeDefinition(name = "Minimum CSV size (bytes)",
                description = "A CSV smaller than this is considered empty and re-generated. Default 1 (must be non-empty).")
        long minCsvSizeBytes() default 1L;

        @AttributeDefinition(name = "Pause between trees (seconds)",
                description = "Cool-down applied after each CSV is re-generated, to let CPU settle "
                        + "before processing the next one. Set to 0 to disable.")
        int pauseBetweenTreesSeconds() default 5;
    }

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private QueryBuilder queryBuilder;

    @Reference
    private WorkfrontCsvGeneratorService csvGeneratorService;

    private String searchRoot;
    private int freshnessThresholdMinutes;
    private long minCsvSizeBytes;
    private int pauseBetweenTreesSeconds;

    @Activate
    protected void activate(final Config config) {
        this.searchRoot = config.searchRoot();
        this.freshnessThresholdMinutes = Math.max(0, config.freshnessThresholdMinutes());
        this.minCsvSizeBytes = Math.max(0L, config.minCsvSizeBytes());
        this.pauseBetweenTreesSeconds = Math.max(0, config.pauseBetweenTreesSeconds());
        LOG.info("WorkfrontCsvVerificationScheduler activated. searchRoot={}, freshnessThresholdMinutes={}, minCsvSizeBytes={}, pauseBetweenTreesSeconds={}",
                searchRoot, freshnessThresholdMinutes, minCsvSizeBytes, pauseBetweenTreesSeconds);
    }

    @Override
    public void run() {
        LOG.info("WorkfrontCsvVerificationScheduler run started.");
        try (ResourceResolver resolver = getServiceResolver()) {
            final List<ContentTreeConfig> trees =
                    WorkfrontConfigReader.readAllConfiguredTrees(resolver, queryBuilder, searchRoot);
            final String damRoot = StringUtils.removeEnd(csvGeneratorService.getDamRootPath(), "/");

            int regenerated = 0;
            for (int i = 0; i < trees.size(); i++) {
                final ContentTreeConfig tree = trees.get(i);
                final String csvName = StringUtils.removeEnd(tree.getCsvName().trim(), CSV_EXTENSION);
                final String csvPath = damRoot + "/" + csvName + CSV_EXTENSION;
                final String reason = healthCheck(resolver, csvPath);
                if (reason == null) {
                    LOG.debug("Verified CSV healthy: {}", csvPath);
                    continue;
                }
                regenerated++;
                LOG.warn("CSV {} needs re-generation ({}) - re-generating", csvPath, reason);
                regenerate(resolver, tree, csvPath);
                // Cool-down after actual regeneration work, except after the last tree.
                if (i < trees.size() - 1 && !SchedulerSupport.pause(pauseBetweenTreesSeconds)) {
                    LOG.warn("Workfront CSV verification interrupted during cool-down; stopping run.");
                    break;
                }
            }
            LOG.info("Verification complete. {} CSV(s) re-generated.", regenerated);
        } catch (final LoginException e) {
            LOG.error("Could not obtain service resolver for Workfront CSV verification", e);
        } catch (final Exception e) {
            LOG.error("Unexpected error during Workfront CSV verification run", e);
        }
        LOG.info("WorkfrontCsvVerificationScheduler run finished.");
    }

    /**
     * Determines whether a CSV needs re-generation.
     *
     * @return {@code null} if the CSV is healthy; otherwise a short human-readable
     *         reason ("missing", "empty", "stale").
     */
    private String healthCheck(final ResourceResolver resolver, final String csvPath) {
        final org.apache.sling.api.resource.Resource resource = resolver.getResource(csvPath);
        if (resource == null) {
            return "missing";
        }
        final Asset asset = resource.adaptTo(Asset.class);
        if (asset == null) {
            return "not a DAM asset";
        }

        // Size check
        final Rendition original = asset.getOriginal();
        final long size = original != null ? original.getSize() : 0L;
        if (size < minCsvSizeBytes) {
            return "empty (" + size + " bytes < " + minCsvSizeBytes + ")";
        }

        // Freshness check (in minutes); disabled when threshold is 0
        if (freshnessThresholdMinutes > 0) {
            final long lastModified = asset.getLastModified();
            if (lastModified <= 0L) {
                return "unknown last-modified";
            }
            final long ageMinutes = (System.currentTimeMillis() - lastModified) / MILLIS_PER_MINUTE;
            if (ageMinutes > freshnessThresholdMinutes) {
                return "stale (" + ageMinutes + " min old > " + freshnessThresholdMinutes + " min)";
            }
        }
        return null;
    }

    private void regenerate(final ResourceResolver resolver, final ContentTreeConfig tree, final String csvPath) {
        try {
            final CsvGenerationResult result = csvGeneratorService.generateCsv(tree);
            if (!result.isSuccess()) {
                final String detail = String.format(
                        "Workfront CSV re-generation failed for tree '%s' (target: %s): %s",
                        tree.getContentTreePath(), csvPath, result.getErrorMessage());
                LOG.error(detail);
                WorkfrontInboxNotifier.notifyFailure(resolver,
                        "Workfront CSV re-generation failed", detail, csvPath);
            }
        } catch (final Exception e) {
            LOG.error("Workfront CSV re-generation threw for tree {}", tree.getContentTreePath(), e);
        }
    }

    private ResourceResolver getServiceResolver() throws LoginException {
        final Map<String, Object> authInfo =
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, (Object) SUBSERVICE);
        return resolverFactory.getServiceResourceResolver(authInfo);
    }
}
