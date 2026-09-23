package com.mysite.core.services.impl;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.commons.Externalizer;
import com.day.cq.dam.api.AssetManager;
import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.Replicator;
import com.mysite.core.models.report.ExcludeProperty;
import com.mysite.core.models.report.ReportColumn;
import com.mysite.core.reports.BrandScope;
import com.mysite.core.reports.ReportDefinition;
import com.mysite.core.reports.ReportFilter;
import com.mysite.core.reports.ReportsConstants;
import com.mysite.core.reports.filter.AllLiveFilter;
import com.mysite.core.reports.filter.ArchiveAgedFilter;
import com.mysite.core.reports.filter.ExpiringPublishedFilter;
import com.mysite.core.reports.filter.LiveLongNoChildrenFilter;
import com.mysite.core.reports.filter.NotLiveStaleFilter;
import com.mysite.core.schedulers.SchedulerSupport;
import com.mysite.core.services.ReportGeneratorService;
import com.mysite.core.util.BrandUtil;
import com.mysite.core.util.CsvSupport;
import com.mysite.core.util.DateUtils;
import com.mysite.core.util.DurationUtil;
import com.mysite.core.util.PagePublicationUtil;
import com.mysite.core.util.PageHashUtil;

/**
 * Default {@link ReportGeneratorService}. Shared engine behind all five reports:
 * for each configured brand it walks the brand's content root as a bounded page
 * hierarchy traversal (index-independent, so it never trips Oak's node-read limit
 * on large repositories), applies the report's {@link ReportFilter} plus the
 * exclusion rules, resolves the configured columns, caps the result at
 * {@code maxRecords}, and writes {@code <outputFolder>/csv/<reportId>-<brand>.csv}
 * via the {@link AssetManager}. Each brand is isolated so one failure never
 * blocks the others.
 */
@Designate(ocd = ReportGeneratorServiceImpl.Config.class)
@Component(service = ReportGeneratorService.class)
public class ReportGeneratorServiceImpl implements ReportGeneratorService {

    private static final Logger LOG = LoggerFactory.getLogger(ReportGeneratorServiceImpl.class);

    /** Reuses the Workfront reporting system user (ACL covers the reports folders). */
    private static final String SUBSERVICE = "workfront-csv-write";

    private static final String CSV_MIME_TYPE = "text/csv";
    private static final String CSV_EXTENSION = ".csv";
    private static final String JCR_CONTENT = "jcr:content";
    private static final String PN_PRIMARY_TYPE = "jcr:primaryType";
    private static final String NT_PAGE = "cq:Page";
    private static final String PN_TITLE = "jcr:title";
    private static final String DATE_FORMAT_ISO = "yyyy-MM-dd'T'HH:mm:ssXXX";
    private static final String HTML_EXTENSION = ".html";

    @ObjectClassDefinition(
            name = "Workfront Report Generator Service",
            description = "Shared engine that generates the per-brand CSVs for the governance reports.")
    public @interface Config {

        @AttributeDefinition(name = "Page Batch Size",
                description = "Number of pages fetched per QueryBuilder batch. Keeps memory flat on large trees "
                        + "and bounds how long the author works before each cool-down.")
        int pageBatchSize() default 200;

        @AttributeDefinition(name = "Cool-down (seconds)",
                description = "Real cool-down taken after each batch and after each brand when throttling is on "
                        + "(scheduled runs), so a shared author stays responsive. Set to 0 to disable.")
        int cooldownSeconds() default 10;
    }

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private Replicator replicator;

    /** Optional: only used to build absolute publish URLs for the {@code :url} column. */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    private volatile Externalizer externalizer;

    private int pageBatchSize;
    private int cooldownSeconds;

    /**
     * Counted down in {@link #deactivate()}. The cool-downs wait on this latch, so
     * only a genuine component stop (shutdown / bundle refresh / reconfigure) ends
     * a run early — a spurious thread interrupt does not abort the remaining work.
     */
    private final CountDownLatch stopLatch = new CountDownLatch(1);

    @Activate
    protected void activate(final Config config) {
        this.pageBatchSize = config.pageBatchSize() > 0 ? config.pageBatchSize() : 200;
        this.cooldownSeconds = Math.max(0, config.cooldownSeconds());
        LOG.info("ReportGeneratorService activated. pageBatchSize={}, cooldownSeconds={}",
                pageBatchSize, cooldownSeconds);
    }

    @Deactivate
    protected void deactivate() {
        stopLatch.countDown();
    }

    @Override
    public ReportRunResult generate(final ReportDefinition definition) {
        return generate(definition, true);
    }

    @Override
    public ReportRunResult generate(final ReportDefinition definition, final boolean throttle) {
        final LocalDate today = LocalDate.now();
        switch (definition.getType()) {
            case ALL_LIVE:
                return generateAllLive(definition, today, throttle);
            case EXPIRING_PUBLISHED:
                return generateExpiringPublished(definition, today, throttle);
            case NOT_LIVE_STALE:
                return generateNotLiveStale(definition, today, throttle);
            case LIVE_LONG_NO_CHILDREN:
                return generateLiveLongNoChildren(definition, today, throttle);
            case ARCHIVE_AGED:
                return generateArchiveAged(definition, today, throttle);
            default:
                LOG.error("Unknown report type for config {}", definition.getComponentPath());
                return ReportRunResult.failure("Unknown report type");
        }
    }

    // ----------------------------------------------------- per-report creation methods
    // Each report has its own creation method that wires its selection rule, so a change
    // to one report cannot affect the others. They all share runReport(...) for the common
    // traverse + filter + CSV-write mechanics.

    /** Report 1 — all live (published) pages under the configured paths. */
    private ReportRunResult generateAllLive(final ReportDefinition def, final LocalDate today, final boolean throttle) {
        return runReport(def, new AllLiveFilter(), throttle);
    }

    /** Report 2 — published pages expiring within the threshold (or already expired). */
    private ReportRunResult generateExpiringPublished(final ReportDefinition def, final LocalDate today,
                                                      final boolean throttle) {
        return runReport(def, new ExpiringPublishedFilter(today, def.getThresholdDays()), throttle);
    }

    /** Report 3 — not-live pages not modified within the threshold window. */
    private ReportRunResult generateNotLiveStale(final ReportDefinition def, final LocalDate today,
                                                 final boolean throttle) {
        return runReport(def, new NotLiveStaleFilter(today, def.getThresholdMonths(), def.getStaleDateProp()),
                throttle);
    }

    /** Report 4 — live pages last published beyond the threshold, with no child page. */
    private ReportRunResult generateLiveLongNoChildren(final ReportDefinition def, final LocalDate today,
                                                       final boolean throttle) {
        return runReport(def, new LiveLongNoChildrenFilter(today, def.getThresholdMonths()), throttle);
    }

    /** Report 5 — pages in the archive folders aged within the [min,max] day window. */
    private ReportRunResult generateArchiveAged(final ReportDefinition def, final LocalDate today,
                                                final boolean throttle) {
        return runReport(def, new ArchiveAgedFilter(today, def.getArchiveMinDays(),
                def.getArchiveMaxDays(), def.getArchiveDateProp()), throttle);
    }

    /**
     * Shared engine invoked by each per-report method: for every configured brand it
     * traverses the brand's root(s), applies the report's {@code filter} plus the
     * exclusion rules, resolves the columns, caps at {@code maxRecords}, and writes
     * {@code <outputFolder>/csv/<reportId>-<brand>.csv}. Each brand is isolated.
     *
     * @param definition the report configuration
     * @param filter     the report's selection rule
     * @param throttle   when {@code true}, take a cool-down after each brand
     * @return the run outcome (paths written + total rows)
     */
    private ReportRunResult runReport(final ReportDefinition definition, final ReportFilter filter,
                                      final boolean throttle) {
        final List<String> csvPaths = new ArrayList<>();
        long totalRows = 0;
        final long reportStartNanos = System.nanoTime();
        try (ResourceResolver resolver = getServiceResolver()) {
            final String csvFolder = definition.getOutputFolder() + "/csv";
            final List<BrandScope> brands = definition.getBrands();

            LOG.info("[STEP] Report '{}' START — {} brand(s), throttle={}, cap={}, output={}",
                    definition.getReportId(), brands.size(), throttle,
                    definition.getMaxRecords() == 0 ? "unlimited" : definition.getMaxRecords(), csvFolder);

            for (int i = 0; i < brands.size(); i++) {
                final BrandScope brand = brands.get(i);
                try {
                    final String csvPath = csvFolder + "/" + definition.getReportId()
                            + "-" + BrandUtil.sanitize(brand.getBrand()) + CSV_EXTENSION;
                    final long rows = generateBrand(resolver, definition, filter, brand, csvPath, throttle);
                    csvPaths.add(csvPath);
                    totalRows += rows;
                    LOG.info("Report '{}' brand '{}': {} row(s) -> {}",
                            definition.getReportId(), brand.getBrand(), rows, csvPath);
                } catch (final Exception e) {
                    LOG.error("Report '{}' failed for brand '{}' (config {})",
                            definition.getReportId(), brand.getBrand(), definition.getComponentPath(), e);
                }
                // Breathe after each brand (except the last) so the shared author recovers.
                if (throttle && cooldownSeconds > 0 && i < brands.size() - 1
                        && !coolDown(definition.getReportId(), "brand")) {
                    break;
                }
            }
            final long reportMillis = (System.nanoTime() - reportStartNanos) / 1_000_000L;
            LOG.info("[STEP] Report '{}' DONE — {} row(s) across {} CSV(s) in {} ({} ms)",
                    definition.getReportId(), totalRows, csvPaths.size(),
                    DurationUtil.format(reportMillis), reportMillis);
            return ReportRunResult.success(csvPaths, totalRows);
        } catch (final LoginException e) {
            LOG.error("Could not obtain service resolver for report {}", definition.getReportId(), e);
            return ReportRunResult.failure(e.getMessage());
        } catch (final Exception e) {
            LOG.error("Unexpected error generating report {}", definition.getReportId(), e);
            return ReportRunResult.failure(e.getMessage());
        }
    }

    /**
     * Generates one brand's CSV and returns the number of data rows written.
     * Times the brand and logs the elapsed duration in human-readable form.
     */
    private long generateBrand(final ResourceResolver resolver, final ReportDefinition def,
                               final ReportFilter filter, final BrandScope brand, final String csvPath,
                               final boolean throttle)
            throws Exception {
        final long startNanos = System.nanoTime();
        final StringBuilder csv = new StringBuilder();
        appendHeader(csv, def.getColumns());

        final int cap = def.getMaxRecords(); // 0 = unlimited (all-live only)
        long rows = 0;
        final String root = brand.getRoot();
        LOG.info("[STEP] Report '{}' brand '{}': traversing {}", def.getReportId(), brand.getBrand(), root);
        if (resolver.getResource(root) == null) {
            LOG.warn("Report '{}' brand '{}': root {} does not exist; writing header-only CSV",
                    def.getReportId(), brand.getBrand(), root);
        } else {
            rows = appendRoot(resolver, root, def, filter, csv, cap, throttle);
        }

        LOG.debug("[STEP] Report '{}' brand '{}': writing CSV {}", def.getReportId(), brand.getBrand(), csvPath);
        writeAsset(resolver, csvPath, csv.toString());
        if (def.isActivateCsv()) {
            LOG.debug("[STEP] Report '{}' brand '{}': activating {}", def.getReportId(), brand.getBrand(), csvPath);
            replicate(resolver, csvPath);
        }

        final long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        LOG.info("Report '{}' brand '{}': completed {} rows in {} ({} ms)",
                def.getReportId(), brand.getBrand(), rows,
                DurationUtil.format(elapsedMillis), elapsedMillis);
        return rows;
    }

    /**
     * Appends qualifying pages under the brand's root, respecting the record cap.
     *
     * <p>Walks the page hierarchy directly (iterative DFS over {@code cq:Page}
     * descendants) instead of running a QueryBuilder/Oak query. A subtree walk
     * reads only the pages under {@code root} and never descends into
     * {@code jcr:content} component trees, so its cost is proportional to the
     * subtree — not the whole repository. This avoids the Oak "traversed more than
     * N nodes" failure that a {@code type=cq:Page} + {@code orderby=path} query
     * hits when the serving index does not push the path restriction down (it
     * would otherwise stream every page in the repo and filter by path in memory).
     * The natural child order is deterministic, so there are no duplicate rows and
     * no dedup Set is needed.</p>
     *
     * <p>Every {@code pageBatchSize} pages the JCR session is refreshed (so it
     * never goes stale on a long run) and, when {@code throttle} is on, a real
     * cool-down is taken so a shared author can breathe.</p>
     *
     * @param cap      the per-brand record cap ({@code 0} = unlimited)
     * @param throttle when {@code true}, cool-down between batches
     * @return the number of rows appended for this brand
     */
    private long appendRoot(final ResourceResolver resolver, final String root, final ReportDefinition def,
                            final ReportFilter filter, final StringBuilder csv,
                            final int cap, final boolean throttle) {
        final Resource rootResource = resolver.getResource(root);
        if (rootResource == null) {
            return 0;
        }
        final Session session = resolver.adaptTo(Session.class);
        long appended = 0;
        long visited = 0;

        // Iterative DFS over the page tree. We push only cq:Page children, so the
        // walk stays inside the page hierarchy and never reads component subtrees.
        final Deque<Resource> stack = new ArrayDeque<>();
        stack.push(rootResource);

        while (!stack.isEmpty()) {
            final Resource current = stack.pop();
            for (final Resource child : current.getChildren()) {
                if (isPage(child)) {
                    stack.push(child);
                }
            }
            if (!isPage(current)) {
                continue; // the root may be a folder; only pages produce rows
            }

            visited++;
            try {
                if (appendPageRowIfMatched(resolver, current, def, filter, csv)) {
                    appended++;
                    if (cap > 0 && appended >= cap) {
                        return appended;
                    }
                }
            } catch (final Exception e) {
                LOG.warn("Skipping page during report '{}'", def.getReportId(), e);
            }

            if (visited % pageBatchSize == 0) {
                // Keep the session fresh across a long traversal (avoids stale/timeout).
                refreshSession(session, def);
                LOG.info("Report '{}': visited {} page(s), {} row(s) so far",
                        def.getReportId(), visited, appended);
                if (throttle && cooldownSeconds > 0 && !coolDown(def.getReportId(), "batch")) {
                    break;
                }
            }
        }
        return appended;
    }

    private static boolean isPage(final Resource resource) {
        return resource != null
                && NT_PAGE.equals(resource.getValueMap().get(PN_PRIMARY_TYPE, String.class));
    }

    /**
     * Logs and takes one throttled cool-down (used between batches and between
     * brands). Shared so the two call sites stay identical and simple.
     *
     * @param reportId the report id, for the log line
     * @param phase    the phase label ({@code "batch"} or {@code "brand"})
     * @return {@code true} to continue; {@code false} if the component is
     *         deactivating and the run should stop
     */
    private boolean coolDown(final String reportId, final String phase) {
        LOG.info("Report '{}': {} cool-off {}", reportId, phase, DurationUtil.format(cooldownSeconds * 1000L));
        if (!SchedulerSupport.await(cooldownSeconds, stopLatch)) {
            LOG.warn("Report '{}': deactivating during {} cool-down; stopping", reportId, phase);
            return false;
        }
        return true;
    }

    private void refreshSession(final Session session, final ReportDefinition def) {
        if (session == null) {
            return;
        }
        try {
            session.refresh(false);
        } catch (final RepositoryException e) {
            LOG.debug("Session refresh failed during report '{}' (continuing)", def.getReportId(), e);
        }
    }

    /**
     * @return {@code true} when a row was appended for this page. Package-private
     *         so the exclusion + column-resolution path can be unit-tested without
     *         QueryBuilder.
     */
    boolean appendPageRowIfMatched(final ResourceResolver resolver, final Resource pageResource,
                                           final ReportDefinition def, final ReportFilter filter,
                                           final StringBuilder csv) {
        if (pageResource == null) {
            return false;
        }
        final String path = pageResource.getPath();
        if (isExcludedPath(path, def.getExcludePaths())) {
            return false;
        }
        final Resource content = pageResource.getChild(JCR_CONTENT);
        final ValueMap contentVm = content != null ? content.getValueMap() : ValueMap.EMPTY;
        final ValueMap pageVm = pageResource.getValueMap();

        if (isExcludedByProperty(contentVm, pageVm, def.getExcludeProperty())) {
            return false;
        }
        if (!filter.accept(pageResource, contentVm)) {
            return false;
        }

        appendRow(resolver, pageResource, content, contentVm, def.getColumns(), csv);
        return true;
    }

    // ------------------------------------------------------------------ exclusions

    private boolean isExcludedPath(final String path, final List<String> excludePaths) {
        for (final String exclude : excludePaths) {
            final String prefix = StringUtils.removeEnd(exclude, "/");
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Applies the single optional exclude-property condition. When {@code ep} is
     * {@code null} (no property configured) no comparison is made. The property is
     * read from {@code jcr:content} first, then the page node.
     *
     * @return {@code true} when the page should be excluded
     */
    private boolean isExcludedByProperty(final ValueMap contentVm, final ValueMap pageVm,
                                         final ExcludeProperty ep) {
        if (ep == null) {
            return false;
        }
        final Object value = contentVm.containsKey(ep.getName())
                ? contentVm.get(ep.getName())
                : (pageVm.containsKey(ep.getName()) ? pageVm.get(ep.getName()) : null);
        return value != null && ep.getValue().equals(String.valueOf(value));
    }

    // ------------------------------------------------------------------ CSV rows

    private void appendHeader(final StringBuilder csv, final List<ReportColumn> columns) {
        final List<String> headers = new ArrayList<>(columns.size());
        for (final ReportColumn column : columns) {
            headers.add(column.getHeader());
        }
        CsvSupport.appendRow(csv, headers);
    }

    private void appendRow(final ResourceResolver resolver, final Resource pageResource, final Resource content,
                           final ValueMap contentVm, final List<ReportColumn> columns, final StringBuilder csv) {
        final boolean published = PagePublicationUtil.isPublished(content);
        final List<String> values = new ArrayList<>(columns.size());
        for (final ReportColumn column : columns) {
            values.add(resolveColumn(resolver, pageResource, contentVm, column, published));
        }
        CsvSupport.appendRow(csv, values);
    }

    private String resolveColumn(final ResourceResolver resolver, final Resource pageResource,
                                 final ValueMap contentVm, final ReportColumn column, final boolean published) {
        final String source = column.getSource();
        switch (source) {
            case ReportsConstants.SOURCE_TITLE:
                return contentVm.get(PN_TITLE, pageResource.getName());
            case ReportsConstants.SOURCE_PATH:
                return pageResource.getPath();
            case ReportsConstants.SOURCE_URL:
                return buildUrl(resolver, pageResource.getPath());
            case ReportsConstants.SOURCE_HASH:
                return PageHashUtil.hash(buildUrl(resolver, pageResource.getPath()));
            case ReportsConstants.SOURCE_BRAND:
                return BrandUtil.brandName(pageResource.getPath());
            case ReportsConstants.SOURCE_PUBLISHED:
                return published ? "True" : "False";
            case ReportsConstants.SOURCE_DAYS_TO_REVIEW:
                return daysToReview(contentVm);
            default:
                return formatValue(contentVm.get(source));
        }
    }

    private String daysToReview(final ValueMap contentVm) {
        final LocalDate review = DateUtils.readLocalDate(contentVm, ReportsConstants.PN_REVIEW_EXPIRY);
        return review != null ? Long.toString(ChronoUnit.DAYS.between(LocalDate.now(), review)) : "";
    }

    private String buildUrl(final ResourceResolver resolver, final String path) {
        final Externalizer ext = this.externalizer;
        if (ext != null) {
            try {
                return ext.publishLink(resolver, path) + HTML_EXTENSION;
            } catch (final Exception e) {
                LOG.debug("Externalizer could not build publish link for {}; falling back", path, e);
            }
        }
        return path + HTML_EXTENSION;
    }

    private String formatValue(final Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Calendar) {
            return new SimpleDateFormat(DATE_FORMAT_ISO).format(((Calendar) value).getTime());
        }
        if (value instanceof Date) {
            return new SimpleDateFormat(DATE_FORMAT_ISO).format((Date) value);
        }
        if (value instanceof Object[]) {
            final Object[] array = (Object[]) value;
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < array.length; i++) {
                if (i > 0) {
                    sb.append(';');
                }
                sb.append(formatValue(array[i]));
            }
            return sb.toString();
        }
        return String.valueOf(value);
    }

    // ------------------------------------------------------------------ IO

    // Package-private so a test can override the DAM write/replicate seams and
    // exercise the full generate() flow without AssetManager.
    void writeAsset(final ResourceResolver resolver, final String csvPath, final String csvContent)
            throws Exception {
        final AssetManager assetManager = resolver.adaptTo(AssetManager.class);
        if (assetManager == null) {
            throw new IllegalStateException("Could not adapt ResourceResolver to AssetManager");
        }
        try (InputStream in = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8))) {
            assetManager.createAsset(csvPath, in, CSV_MIME_TYPE, true);
        }
        resolver.commit();
    }

    void replicate(final ResourceResolver resolver, final String csvPath) {
        try {
            final Session session = resolver.adaptTo(Session.class);
            replicator.replicate(session, ReplicationActionType.ACTIVATE, csvPath);
        } catch (final Exception e) {
            LOG.warn("Replication of {} failed; asset saved on author", csvPath, e);
        }
    }

    private ResourceResolver getServiceResolver() throws LoginException {
        final Map<String, Object> authInfo =
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, (Object) SUBSERVICE);
        return resolverFactory.getServiceResourceResolver(authInfo);
    }
}
