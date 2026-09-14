package com.mysite.core.models;

import com.mysite.core.reports.ReportDefinition;

/**
 * Request/resource-scoped view of a report configuration component, used only to
 * render the author summary (edit/preview mode) for any of the five report
 * components.
 */
public interface ReportConfigModel {

    /** @return {@code true} when the resource is a recognised report component. */
    boolean isValid();

    /** @return the JCR path of the configuration component. */
    String getComponentPath();

    /** @return the report id (e.g. {@code expiring-published}), or empty when invalid. */
    String getReportId();

    /** @return a short human-readable description of the report's selection rule. */
    String getRuleSummary();

    /** @return the resolved configuration snapshot (with defaults applied), or {@code null} when invalid. */
    ReportDefinition getDefinition();
}
