package com.mysite.core.reports;

import java.util.Collections;
import java.util.List;

import com.mysite.core.models.report.ExcludeProperty;
import com.mysite.core.models.report.ReportColumn;

/**
 * Immutable snapshot of one report configuration component, read by
 * {@link ReportDefinitionReader} and consumed by the report generator, the
 * Run-now servlet and the scheduler. Only the threshold field(s) relevant to the
 * {@link ReportType} are meaningful; the rest carry defaults.
 */
public final class ReportDefinition {

    private final String componentPath;
    private final ReportType type;
    private final List<BrandScope> brands;
    private final int thresholdDays;
    private final int thresholdMonths;
    private final String staleDateProp;
    private final int archiveMinDays;
    private final int archiveMaxDays;
    private final String archiveDateProp;
    private final List<String> excludePaths;
    private final ExcludeProperty excludeProperty;
    private final int maxRecords;
    private final String outputFolder;
    private final boolean sendToWorkfront;
    private final boolean activateCsv;
    private final List<ReportColumn> columns;

    // CHECKSTYLE:OFF - a config snapshot legitimately aggregates many fields.
    public ReportDefinition(final String componentPath, final ReportType type, final List<BrandScope> brands,
                            final int thresholdDays, final int thresholdMonths, final String staleDateProp,
                            final int archiveMinDays,
                            final int archiveMaxDays, final String archiveDateProp, final List<String> excludePaths,
                            final ExcludeProperty excludeProperty, final int maxRecords, final String outputFolder,
                            final boolean sendToWorkfront, final boolean activateCsv, final List<ReportColumn> columns) {
        this.componentPath = componentPath;
        this.type = type;
        this.brands = unmodifiable(brands);
        this.thresholdDays = thresholdDays;
        this.thresholdMonths = thresholdMonths;
        this.staleDateProp = staleDateProp;
        this.archiveMinDays = archiveMinDays;
        this.archiveMaxDays = archiveMaxDays;
        this.archiveDateProp = archiveDateProp;
        this.excludePaths = unmodifiable(excludePaths);
        this.excludeProperty = excludeProperty;
        this.maxRecords = maxRecords;
        this.outputFolder = outputFolder;
        this.sendToWorkfront = sendToWorkfront;
        this.activateCsv = activateCsv;
        this.columns = unmodifiable(columns);
    }
    // CHECKSTYLE:ON

    private static <T> List<T> unmodifiable(final List<T> in) {
        return in != null ? Collections.unmodifiableList(in) : Collections.emptyList();
    }

    public String getComponentPath() {
        return componentPath;
    }

    public ReportType getType() {
        return type;
    }

    /** @return the report id (kebab-case), from the report type. */
    public String getReportId() {
        return type.getReportId();
    }

    public List<BrandScope> getBrands() {
        return brands;
    }

    /** @return threshold in days (expiring-published). */
    public int getThresholdDays() {
        return thresholdDays;
    }

    /** @return threshold in months (not-live-stale, live-long-no-children). */
    public int getThresholdMonths() {
        return thresholdMonths;
    }

    /** @return the jcr:content date property the not-live-stale rule compares (default cq:lastModified). */
    public String getStaleDateProp() {
        return staleDateProp;
    }

    /** @return archive-age lower bound in days (archive-aged). */
    public int getArchiveMinDays() {
        return archiveMinDays;
    }

    /** @return archive-age upper bound in days (archive-aged). */
    public int getArchiveMaxDays() {
        return archiveMaxDays;
    }

    /** @return the jcr:content date property used to compute archive age (archive-aged). */
    public String getArchiveDateProp() {
        return archiveDateProp;
    }

    /** @return content path prefixes excluded from the report. */
    public List<String> getExcludePaths() {
        return excludePaths;
    }

    /**
     * @return the optional exclude-property condition (a page is omitted when this
     *         property equals the configured value), or {@code null} when no
     *         property is configured (i.e. no property-based exclusion).
     */
    public ExcludeProperty getExcludeProperty() {
        return excludeProperty;
    }

    /** @return the per-brand record cap ({@code 0} = unlimited). */
    public int getMaxRecords() {
        return maxRecords;
    }

    /** @return the DAM base folder; CSVs are written to {@code <outputFolder>/csv}. */
    public String getOutputFolder() {
        return outputFolder;
    }

    public boolean isSendToWorkfront() {
        return sendToWorkfront;
    }

    public boolean isActivateCsv() {
        return activateCsv;
    }

    public List<ReportColumn> getColumns() {
        return columns;
    }
}
