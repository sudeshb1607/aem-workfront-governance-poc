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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

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
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.dam.api.AssetManager;
import com.day.cq.replication.ReplicationActionType;
import com.day.cq.replication.Replicator;
import com.day.cq.search.PredicateGroup;
import com.day.cq.search.Query;
import com.day.cq.search.QueryBuilder;
import com.day.cq.search.result.Hit;
import com.day.cq.search.result.SearchResult;
import com.mysite.core.models.workfront.ContentTreeConfig;
import com.mysite.core.services.WorkfrontCsvGeneratorService;

/**
 * Default implementation of {@link WorkfrontCsvGeneratorService}.
 *
 * <p>Pages are traversed in bounded batches via QueryBuilder ({@code p.offset}/
 * {@code p.limit}, {@code pageBatchSize} pages per batch) so only one batch is
 * held at a time. Validated for content trees of up to ~10,000 pages (~20
 * batches). The CSV for a tree is assembled and written into the DAM through the
 * {@link AssetManager} using a dedicated service user in a single asset write,
 * then activated via the {@link Replicator}. Replication failure is treated as
 * non-fatal: the asset survives on author and the verification scheduler will
 * republish it. For trees well beyond ~50k pages, switch to a streaming query
 * and file-backed write (see {@code docs/workfront-csv-export.md}).</p>
 */
@Designate(ocd = WorkfrontCsvGeneratorServiceImpl.Config.class)
@Component(service = WorkfrontCsvGeneratorService.class)
public class WorkfrontCsvGeneratorServiceImpl implements WorkfrontCsvGeneratorService {

    private static final Logger LOG = LoggerFactory.getLogger(WorkfrontCsvGeneratorServiceImpl.class);

    /** Subservice name mapped to the {@code workfront-csv-service} system user. */
    private static final String SUBSERVICE = "workfront-csv-write";

    private static final String CSV_MIME_TYPE = "text/csv";
    private static final String CSV_EXTENSION = ".csv";
    private static final String CSV_HEADER = "Title,Path,Last Modified,Template";
    private static final String NEWLINE = "\r\n";

    @ObjectClassDefinition(
            name = "Workfront CSV Generator Service",
            description = "Generates page-metadata CSV exports into the DAM for Workfront dashboards.")
    public @interface Config {

        @AttributeDefinition(
                name = "DAM Root Path",
                description = "DAM folder the generated CSV files are written into.")
        String damRootPath() default "/content/dam/mysite/workfront-dashboard";

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

    private String damRootPath;
    private int pageBatchSize;

    @Activate
    protected void activate(final Config config) {
        this.damRootPath = StringUtils.removeEnd(
                StringUtils.defaultString(config.damRootPath(), "/content/dam/mysite/workfront-dashboard").trim(), "/");
        this.pageBatchSize = config.pageBatchSize() > 0 ? config.pageBatchSize() : 500;
        LOG.info("WorkfrontCsvGeneratorService activated. damRootPath={}, pageBatchSize={}",
                damRootPath, pageBatchSize);
    }

    @Override
    public String getDamRootPath() {
        return damRootPath;
    }

    @Override
    public CsvGenerationResult generateCsv(final ContentTreeConfig treeConfig) {
        final String csvName = StringUtils.removeEnd(treeConfig.getCsvName().trim(), CSV_EXTENSION);
        final String csvPath = damRootPath + "/" + csvName + CSV_EXTENSION;

        try (ResourceResolver resolver = getServiceResolver()) {
            final Resource treeRoot = resolver.getResource(treeConfig.getContentTreePath());
            if (treeRoot == null) {
                final String msg = "Content tree path does not exist: " + treeConfig.getContentTreePath();
                LOG.warn(msg);
                return CsvGenerationResult.failure(csvPath, msg);
            }

            final StringBuilder csv = new StringBuilder(CSV_HEADER).append(NEWLINE);
            final long pageCount = appendPages(resolver, treeConfig.getContentTreePath(), csv);

            writeAsset(resolver, csvPath, csv.toString());
            replicate(resolver, csvPath);

            LOG.info("Generated CSV {} with {} page(s) from {}", csvPath, pageCount,
                    treeConfig.getContentTreePath());
            return CsvGenerationResult.success(csvPath, pageCount);

        } catch (final Exception e) {
            LOG.error("Failed to generate CSV {} for tree {}", csvPath, treeConfig.getContentTreePath(), e);
            return CsvGenerationResult.failure(csvPath, e.getMessage());
        }
    }

    /**
     * Streams all {@code cq:Page} nodes under the given path into the CSV buffer
     * using bounded QueryBuilder batches.
     *
     * @return the number of pages appended
     */
    private long appendPages(final ResourceResolver resolver, final String treePath, final StringBuilder csv) {
        final Session session = resolver.adaptTo(Session.class);
        long total = 0;
        int offset = 0;

        while (true) {
            final Map<String, String> params = new HashMap<>();
            params.put("path", treePath);
            // Include the tree root page itself, not just its descendants.
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
                try {
                    appendPageRow(hit.getResource(), csv);
                    batchCount++;
                } catch (final Exception e) {
                    LOG.warn("Skipping page during CSV export", e);
                }
            }

            total += batchCount;
            if (batchCount < pageBatchSize) {
                break;
            }
            offset += pageBatchSize;
        }
        return total;
    }

    private void appendPageRow(final Resource pageResource, final StringBuilder csv) {
        if (pageResource == null) {
            return;
        }
        final Resource content = pageResource.getChild("jcr:content");
        final ValueMap vm = content != null ? content.getValueMap() : ValueMap.EMPTY;

        final String title = vm.get("jcr:title", pageResource.getName());
        final String path = pageResource.getPath();
        final String lastModified = vm.get("cq:lastModified", "");
        final String template = vm.get("cq:template", "");

        csv.append(escape(title)).append(',')
                .append(escape(path)).append(',')
                .append(escape(lastModified)).append(',')
                .append(escape(template)).append(NEWLINE);
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
            // Non-fatal: asset is saved on author; verification scheduler will republish.
            LOG.warn("Replication of {} failed; will be retried by verification scheduler", csvPath, e);
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
