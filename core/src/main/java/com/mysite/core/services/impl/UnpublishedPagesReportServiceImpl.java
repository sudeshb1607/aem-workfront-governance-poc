/*
 *  Copyright 2024 Adobe Systems Incorporated
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.mysite.core.services.impl;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.jcr.Session;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
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
import com.day.cq.search.PredicateGroup;
import com.day.cq.search.Query;
import com.day.cq.search.QueryBuilder;
import com.day.cq.search.result.Hit;
import com.day.cq.search.result.SearchResult;
import com.mysite.core.models.report.ExcludeProperty;
import com.mysite.core.models.report.ReportColumn;
import com.mysite.core.models.report.ReportConfigConstants;
import com.mysite.core.models.report.UnpublishedReportConfig;
import com.mysite.core.services.UnpublishedPagesReportService;
import com.mysite.core.util.PageHashUtil;

/**
 * Default implementation of {@link UnpublishedPagesReportService}.
 *
 * <p>Each configured scan root is traversed in bounded QueryBuilder batches
 * ({@code p.offset}/{@code p.limit}) so only one batch of {@code cq:Page} hits is
 * held at a time. Per page the {@code jcr:content} ValueMap is inspected: pages
 * under an excluded path or matching an exclude-property condition are skipped,
 * and a page is reported only when it has never been activated or its last
 * activation is older than the configured threshold. Reported pages emit one CSV
 * row per configured column. The CSV is written into the DAM via the
 * {@link AssetManager} and, when enabled, activated through the
 * {@link Replicator} (activation failure is non-fatal).</p>
 */
@Designate(ocd = UnpublishedPagesReportServiceImpl.Config.class)
@Component(service = UnpublishedPagesReportService.class)
public class UnpublishedPagesReportServiceImpl implements UnpublishedPagesReportService {

    private static final Logger LOG = LoggerFactory.getLogger(UnpublishedPagesReportServiceImpl.class);

    /** Subservice name mapped to the {@code unpublished-report-service} system user. */
    private static final String SUBSERVICE = "unpublished-report-write";

    private static final String CSV_MIME_TYPE = "text/csv";
    private static final String CSV_EXTENSION = ".csv";
    private static final String NEWLINE = "\r\n";
    private static final String ACTION_ACTIVATE = "Activate";
    private static final String JCR_CONTENT = "jcr:content";
    private static final String PN_TITLE = "jcr:title";
    private static final String DATE_FORMAT_ISO = "yyyy-MM-dd'T'HH:mm:ssXXX";
    private static final String DATE_FORMAT_FILE = "yyyyMMdd";

    @ObjectClassDefinition(
            name = "Unpublished Pages Report Service",
            description = "Generates CSV reports of pages not published within a configured window.")
    public @interface Config {

        @AttributeDefinition(
                name = "Page Batch Size",
                description = "Number of pages fetched per QueryBuilder batch. Keeps memory flat on large trees.")
        int pageBatchSize() default 500;
    }

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private QueryBuilder queryBuilder;

    @Reference
    private Replicator replicator;

    /** Optional: only used to build absolute publish URLs for the {@code :url} column. */
    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    private volatile Externalizer externalizer;

    private int pageBatchSize;

    @Activate
    protected void activate(final Config config) {
        this.pageBatchSize = config.pageBatchSize() > 0 ? config.pageBatchSize() : 500;
        LOG.info("UnpublishedPagesReportService activated. pageBatchSize={}", pageBatchSize);
    }

    @Override
    public ReportResult generateReport(final UnpublishedReportConfig config) {
        final String date = new SimpleDateFormat(DATE_FORMAT_FILE).format(new Date());
        final String csvPath = config.getOutputFolder() + "/" + config.getFileName() + "-" + date + CSV_EXTENSION;

        try (ResourceResolver resolver = getServiceResolver()) {
            final StringBuilder csv = new StringBuilder();
            appendHeader(csv, config.getColumns());

            long rows = 0;
            for (final String scanRoot : config.getScanRoots()) {
                if (resolver.getResource(scanRoot) == null) {
                    LOG.warn("Scan root {} does not exist; skipping", scanRoot);
                    continue;
                }
                rows += appendUnpublishedPages(resolver, scanRoot, config, csv);
            }

            writeAsset(resolver, csvPath, csv.toString());
            if (config.isActivateCsv()) {
                replicate(resolver, csvPath);
            }

            LOG.info("Generated unpublished-pages report {} with {} row(s)", csvPath, rows);
            return ReportResult.success(csvPath, rows);

        } catch (final Exception e) {
            LOG.error("Failed to generate unpublished-pages report {} for config {}",
                    csvPath, config.getComponentPath(), e);
            return ReportResult.failure(csvPath, e.getMessage());
        }
    }

    private void appendHeader(final StringBuilder csv, final List<ReportColumn> columns) {
        boolean first = true;
        for (final ReportColumn column : columns) {
            if (!first) {
                csv.append(',');
            }
            csv.append(escape(column.getHeader()));
            first = false;
        }
        csv.append(NEWLINE);
    }

    /**
     * Traverses all {@code cq:Page} nodes under the scan root in bounded batches,
     * appending a CSV row for every page that qualifies as unpublished.
     *
     * @return the number of rows appended
     */
    private long appendUnpublishedPages(final ResourceResolver resolver, final String scanRoot,
                                        final UnpublishedReportConfig config, final StringBuilder csv) {
        final Session session = resolver.adaptTo(Session.class);
        final long thresholdMillis = System.currentTimeMillis()
                - TimeUnit.DAYS.toMillis(config.getThresholdDays());
        long total = 0;
        int offset = 0;

        while (true) {
            final Map<String, String> params = new HashMap<>();
            params.put("path", scanRoot);
            params.put("path.self", "true");
            params.put("type", "cq:Page");
            params.put("p.limit", String.valueOf(pageBatchSize));
            params.put("p.offset", String.valueOf(offset));
            params.put("p.guessTotal", "true");
            params.put("orderby", "path");

            final Query query = queryBuilder.createQuery(PredicateGroup.create(params), session);
            final SearchResult result = query.getResult();

            int batchCount = 0;
            for (final Hit hit : result.getHits()) {
                batchCount++;
                try {
                    if (appendPageRowIfUnpublished(resolver, hit.getResource(), config, thresholdMillis, csv)) {
                        total++;
                    }
                } catch (final Exception e) {
                    LOG.warn("Skipping page during unpublished-pages report", e);
                }
            }

            if (batchCount < pageBatchSize) {
                break;
            }
            offset += pageBatchSize;
        }
        return total;
    }

    /**
     * @return {@code true} when a row was appended for the page.
     */
    private boolean appendPageRowIfUnpublished(final ResourceResolver resolver, final Resource pageResource,
                                               final UnpublishedReportConfig config, final long thresholdMillis,
                                               final StringBuilder csv) {
        if (pageResource == null) {
            return false;
        }
        final String path = pageResource.getPath();
        if (isExcludedPath(path, config.getExcludePaths())) {
            return false;
        }

        final Resource content = pageResource.getChild(JCR_CONTENT);
        final ValueMap contentVm = content != null ? content.getValueMap() : ValueMap.EMPTY;
        final ValueMap pageVm = pageResource.getValueMap();

        if (isExcludedByProperty(contentVm, pageVm, config.getExcludeProps())) {
            return false;
        }
        if (!isUnpublished(contentVm, thresholdMillis)) {
            return false;
        }

        appendRow(resolver, pageResource, contentVm, config.getColumns(), csv);
        return true;
    }

    private boolean isExcludedPath(final String path, final List<String> excludePaths) {
        for (final String exclude : excludePaths) {
            final String prefix = StringUtils.removeEnd(exclude, "/");
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private boolean isExcludedByProperty(final ValueMap contentVm, final ValueMap pageVm,
                                         final List<ExcludeProperty> excludeProps) {
        for (final ExcludeProperty ep : excludeProps) {
            final Object value = contentVm.containsKey(ep.getName())
                    ? contentVm.get(ep.getName())
                    : (pageVm.containsKey(ep.getName()) ? pageVm.get(ep.getName()) : null);
            if (value != null && ep.getValue().equals(String.valueOf(value))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A page is unpublished when it has never been activated, its last
     * replication action was not an Activate, or its last activation is older
     * than the threshold.
     */
    private boolean isUnpublished(final ValueMap contentVm, final long thresholdMillis) {
        final Calendar lastReplicated = contentVm.get("cq:lastReplicated", Calendar.class);
        if (lastReplicated == null) {
            return true;
        }
        final String action = contentVm.get("cq:lastReplicationAction", String.class);
        if (!ACTION_ACTIVATE.equals(action)) {
            return true;
        }
        return lastReplicated.getTimeInMillis() < thresholdMillis;
    }

    private void appendRow(final ResourceResolver resolver, final Resource pageResource,
                           final ValueMap contentVm, final List<ReportColumn> columns, final StringBuilder csv) {
        boolean first = true;
        for (final ReportColumn column : columns) {
            if (!first) {
                csv.append(',');
            }
            csv.append(escape(resolveColumn(resolver, pageResource, contentVm, column)));
            first = false;
        }
        csv.append(NEWLINE);
    }

    private String resolveColumn(final ResourceResolver resolver, final Resource pageResource,
                                 final ValueMap contentVm, final ReportColumn column) {
        final String source = column.getSource();
        if (ReportConfigConstants.SOURCE_TITLE.equals(source)) {
            return contentVm.get(PN_TITLE, pageResource.getName());
        }
        if (ReportConfigConstants.SOURCE_PATH.equals(source)) {
            return pageResource.getPath();
        }
        if (ReportConfigConstants.SOURCE_URL.equals(source)) {
            return buildUrl(resolver, pageResource.getPath());
        }
        if (ReportConfigConstants.SOURCE_HASH.equals(source)) {
            return PageHashUtil.hash(buildUrl(resolver, pageResource.getPath()));
        }
        return formatValue(contentVm.get(source));
    }

    private String buildUrl(final ResourceResolver resolver, final String path) {
        final Externalizer ext = this.externalizer;
        if (ext != null) {
            try {
                return ext.publishLink(resolver, path) + ".html";
            } catch (final Exception e) {
                LOG.debug("Externalizer could not build publish link for {}; falling back", path, e);
            }
        }
        return path + ".html";
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

    private void writeAsset(final ResourceResolver resolver, final String csvPath, final String csvContent)
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

    private void replicate(final ResourceResolver resolver, final String csvPath) {
        try {
            final Session session = resolver.adaptTo(Session.class);
            replicator.replicate(session, ReplicationActionType.ACTIVATE, csvPath);
        } catch (final Exception e) {
            // Non-fatal: the asset is saved on author even if activation fails.
            LOG.warn("Replication of {} failed; asset saved on author", csvPath, e);
        }
    }

    private ResourceResolver getServiceResolver() throws LoginException {
        final Map<String, Object> authInfo =
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, (Object) SUBSERVICE);
        return resolverFactory.getServiceResourceResolver(authInfo);
    }

    /**
     * Escapes a value per RFC 4180: wraps in quotes when it contains a comma,
     * quote, or newline, and doubles embedded quotes.
     */
    private static String escape(final String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
