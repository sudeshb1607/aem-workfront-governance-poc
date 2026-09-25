package com.mysite.core.reports;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.mysite.core.models.report.ReportColumn;

/**
 * Shared property/node names and defaults for the five report configuration
 * components, used by {@link ReportDefinitionReader} and the report Sling model.
 */
public final class ReportsConstants {

    private ReportsConstants() {
        // constants holder
    }

    /** Resource-type prefix; the report id is appended (e.g. {@code .../reports/all-live}). */
    public static final String RESOURCE_TYPE_BASE = "mysite/components/reports/";

    /** DAM root under which the per-report (sent) subfolders live. */
    public static final String REPORTS_ROOT = "/content/dam/mysite/workfront-reports";
    /** Fixed DAM folder for the all-live report (DAM-only, not sent). */
    public static final String ALL_LIVE_OUTPUT_FOLDER = "/content/dam/mysite/reports/all-live";

    // --- Composite multifield: brands, each with exactly one content root path. ---
    public static final String PN_BRANDS = "brands";
    public static final String PN_BRAND_KEY = "brand";
    public static final String PN_BRAND_ROOT = "rootPath";

    // --- Rule thresholds (only the relevant one(s) apply per report type). ---
    public static final String PN_THRESHOLD_DAYS = "thresholdDays";     // expiring-published
    public static final String PN_THRESHOLD_MONTHS = "thresholdMonths"; // not-live-stale, live-long-no-children
    public static final String PN_STALE_DATE_PROP = "staleDateProp";    // not-live-stale
    public static final String PN_ARCHIVE_MIN_DAYS = "archiveMinDays";  // archive-aged
    public static final String PN_ARCHIVE_MAX_DAYS = "archiveMaxDays";  // archive-aged
    public static final String PN_ARCHIVE_DATE_PROP = "archiveDateProp"; // archive-aged

    // --- Exclusions. A single optional property: when the name is set, a page is
    //     excluded if that jcr:content (or page) property equals the boolean value. ---
    public static final String PN_EXCLUDE_PATHS = "excludePaths";
    public static final String PN_EXCLUDE_PROPERTY_NAME = "excludePropertyName";
    public static final String PN_EXCLUDE_PROPERTY_VALUE = "excludePropertyValue";

    // --- Output. Columns are the fixed governance schema (defaultColumns()); the
    //     per-component Columns tab has been removed. ---
    public static final String PN_MAX_RECORDS = "maxRecords";
    public static final String PN_OUTPUT_FOLDER = "outputFolder";
    public static final String PN_ACTIVATE_CSV = "activateCsv";

    // --- Column source tokens (resolved by the report engine). ---
    public static final String SOURCE_TITLE = ":title";
    public static final String SOURCE_PATH = ":path";
    public static final String SOURCE_URL = ":url";
    public static final String SOURCE_HASH = ":hash";
    public static final String SOURCE_BRAND = ":brand";
    public static final String SOURCE_PUBLISHED = ":published";
    public static final String SOURCE_DAYS_TO_REVIEW = ":daysToReview";
    /** True when the page has at least one published (live) direct child page. */
    public static final String SOURCE_HAS_LIVE_CHILDREN = ":hasLiveChildren";
    /** True when any direct child page (live or not) carries the configured exclusion flag. */
    public static final String SOURCE_HAS_EXCLUDED_CHILDREN = ":hasExcludedChildren";

    // --- Property names read by the rules. ---
    public static final String PN_LAST_MODIFIED = "cq:lastModified";
    public static final String PN_REVIEW_EXPIRY = "contentReviewExpiryDate";

    // --- Defaults. ---
    public static final int DEFAULT_THRESHOLD_DAYS = 45;    // expiring-published
    public static final int DEFAULT_STALE_MONTHS = 6;       // not-live-stale
    public static final String DEFAULT_STALE_DATE_PROP = "cq:lastModified";
    public static final int DEFAULT_LONG_MONTHS = 18;       // live-long-no-children
    public static final int DEFAULT_ARCHIVE_MIN_DAYS = 60;  // archive-aged
    public static final int DEFAULT_ARCHIVE_MAX_DAYS = 90;  // archive-aged
    public static final String DEFAULT_ARCHIVE_DATE_PROP = "cq:lastModified";
    public static final int DEFAULT_MAX_RECORDS = 1000;
    /**
     * Absolute per-brand record cap enforced for every report except all-live.
     * The cap cannot be raised past this value, even by editing {@code maxRecords}
     * directly in CRXDE — {@link ReportType#clampMaxRecords(int)} clamps to it.
     */
    public static final int HARD_CAP_MAX_RECORDS = 1000;
    /** Value assumed when an exclusion property name is set but no value is chosen. */
    public static final String DEFAULT_EXCLUDE_PROPERTY_VALUE = "true";

    /**
     * The shared default column set (the governance schema) emitted by every
     * report unless overridden per component. Matches the header expected by the
     * Workfront JSON converter.
     *
     * @return an unmodifiable list of the 14 default columns
     */
    public static List<ReportColumn> defaultColumns() {
        return Collections.unmodifiableList(Arrays.asList(
                new ReportColumn("Hash", SOURCE_HASH),
                new ReportColumn("Title", SOURCE_TITLE),
                new ReportColumn("Path", SOURCE_PATH),
                new ReportColumn("Brand", SOURCE_BRAND),
                new ReportColumn("Last Modified", PN_LAST_MODIFIED),
                new ReportColumn("Modified By", "cq:lastModifiedBy"),
                new ReportColumn("Published", SOURCE_PUBLISHED),
                new ReportColumn("Next Review Date", PN_REVIEW_EXPIRY),
                new ReportColumn("Days For Next Review", SOURCE_DAYS_TO_REVIEW),
                new ReportColumn("Franchise", "franchise"),
                new ReportColumn("Page Owners", "pageOwners"),
                new ReportColumn("Template", "cq:template"),
                new ReportColumn("Has Live Children", SOURCE_HAS_LIVE_CHILDREN),
                new ReportColumn("Has Excluded Children", SOURCE_HAS_EXCLUDED_CHILDREN)));
    }
}
