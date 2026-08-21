package com.mysite.core.services;

import org.osgi.annotation.versioning.ProviderType;

import com.mysite.core.models.report.UnpublishedReportConfig;

/**
 * Generates a CSV report of pages that have not been published within a
 * configured window, writing the file into the DAM and optionally activating
 * it. Implementations traverse each configured scan root in bounded batches so
 * memory stays flat even under a large {@code /content} tree.
 */
@ProviderType
public interface UnpublishedPagesReportService {

    /**
     * Generates the unpublished-pages CSV for a single configuration.
     *
     * @param config the resolved report configuration (defaults already applied)
     * @return the outcome of the generation attempt; never {@code null}
     */
    ReportResult generateReport(UnpublishedReportConfig config);

    /**
     * Immutable outcome of a single report generation attempt.
     */
    final class ReportResult {

        private final boolean success;
        private final String csvPath;
        private final long rowsReported;
        private final String errorMessage;

        private ReportResult(final boolean success, final String csvPath,
                             final long rowsReported, final String errorMessage) {
            this.success = success;
            this.csvPath = csvPath;
            this.rowsReported = rowsReported;
            this.errorMessage = errorMessage;
        }

        public static ReportResult success(final String csvPath, final long rowsReported) {
            return new ReportResult(true, csvPath, rowsReported, null);
        }

        public static ReportResult failure(final String csvPath, final String errorMessage) {
            return new ReportResult(false, csvPath, 0L, errorMessage);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getCsvPath() {
            return csvPath;
        }

        public long getRowsReported() {
            return rowsReported;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
