package com.mysite.core.reports;

/**
 * The five governance report types. Each type maps 1:1 to an authorable
 * configuration component (via {@link #getResourceType()}) and carries the
 * defaults that differ between reports: the folder its CSVs are written into,
 * whether its output is sent to Workfront, and the default record cap.
 *
 * <p>The selection rule for each type is implemented by
 * {@link ReportFilterFactory}; thresholds are read per component by
 * {@link ReportDefinitionReader}.</p>
 */
public enum ReportType {

    /** All live pages per configured paths. Full CSV, kept in the DAM (not sent). */
    ALL_LIVE("all-live", ReportsConstants.ALL_LIVE_OUTPUT_FOLDER, false, 0),

    /** Published pages expiring within N days (incl. already expired). Per brand, sent. */
    EXPIRING_PUBLISHED("expiring-published", null, true, ReportsConstants.DEFAULT_MAX_RECORDS),

    /** Not-live pages not modified in N months, without the exclusion flag. Per brand, sent. */
    NOT_LIVE_STALE("not-live-stale", null, true, ReportsConstants.DEFAULT_MAX_RECORDS),

    /** Live pages last published over N months ago, with no child page and no exclusion flag. Per brand, sent. */
    LIVE_LONG_NO_CHILDREN("live-long-no-children", null, true, ReportsConstants.DEFAULT_MAX_RECORDS),

    /** Pages in configured archive folders aged within [min,max] days. Per brand, sent. */
    ARCHIVE_AGED("archive-aged", null, true, ReportsConstants.DEFAULT_MAX_RECORDS);

    private final String reportId;
    private final String fixedOutputFolder;
    private final boolean sendToWorkfront;
    private final int defaultMaxRecords;

    ReportType(final String reportId, final String fixedOutputFolder,
               final boolean sendToWorkfront, final int defaultMaxRecords) {
        this.reportId = reportId;
        this.fixedOutputFolder = fixedOutputFolder;
        this.sendToWorkfront = sendToWorkfront;
        this.defaultMaxRecords = defaultMaxRecords;
    }

    /** @return the kebab-case report id, also the file-name prefix and dataset prefix. */
    public String getReportId() {
        return reportId;
    }

    /** @return the config component resource type, e.g. {@code mysite/components/reports/all-live}. */
    public String getResourceType() {
        return ReportsConstants.RESOURCE_TYPE_BASE + reportId;
    }

    /** @return whether this report's JSON is pushed to Workfront (false only for all-live). */
    public boolean isSendToWorkfront() {
        return sendToWorkfront;
    }

    /** @return the default record cap ({@code 0} = unlimited). */
    public int getDefaultMaxRecords() {
        return defaultMaxRecords;
    }

    /**
     * @return the default DAM base folder for this report: a fixed folder for
     *         {@code ALL_LIVE}, otherwise {@code <reportsRoot>/<reportId>}.
     */
    public String getDefaultOutputFolder() {
        return fixedOutputFolder != null
                ? fixedOutputFolder
                : ReportsConstants.REPORTS_ROOT + "/" + reportId;
    }

    /**
     * Resolves a report type from a config component resource type.
     *
     * @param resourceType the {@code sling:resourceType}
     * @return the matching type, or {@code null} when none matches
     */
    public static ReportType fromResourceType(final String resourceType) {
        if (resourceType == null) {
            return null;
        }
        for (final ReportType type : values()) {
            if (type.getResourceType().equals(resourceType)) {
                return type;
            }
        }
        return null;
    }
}
