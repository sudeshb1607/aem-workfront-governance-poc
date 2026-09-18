package com.mysite.core.schedulers;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.search.QueryBuilder;
import com.mysite.core.reports.ReportDefinition;
import com.mysite.core.reports.ReportDefinitionReader;
import com.mysite.core.services.ReportGeneratorService;
import com.mysite.core.services.ReportGeneratorService.ReportRunResult;

/**
 * Generates all five governance reports on a schedule (weekly, weekends by
 * default). Discovers every report configuration component under the search root
 * and runs each in isolation, with a cool-down between them. Failures are logged
 * and raise an AEM Inbox notification, but never stop the other reports.
 */
@Designate(ocd = ReportCsvGeneratorScheduler.Config.class)
@Component(service = Runnable.class)
public class ReportCsvGeneratorScheduler implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(ReportCsvGeneratorScheduler.class);

    private static final String SUBSERVICE = "workfront-csv-write";

    @ObjectClassDefinition(
            name = "Workfront Report CSV Generator Scheduler",
            description = "Generates the governance report CSVs on a schedule (weekly).")
    public @interface Config {

        @AttributeDefinition(name = "Cron expression",
                description = "Quartz cron expression driving the report generation run.")
        String scheduler_expression() default "0 0 2 ? * SAT";

        @AttributeDefinition(name = "Allow concurrent execution")
        boolean scheduler_concurrent() default false;

        @AttributeDefinition(name = "Search root",
                description = "Repository path scanned for report configuration components.")
        String searchRoot() default "/content";

        @AttributeDefinition(name = "Pause between reports (seconds)",
                description = "Cool-down applied after each report is generated. Set to 0 to disable.")
        int pauseBetweenReportsSeconds() default 5;
    }

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private QueryBuilder queryBuilder;

    @Reference
    private ReportGeneratorService reportService;

    private String searchRoot;
    private int pauseBetweenReportsSeconds;

    /** Counted down on deactivate so only a real component stop ends a run early. */
    private final CountDownLatch stopLatch = new CountDownLatch(1);

    @Activate
    protected void activate(final Config config) {
        this.searchRoot = config.searchRoot();
        this.pauseBetweenReportsSeconds = Math.max(0, config.pauseBetweenReportsSeconds());
        LOG.info("ReportCsvGeneratorScheduler activated. searchRoot={}, pauseBetweenReportsSeconds={}",
                searchRoot, pauseBetweenReportsSeconds);
    }

    @Deactivate
    protected void deactivate() {
        stopLatch.countDown();
    }

    @Override
    public void run() {
        LOG.info("ReportCsvGeneratorScheduler run started.");
        try (ResourceResolver resolver = getServiceResolver()) {
            final List<ReportDefinition> definitions =
                    ReportDefinitionReader.readAll(resolver, queryBuilder, searchRoot);
            if (definitions.isEmpty()) {
                LOG.info("No report configuration components found under {}", searchRoot);
                return;
            }

            for (int i = 0; i < definitions.size(); i++) {
                generateOne(resolver, definitions.get(i));
                if (i < definitions.size() - 1 && !SchedulerSupport.await(pauseBetweenReportsSeconds, stopLatch)) {
                    LOG.warn("Report generation stopping (component deactivating) during cool-down.");
                    break;
                }
            }
        } catch (final LoginException e) {
            LOG.error("Could not obtain service resolver for report generation", e);
        } catch (final Exception e) {
            LOG.error("Unexpected error during report generation run", e);
        }
        LOG.info("ReportCsvGeneratorScheduler run finished.");
    }

    private void generateOne(final ResourceResolver resolver, final ReportDefinition definition) {
        try {
            // Scheduled run: throttle on (full per-batch and per-brand cool-downs).
            final ReportRunResult result = reportService.generate(definition, true);
            if (result.isSuccess()) {
                LOG.info("Report '{}' generated {} CSV(s), {} row(s) total",
                        definition.getReportId(), result.getCsvPaths().size(), result.getTotalRows());
            } else {
                final String detail = String.format("Report '%s' failed (config %s): %s",
                        definition.getReportId(), definition.getComponentPath(), result.getErrorMessage());
                LOG.error(detail);
                WorkfrontInboxNotifier.notifyFailure(resolver, "Report generation failed", detail,
                        definition.getComponentPath());
            }
        } catch (final Exception e) {
            final String detail = String.format("Report '%s' threw: %s",
                    definition.getReportId(), e.getMessage());
            LOG.error(detail, e);
            WorkfrontInboxNotifier.notifyFailure(resolver, "Report generation failed", detail,
                    definition.getComponentPath());
        }
    }

    private ResourceResolver getServiceResolver() throws LoginException {
        final Map<String, Object> authInfo =
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, (Object) SUBSERVICE);
        return resolverFactory.getServiceResourceResolver(authInfo);
    }
}
