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

import com.mysite.core.services.WorkfrontWebhookService;
import com.mysite.core.services.WorkfrontWebhookService.SendResult;

/**
 * Sends the report JSON datasets to the Workfront Fusion webhook on a schedule
 * (weekly). Scans a configurable reports root for per-report subfolders and, for
 * each, sends every {@code json/*.json} as its own signed request, in isolation
 * with in-run retries, so one failing send never blocks the others; anything
 * still failing is retried on the next run.
 */
@Designate(ocd = WorkfrontWebhookScheduler.Config.class)
@Component(service = Runnable.class)
public class WorkfrontWebhookScheduler implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(WorkfrontWebhookScheduler.class);

    private static final String SUBSERVICE = "workfront-csv-write";
    private static final String JSON_EXTENSION = ".json";
    private static final String JSON_SUBFOLDER = "json";

    @ObjectClassDefinition(
            name = "Workfront Webhook Scheduler",
            description = "Sends the report JSON datasets to the Workfront Fusion webhook on a schedule (weekends).")
    public @interface Config {

        @AttributeDefinition(name = "Cron expression",
                description = "Quartz cron expression driving the webhook send run.")
        String scheduler_expression() default "0 0 4 ? * SAT";

        @AttributeDefinition(name = "Allow concurrent execution")
        boolean scheduler_concurrent() default false;

        @AttributeDefinition(name = "Reports root",
                description = "DAM root scanned for per-report subfolders, each with a json/ folder.")
        String reportsRoot() default "/content/dam/mysite/workfront-reports";

        @AttributeDefinition(name = "Max attempts",
                description = "Number of attempts per dataset before giving up for this run (>= 1).")
        int maxAttempts() default 3;

        @AttributeDefinition(name = "Retry backoff (seconds)",
                description = "Pause between retry attempts for the same dataset.")
        int retryBackoffSeconds() default 5;

        @AttributeDefinition(name = "Pause between files (seconds)",
                description = "Cool-down after each dataset. Set to 0 to disable.")
        int pauseBetweenFilesSeconds() default 5;
    }

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private WorkfrontWebhookService webhookService;

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
        LOG.info("WorkfrontWebhookScheduler activated. reportsRoot={}, maxAttempts={}, "
                        + "retryBackoffSeconds={}, pauseBetweenFilesSeconds={}",
                reportsRoot, maxAttempts, retryBackoffSeconds, pauseBetweenFilesSeconds);
    }

    @Override
    public void run() {
        LOG.info("WorkfrontWebhookScheduler run started.");
        try (ResourceResolver resolver = getServiceResolver()) {
            final Resource root = resolver.getResource(reportsRoot);
            if (root == null) {
                LOG.warn("Reports root does not exist: {}", reportsRoot);
                return;
            }

            int sent = 0;
            int failed = 0;
            for (final Resource reportFolder : root.getChildren()) {
                final Resource jsonFolder = reportFolder.getChild(JSON_SUBFOLDER);
                if (jsonFolder == null) {
                    continue;
                }
                for (final Resource child : jsonFolder.getChildren()) {
                    if (!StringUtils.endsWithIgnoreCase(child.getName(), JSON_EXTENSION)) {
                        continue;
                    }
                    if (sendWithRetry(child)) {
                        sent++;
                    } else {
                        failed++;
                    }
                    if (!SchedulerSupport.pause(pauseBetweenFilesSeconds)) {
                        LOG.warn("Workfront webhook send interrupted during cool-down; stopping run.");
                        LOG.info("Webhook send complete. {} sent, {} failed.", sent, failed);
                        return;
                    }
                }
            }
            LOG.info("Webhook send complete. {} sent, {} failed.", sent, failed);
        } catch (final LoginException e) {
            LOG.error("Could not obtain service resolver for Workfront webhook send", e);
        } catch (final Exception e) {
            LOG.error("Unexpected error during Workfront webhook send run", e);
        }
        LOG.info("WorkfrontWebhookScheduler run finished.");
    }

    /**
     * Sends one dataset with up to {@code maxAttempts} attempts and a backoff pause
     * between them. Failures are isolated to this dataset.
     *
     * @return {@code true} if the dataset was accepted by the webhook
     */
    private boolean sendWithRetry(final Resource jsonAsset) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                final SendResult result = webhookService.send(jsonAsset);
                if (result.isSuccess()) {
                    return true;
                }
                LOG.warn("Send attempt {}/{} failed for {}: {}",
                        attempt, maxAttempts, jsonAsset.getPath(), result.getErrorMessage());
            } catch (final Exception e) {
                LOG.warn("Send attempt {}/{} threw for {}",
                        attempt, maxAttempts, jsonAsset.getPath(), e);
            }
            if (attempt < maxAttempts && !SchedulerSupport.pause(retryBackoffSeconds)) {
                LOG.warn("Interrupted during retry backoff for {}; aborting dataset.", jsonAsset.getPath());
                break;
            }
        }
        LOG.error("Giving up sending {} after {} attempt(s)", jsonAsset.getPath(), maxAttempts);
        return false;
    }

    private ResourceResolver getServiceResolver() throws LoginException {
        final Map<String, Object> authInfo =
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, (Object) SUBSERVICE);
        return resolverFactory.getServiceResourceResolver(authInfo);
    }
}
