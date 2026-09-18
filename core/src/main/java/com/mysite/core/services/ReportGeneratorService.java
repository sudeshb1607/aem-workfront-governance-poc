package com.mysite.core.services;

import java.util.Collections;
import java.util.List;

import org.osgi.annotation.versioning.ProviderType;

import com.mysite.core.reports.ReportDefinition;

/**
 * Generates the CSV(s) for one report definition — one CSV per configured brand,
 * written into {@code <outputFolder>/csv/<reportId>-<brand>.csv}. The common
 * engine behind all five governance reports; the per-report selection rule comes
 * from {@code ReportFilterFactory}.
 */
@ProviderType
public interface ReportGeneratorService {

    /**
     * Generates every per-brand CSV for the given report definition, with the full
     * author-protection cool-downs enabled (equivalent to {@code generate(definition, true)}).
     * Each brand is isolated: a failure on one brand does not prevent the others.
     *
     * @param definition the report configuration snapshot
     * @return the outcome (paths written + total rows); never {@code null}
     */
    ReportRunResult generate(ReportDefinition definition);

    /**
     * Generates every per-brand CSV for the given report definition.
     *
     * @param definition the report configuration snapshot
     * @param throttle   when {@code true}, take the configured cool-down after each
     *                   batch and after each brand (used by the scheduled run so the
     *                   shared author stays responsive); when {@code false}, run
     *                   straight through with no cool-downs (used by the on-demand
     *                   run-now servlet so the HTTP request returns promptly)
     * @return the outcome (paths written + total rows); never {@code null}
     */
    ReportRunResult generate(ReportDefinition definition, boolean throttle);

    /**
     * Immutable outcome of a report generation run (across all brands).
     */
    final class ReportRunResult {

        private final boolean success;
        private final List<String> csvPaths;
        private final long totalRows;
        private final String errorMessage;

        private ReportRunResult(final boolean success, final List<String> csvPaths,
                                final long totalRows, final String errorMessage) {
            this.success = success;
            this.csvPaths = csvPaths != null ? Collections.unmodifiableList(csvPaths) : Collections.emptyList();
            this.totalRows = totalRows;
            this.errorMessage = errorMessage;
        }

        public static ReportRunResult success(final List<String> csvPaths, final long totalRows) {
            return new ReportRunResult(true, csvPaths, totalRows, null);
        }

        public static ReportRunResult failure(final String errorMessage) {
            return new ReportRunResult(false, Collections.emptyList(), 0L, errorMessage);
        }

        public boolean isSuccess() {
            return success;
        }

        public List<String> getCsvPaths() {
            return csvPaths;
        }

        public long getTotalRows() {
            return totalRows;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
