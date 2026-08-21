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
import com.mysite.core.models.report.UnpublishedReportConfig;
import com.mysite.core.models.report.UnpublishedReportConfigReader;
import com.mysite.core.services.UnpublishedPagesReportService;
import com.mysite.core.services.UnpublishedPagesReportService.ReportResult;

/**
 * Monthly scheduler that discovers every Unpublished Pages Report configuration
 * component under the search root and generates one CSV per configuration. Each
 * configuration is isolated: a failure on one never prevents the others from
 * running, and any failure raises an AEM Inbox notification for the
 * administrators group.
 */
@Designate(ocd = UnpublishedPagesReportScheduler.Config.class)
@Component(service = Runnable.class)
public class UnpublishedPagesReportScheduler implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(UnpublishedPagesReportScheduler.class);

    private static final String SUBSERVICE = "unpublished-report-write";

    @ObjectClassDefinition(
            name = "Unpublished Pages Report Scheduler",
            description = "Generates unpublished-pages CSV reports on a schedule (monthly by default).")
    public @interface Config {

        @AttributeDefinition(name = "Cron expression",
                description = "Quartz cron expression driving the report run (default: 02:00 on the 1st monthly).")
        String scheduler_expression() default "0 0 2 1 * ?";

        @AttributeDefinition(name = "Allow concurrent execution")
        boolean scheduler_concurrent() default false;

        @AttributeDefinition(name = "Search root",
                description = "Repository path scanned for configuration components.")
        String searchRoot() default "/content";

        @AttributeDefinition(name = "Pause between configs (seconds)",
                description = "Cool-down applied after each configuration is processed. Set to 0 to disable.")
        int pauseBetweenConfigsSeconds() default 5;
    }

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private QueryBuilder queryBuilder;

    @Reference
    private UnpublishedPagesReportService reportService;

    private String searchRoot;
    private int pauseBetweenConfigsSeconds;

    @Activate
    protected void activate(final Config config) {
        this.searchRoot = config.searchRoot();
        this.pauseBetweenConfigsSeconds = Math.max(0, config.pauseBetweenConfigsSeconds());
        LOG.info("UnpublishedPagesReportScheduler activated. searchRoot={}, pauseBetweenConfigsSeconds={}",
                searchRoot, pauseBetweenConfigsSeconds);
    }

    @Override
    public void run() {
        LOG.info("UnpublishedPagesReportScheduler run started.");
        try (ResourceResolver resolver = getServiceResolver()) {
            final List<UnpublishedReportConfig> configs =
                    UnpublishedReportConfigReader.readAll(resolver, queryBuilder, searchRoot);
            if (configs.isEmpty()) {
                LOG.info("No Unpublished Pages Report configuration components found under {}", searchRoot);
                return;
            }

            for (int i = 0; i < configs.size(); i++) {
                generateForConfig(resolver, configs.get(i));
                if (i < configs.size() - 1 && !SchedulerSupport.pause(pauseBetweenConfigsSeconds)) {
                    LOG.warn("Unpublished-pages report run interrupted during cool-down; stopping run.");
                    break;
                }
            }
        } catch (final LoginException e) {
            LOG.error("Could not obtain service resolver for unpublished-pages report run", e);
        } catch (final Exception e) {
            LOG.error("Unexpected error during unpublished-pages report run", e);
        }
        LOG.info("UnpublishedPagesReportScheduler run finished.");
    }

    private void generateForConfig(final ResourceResolver resolver, final UnpublishedReportConfig config) {
        try {
            final ReportResult result = reportService.generateReport(config);
            if (result.isSuccess()) {
                LOG.info("Reported {} unpublished page(s) to {}", result.getRowsReported(), result.getCsvPath());
            } else {
                final String detail = String.format(
                        "Unpublished-pages report failed for config '%s' (target: %s): %s",
                        config.getComponentPath(), result.getCsvPath(), result.getErrorMessage());
                LOG.error(detail);
                WorkfrontInboxNotifier.notifyFailure(resolver,
                        "Unpublished-pages report failed", detail, result.getCsvPath());
            }
        } catch (final Exception e) {
            final String detail = String.format(
                    "Unpublished-pages report threw for config '%s': %s",
                    config.getComponentPath(), e.getMessage());
            LOG.error(detail, e);
            WorkfrontInboxNotifier.notifyFailure(resolver,
                    "Unpublished-pages report failed", detail, config.getComponentPath());
        }
    }

    private ResourceResolver getServiceResolver() throws LoginException {
        final Map<String, Object> authInfo =
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, (Object) SUBSERVICE);
        return resolverFactory.getServiceResourceResolver(authInfo);
    }
}
