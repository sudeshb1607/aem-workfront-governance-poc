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
package com.mysite.core.models.report;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Session;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.search.PredicateGroup;
import com.day.cq.search.Query;
import com.day.cq.search.QueryBuilder;
import com.day.cq.search.result.Hit;

/**
 * Reads Unpublished Pages Report configuration straight from the JCR. Used by
 * the scheduler and the Run-now servlet, which run without (or independently of)
 * the request-scoped {@code UnpublishedReportConfigModel}. Defaults are applied
 * for any field the author left empty, so every returned
 * {@link UnpublishedReportConfig} is immediately usable.
 */
public final class UnpublishedReportConfigReader {

    private static final Logger LOG = LoggerFactory.getLogger(UnpublishedReportConfigReader.class);

    private UnpublishedReportConfigReader() {
        // static utility
    }

    /**
     * Finds every instance of the configuration component under the search root
     * and builds one {@link UnpublishedReportConfig} per component.
     *
     * @param resolver     a resolver with read access to {@code /content}
     * @param queryBuilder the QueryBuilder service
     * @param searchRoot   the path to scan (typically {@code /content})
     * @return all configurations found; never {@code null}
     */
    public static List<UnpublishedReportConfig> readAll(final ResourceResolver resolver,
                                                        final QueryBuilder queryBuilder,
                                                        final String searchRoot) {
        final List<UnpublishedReportConfig> configs = new ArrayList<>();
        final Session session = resolver.adaptTo(Session.class);

        final Map<String, String> params = new HashMap<>();
        params.put("path", searchRoot);
        params.put("property", "sling:resourceType");
        params.put("property.value", ReportConfigConstants.RESOURCE_TYPE);
        params.put("p.limit", "-1");

        final Query query = queryBuilder.createQuery(PredicateGroup.create(params), session);
        for (final Hit hit : query.getResult().getHits()) {
            try {
                configs.add(readOne(hit.getResource()));
            } catch (final Exception e) {
                LOG.warn("Failed to read Unpublished Pages Report config component", e);
            }
        }
        LOG.debug("Discovered {} Unpublished Pages Report config component(s) under {}",
                configs.size(), searchRoot);
        return configs;
    }

    /**
     * Builds a single {@link UnpublishedReportConfig} from a configuration
     * component resource, applying defaults for any empty field.
     *
     * @param component the configuration component resource; must not be {@code null}
     * @return the immutable configuration snapshot
     */
    public static UnpublishedReportConfig readOne(final Resource component) {
        if (component == null) {
            throw new IllegalArgumentException("component resource must not be null");
        }
        final ValueMap vm = component.getValueMap();

        final List<String> scanRoots = readMultiValue(vm, ReportConfigConstants.PN_SCAN_ROOTS,
                ReportConfigConstants.DEFAULT_SCAN_ROOT);
        final List<String> excludePaths = readMultiValue(vm, ReportConfigConstants.PN_EXCLUDE_PATHS,
                ReportConfigConstants.DEFAULT_EXCLUDE_PATH);
        final List<ExcludeProperty> excludeProps = readExcludeProps(component);
        final int thresholdDays = readThresholdDays(vm);
        final String outputFolder = StringUtils.removeEnd(
                defaulted(vm.get(ReportConfigConstants.PN_OUTPUT_FOLDER, String.class),
                        ReportConfigConstants.DEFAULT_OUTPUT_FOLDER), "/");
        final String fileName = StringUtils.removeEnd(
                defaulted(vm.get(ReportConfigConstants.PN_FILE_NAME, String.class),
                        ReportConfigConstants.DEFAULT_FILE_NAME).trim(), ".csv");
        final boolean activateCsv = vm.get(ReportConfigConstants.PN_ACTIVATE_CSV,
                ReportConfigConstants.DEFAULT_ACTIVATE_CSV);
        final List<ReportColumn> columns = readColumns(component);

        return new UnpublishedReportConfig(component.getPath(), scanRoots, excludePaths, excludeProps,
                thresholdDays, outputFolder, fileName, activateCsv, columns);
    }

    private static List<String> readMultiValue(final ValueMap vm, final String name, final String defaultValue) {
        final String[] values = vm.get(name, String[].class);
        final List<String> result = new ArrayList<>();
        if (values != null) {
            for (final String value : values) {
                final String trimmed = StringUtils.trimToNull(value);
                if (trimmed != null) {
                    result.add(trimmed);
                }
            }
        }
        if (result.isEmpty()) {
            result.add(defaultValue);
        }
        return result;
    }

    private static List<ExcludeProperty> readExcludeProps(final Resource component) {
        final List<ExcludeProperty> props = new ArrayList<>();
        final Resource node = component.getChild(ReportConfigConstants.PN_EXCLUDE_PROPS);
        if (node != null) {
            for (final Resource row : node.getChildren()) {
                final ValueMap vm = row.getValueMap();
                final String name = StringUtils.trimToNull(
                        vm.get(ReportConfigConstants.PN_EXCLUDE_PROP_NAME, String.class));
                final String value = StringUtils.trimToNull(
                        vm.get(ReportConfigConstants.PN_EXCLUDE_PROP_VALUE, String.class));
                if (name != null && value != null) {
                    props.add(new ExcludeProperty(name, value));
                } else {
                    LOG.warn("Skipping incomplete exclude-property row at {}", row.getPath());
                }
            }
        }
        if (props.isEmpty()) {
            props.add(new ExcludeProperty(ReportConfigConstants.DEFAULT_EXCLUDE_PROP_NAME,
                    ReportConfigConstants.DEFAULT_EXCLUDE_PROP_VALUE));
        }
        return props;
    }

    private static List<ReportColumn> readColumns(final Resource component) {
        final List<ReportColumn> columns = new ArrayList<>();
        final Resource node = component.getChild(ReportConfigConstants.PN_COLUMNS);
        if (node != null) {
            for (final Resource row : node.getChildren()) {
                final ValueMap vm = row.getValueMap();
                final String header = StringUtils.trimToNull(
                        vm.get(ReportConfigConstants.PN_COLUMN_HEADER, String.class));
                final String source = StringUtils.trimToNull(
                        vm.get(ReportConfigConstants.PN_COLUMN_SOURCE, String.class));
                if (header != null && source != null) {
                    columns.add(new ReportColumn(header, source));
                } else {
                    LOG.warn("Skipping incomplete column row at {}", row.getPath());
                }
            }
        }
        if (columns.isEmpty()) {
            columns.addAll(defaultColumns());
        }
        return columns;
    }

    /**
     * @return the pre-seeded default column set: Hash, Title, Page URL, Last Published,
     *         Replication Action, Last Modified, Modified By, Template, Resource Type.
     */
    public static List<ReportColumn> defaultColumns() {
        return Collections.unmodifiableList(Arrays.asList(
                new ReportColumn("Hash", ReportConfigConstants.SOURCE_HASH),
                new ReportColumn("Title", ReportConfigConstants.SOURCE_TITLE),
                new ReportColumn("Page URL", ReportConfigConstants.SOURCE_URL),
                new ReportColumn("Last Published", "cq:lastReplicated"),
                new ReportColumn("Replication Action", "cq:lastReplicationAction"),
                new ReportColumn("Last Modified", "cq:lastModified"),
                new ReportColumn("Modified By", "cq:lastModifiedBy"),
                new ReportColumn("Template", "cq:template"),
                new ReportColumn("Resource Type", "sling:resourceType")));
    }

    private static int readThresholdDays(final ValueMap vm) {
        final Long asLong = vm.get(ReportConfigConstants.PN_THRESHOLD_DAYS, Long.class);
        if (asLong != null && asLong > 0) {
            return asLong.intValue();
        }
        final String asString = StringUtils.trimToNull(
                vm.get(ReportConfigConstants.PN_THRESHOLD_DAYS, String.class));
        if (asString != null) {
            try {
                final int parsed = Integer.parseInt(asString);
                if (parsed > 0) {
                    return parsed;
                }
            } catch (final NumberFormatException e) {
                LOG.warn("Invalid thresholdDays value '{}'; using default {}",
                        asString, ReportConfigConstants.DEFAULT_THRESHOLD_DAYS);
            }
        }
        return ReportConfigConstants.DEFAULT_THRESHOLD_DAYS;
    }

    private static String defaulted(final String value, final String defaultValue) {
        final String trimmed = StringUtils.trimToNull(value);
        return trimmed != null ? trimmed : defaultValue;
    }
}
