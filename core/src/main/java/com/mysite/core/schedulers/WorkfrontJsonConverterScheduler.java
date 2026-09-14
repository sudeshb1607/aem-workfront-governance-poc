package com.mysite.core.schedulers;

import java.util.Collections;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
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

import com.mysite.core.services.WorkfrontJsonConverterService;
import com.mysite.core.services.WorkfrontJsonConverterService.ConversionResult;

/**
 * Converts the report CSVs into JSON datasets on a schedule (weekly). Scans a
 * configurable reports root for per-report subfolders and, for each, converts
 * every {@code csv/*.csv} into {@code json/}. Each CSV is processed in isolation
 * with in-run retries (attempts with a backoff pause), so one failing file never
 * blocks the others; anything still failing is retried on the next run.
 */
@Designate(ocd = WorkfrontJsonConverterScheduler.Config.class)
@Component(service = Runnable.class)
public class WorkfrontJsonConverterScheduler implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(WorkfrontJsonConverterScheduler.class);

    private static final String SUBSERVICE = "workfront-csv-write";
    private static final String CSV_EXTENSION = ".csv";
    private static final String CSV_SUBFOLDER = "csv";
    private static final String JSON_SUBFOLDER = "json";

    @ObjectClassDefinition(
            name = "Workfront JSON Converter Scheduler",
            description = "Converts the report CSVs into JSON datasets on a schedule.")
    public @interface Config {

        @AttributeDefinition(name = "Cron expression",
                description = "Quartz cron expression driving the conversion run.")
        String scheduler_expression() default "0 0 3 ? * SAT";

        @AttributeDefinition(name = "Allow concurrent execution")
        boolean scheduler_concurrent() default false;

        @AttributeDefinition(name = "Reports root",
                description = "DAM root scanned for per-report subfolders, each with csv/ and json/.")
        String reportsRoot() default "/content/dam/mysite/workfront-reports";

        @AttributeDefinition(name = "Max attempts",
                description = "Number of attempts per file before giving up for this run (>= 1).")
        int maxAttempts() default 3;

        @AttributeDefinition(name = "Retry backoff (seconds)",
                description = "Pause between retry attempts for the same file.")
        int retryBackoffSeconds() default 5;

        @AttributeDefinition(name = "Pause between files (seconds)",
                description = "Cool-down after each file, to let CPU settle. Set to 0 to disable.")
        int pauseBetweenFilesSeconds() default 5;
    }

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private WorkfrontJsonConverterService converterService;

    private String reportsRoot;
    private int maxAttempts;
    private int retryBackoffSeconds;
    private int pauseBetweenFilesSeconds;

    @Activate
    protected void activate(final Config config) {
        this.reportsRoot = StringUtils.removeEnd(
                StringUtils.defaultString(config.reportsRoot()).trim(), "/");
        this.maxAttempts = Math.max(1, config.maxAttempts());
        this.retryBackoffSeconds = Math.max(0, config.retryBackoffSeconds());
        this.pauseBetweenFilesSeconds = Math.max(0, config.pauseBetweenFilesSeconds());
        LOG.info("WorkfrontJsonConverterScheduler activated. reportsRoot={}, maxAttempts={}, "
                        + "retryBackoffSeconds={}, pauseBetweenFilesSeconds={}",
                reportsRoot, maxAttempts, retryBackoffSeconds, pauseBetweenFilesSeconds);
    }

    @Override
    public void run() {
        LOG.info("WorkfrontJsonConverterScheduler run started.");
        try (ResourceResolver resolver = getServiceResolver()) {
            final Resource root = resolver.getResource(reportsRoot);
            if (root == null) {
                LOG.warn("Reports root does not exist: {}", reportsRoot);
                return;
            }

            int converted = 0;
            int failed = 0;
            for (final Resource reportFolder : root.getChildren()) {
                final Resource csvFolder = reportFolder.getChild(CSV_SUBFOLDER);
                if (csvFolder == null) {
                    continue;
                }
                final String jsonFolder = reportFolder.getPath() + "/" + JSON_SUBFOLDER;
                for (final Resource child : csvFolder.getChildren()) {
                    if (!StringUtils.endsWithIgnoreCase(child.getName(), CSV_EXTENSION)) {
                        continue;
                    }
                    if (convertWithRetry(child, jsonFolder)) {
                        converted++;
                    } else {
                        failed++;
                    }
                    if (!SchedulerSupport.pause(pauseBetweenFilesSeconds)) {
                        LOG.warn("Workfront JSON conversion interrupted during cool-down; stopping run.");
                        LOG.info("Conversion complete. {} converted, {} failed.", converted, failed);
                        return;
                    }
                }
            }
            LOG.info("Conversion complete. {} converted, {} failed.", converted, failed);
        } catch (final LoginException e) {
            LOG.error("Could not obtain service resolver for Workfront JSON conversion", e);
        } catch (final Exception e) {
            LOG.error("Unexpected error during Workfront JSON conversion run", e);
        }
        LOG.info("WorkfrontJsonConverterScheduler run finished.");
    }

    /**
     * Converts one CSV into the given JSON folder with up to {@code maxAttempts}
     * attempts and a backoff pause between them. Failures are isolated to this file.
     *
     * @return {@code true} if the file converted successfully
     */
    private boolean convertWithRetry(final Resource csvAsset, final String jsonFolder) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                final ConversionResult result = converterService.convert(csvAsset, jsonFolder);
                if (result.isSuccess()) {
                    return true;
                }
                LOG.warn("Conversion attempt {}/{} failed for {}: {}",
                        attempt, maxAttempts, csvAsset.getPath(), result.getErrorMessage());
            } catch (final Exception e) {
                LOG.warn("Conversion attempt {}/{} threw for {}", attempt, maxAttempts, csvAsset.getPath(), e);
            }
            if (attempt < maxAttempts && !SchedulerSupport.pause(retryBackoffSeconds)) {
                LOG.warn("Interrupted during retry backoff for {}; aborting file.", csvAsset.getPath());
                break;
            }
        }
        LOG.error("Giving up converting {} after {} attempt(s)", csvAsset.getPath(), maxAttempts);
        return false;
    }

    private ResourceResolver getServiceResolver() throws LoginException {
        final Map<String, Object> authInfo =
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, (Object) SUBSERVICE);
        return resolverFactory.getServiceResourceResolver(authInfo);
    }
}
