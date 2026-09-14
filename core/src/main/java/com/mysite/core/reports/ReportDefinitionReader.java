package com.mysite.core.reports;

import java.util.ArrayList;
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
import com.mysite.core.models.report.ExcludeProperty;
import com.mysite.core.models.report.ReportColumn;

/**
 * Reads the five report configuration components straight from the JCR, applying
 * defaults for any field the author left empty. Used by the CSV scheduler and the
 * Run-now servlet. The {@link ReportType} is derived from the component's
 * {@code sling:resourceType}.
 */
public final class ReportDefinitionReader {

    private static final Logger LOG = LoggerFactory.getLogger(ReportDefinitionReader.class);

    private ReportDefinitionReader() {
        // static utility
    }

    /**
     * Finds every report configuration component (all five types) under the
     * search root and builds one {@link ReportDefinition} per component.
     *
     * @param resolver     a resolver with read access to {@code /content}
     * @param queryBuilder the QueryBuilder service
     * @param searchRoot   the path to scan (typically {@code /content})
     * @return all configurations found; never {@code null}
     */
    public static List<ReportDefinition> readAll(final ResourceResolver resolver,
                                                 final QueryBuilder queryBuilder,
                                                 final String searchRoot) {
        final List<ReportDefinition> definitions = new ArrayList<>();
        final Session session = resolver.adaptTo(Session.class);

        for (final ReportType type : ReportType.values()) {
            final Map<String, String> params = new HashMap<>();
            params.put("path", searchRoot);
            params.put("property", "sling:resourceType");
            params.put("property.value", type.getResourceType());
            params.put("p.limit", "-1");

            final Query query = queryBuilder.createQuery(PredicateGroup.create(params), session);
            for (final Hit hit : query.getResult().getHits()) {
                try {
                    definitions.add(readOne(hit.getResource()));
                } catch (final Exception e) {
                    LOG.warn("Failed to read report config component for type {}", type, e);
                }
            }
        }
        LOG.debug("Discovered {} report config component(s) under {}", definitions.size(), searchRoot);
        return definitions;
    }

    /**
     * Builds a single {@link ReportDefinition} from a config component resource,
     * applying defaults for any empty field.
     *
     * @param component the configuration component resource; must not be {@code null}
     * @return the immutable configuration snapshot
     * @throws IllegalArgumentException if the resource type is not a known report type
     */
    public static ReportDefinition readOne(final Resource component) {
        if (component == null) {
            throw new IllegalArgumentException("component resource must not be null");
        }
        final ValueMap vm = component.getValueMap();
        final ReportType type = ReportType.fromResourceType(component.getResourceType());
        if (type == null) {
            throw new IllegalArgumentException("Not a report config component: " + component.getPath());
        }

        final List<BrandScope> brands = readBrands(component);
        final int thresholdDays = readInt(vm, ReportsConstants.PN_THRESHOLD_DAYS,
                ReportsConstants.DEFAULT_THRESHOLD_DAYS);
        final int thresholdMonths = readInt(vm, ReportsConstants.PN_THRESHOLD_MONTHS, defaultMonths(type));
        final String staleDateProp = defaulted(vm.get(ReportsConstants.PN_STALE_DATE_PROP, String.class),
                ReportsConstants.DEFAULT_STALE_DATE_PROP);
        final int archiveMinDays = readInt(vm, ReportsConstants.PN_ARCHIVE_MIN_DAYS,
                ReportsConstants.DEFAULT_ARCHIVE_MIN_DAYS);
        final int archiveMaxDays = readInt(vm, ReportsConstants.PN_ARCHIVE_MAX_DAYS,
                ReportsConstants.DEFAULT_ARCHIVE_MAX_DAYS);
        final String archiveDateProp = defaulted(vm.get(ReportsConstants.PN_ARCHIVE_DATE_PROP, String.class),
                ReportsConstants.DEFAULT_ARCHIVE_DATE_PROP);
        final List<String> excludePaths = readMultiValue(vm, ReportsConstants.PN_EXCLUDE_PATHS);
        final ExcludeProperty excludeProperty = readExcludeProperty(vm);
        final int maxRecords = readInt(vm, ReportsConstants.PN_MAX_RECORDS, type.getDefaultMaxRecords());
        final String outputFolder = StringUtils.removeEnd(
                defaulted(vm.get(ReportsConstants.PN_OUTPUT_FOLDER, String.class),
                        type.getDefaultOutputFolder()), "/");
        final boolean activateCsv = vm.get(ReportsConstants.PN_ACTIVATE_CSV, false);
        final List<ReportColumn> columns = readColumns(component);

        return new ReportDefinition(component.getPath(), type, brands, thresholdDays, thresholdMonths,
                staleDateProp, archiveMinDays, archiveMaxDays, archiveDateProp, excludePaths, excludeProperty,
                maxRecords, outputFolder, type.isSendToWorkfront(), activateCsv, columns);
    }

    private static int defaultMonths(final ReportType type) {
        return type == ReportType.LIVE_LONG_NO_CHILDREN
                ? ReportsConstants.DEFAULT_LONG_MONTHS : ReportsConstants.DEFAULT_STALE_MONTHS;
    }

    private static List<BrandScope> readBrands(final Resource component) {
        final List<BrandScope> brands = new ArrayList<>();
        final Resource node = component.getChild(ReportsConstants.PN_BRANDS);
        if (node != null) {
            for (final Resource row : node.getChildren()) {
                final ValueMap vm = row.getValueMap();
                final String brand = StringUtils.trimToNull(vm.get(ReportsConstants.PN_BRAND_KEY, String.class));
                final List<String> roots = readMultiValue(vm, ReportsConstants.PN_BRAND_ROOTS);
                if (brand != null && !roots.isEmpty()) {
                    brands.add(new BrandScope(brand, roots));
                } else {
                    LOG.warn("Skipping incomplete brand row at {}", row.getPath());
                }
            }
        }
        if (brands.isEmpty()) {
            // Fall back to a single "all" scope so the report still runs.
            brands.add(new BrandScope("all", java.util.Collections.singletonList("/content")));
        }
        return brands;
    }

    /**
     * Reads the single optional exclude-property condition. Returns {@code null}
     * when no property name is configured — in which case no property-based
     * exclusion is applied. The value defaults to {@code true} when a name is set
     * but no value is chosen.
     *
     * @param vm the config component's ValueMap
     * @return the exclude condition, or {@code null} for "no property exclusion"
     */
    private static ExcludeProperty readExcludeProperty(final ValueMap vm) {
        final String name = StringUtils.trimToNull(vm.get(ReportsConstants.PN_EXCLUDE_PROPERTY_NAME, String.class));
        if (name == null) {
            return null;
        }
        final String value = defaulted(vm.get(ReportsConstants.PN_EXCLUDE_PROPERTY_VALUE, String.class),
                ReportsConstants.DEFAULT_EXCLUDE_PROPERTY_VALUE);
        return new ExcludeProperty(name, value);
    }

    private static List<ReportColumn> readColumns(final Resource component) {
        final List<ReportColumn> columns = new ArrayList<>();
        final Resource node = component.getChild(ReportsConstants.PN_COLUMNS);
        if (node != null) {
            for (final Resource row : node.getChildren()) {
                final ValueMap vm = row.getValueMap();
                final String header = StringUtils.trimToNull(vm.get(ReportsConstants.PN_COLUMN_HEADER, String.class));
                final String source = StringUtils.trimToNull(vm.get(ReportsConstants.PN_COLUMN_SOURCE, String.class));
                if (header != null && source != null) {
                    columns.add(new ReportColumn(header, source));
                } else {
                    LOG.warn("Skipping incomplete column row at {}", row.getPath());
                }
            }
        }
        if (columns.isEmpty()) {
            columns.addAll(ReportsConstants.defaultColumns());
        }
        return columns;
    }

    private static List<String> readMultiValue(final ValueMap vm, final String name) {
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
        return result;
    }

    private static int readInt(final ValueMap vm, final String name, final int defaultValue) {
        final Long asLong = vm.get(name, Long.class);
        if (asLong != null && asLong >= 0) {
            return asLong.intValue();
        }
        final String asString = StringUtils.trimToNull(vm.get(name, String.class));
        if (asString != null) {
            try {
                final int parsed = Integer.parseInt(asString);
                if (parsed >= 0) {
                    return parsed;
                }
            } catch (final NumberFormatException e) {
                LOG.warn("Invalid integer '{}' for '{}'; using default {}", asString, name, defaultValue);
            }
        }
        return defaultValue;
    }

    private static String defaulted(final String value, final String defaultValue) {
        final String trimmed = StringUtils.trimToNull(value);
        return trimmed != null ? trimmed : defaultValue;
    }
}
