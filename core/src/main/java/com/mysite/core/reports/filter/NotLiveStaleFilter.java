package com.mysite.core.reports.filter;

import java.time.LocalDate;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import com.mysite.core.reports.ReportFilter;
import com.mysite.core.util.DateUtils;
import com.mysite.core.util.PagePublicationUtil;

/**
 * Report 3 — Not-Live Stale: pages that are not published and were not modified
 * within the threshold window (a missing date is treated as stale). The date
 * property is configurable. Self-contained.
 */
public final class NotLiveStaleFilter implements ReportFilter {

    private static final String JCR_CONTENT = "jcr:content";

    private final LocalDate cutoff;
    private final String dateProp;

    /**
     * @param today          the reference date for the comparison
     * @param thresholdMonths include pages last modified before {@code today - thresholdMonths}
     * @param dateProp       the {@code jcr:content} date property used to judge staleness
     */
    public NotLiveStaleFilter(final LocalDate today, final int thresholdMonths, final String dateProp) {
        this.cutoff = today.minusMonths(thresholdMonths);
        this.dateProp = dateProp;
    }

    @Override
    public boolean accept(final Resource pageResource, final ValueMap contentVm) {
        if (PagePublicationUtil.isPublished(pageResource.getChild(JCR_CONTENT))) {
            return false;
        }
        final LocalDate lastModified = DateUtils.readLocalDate(contentVm, dateProp);
        return lastModified == null || lastModified.isBefore(cutoff);
    }
}
