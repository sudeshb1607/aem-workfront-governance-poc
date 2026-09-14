package com.mysite.core.reports.filter;

import java.time.LocalDate;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import com.mysite.core.reports.ReportsConstants;
import com.mysite.core.reports.ReportFilter;
import com.mysite.core.util.DateUtils;
import com.mysite.core.util.PagePublicationUtil;

/**
 * Report 2 — Expiring Published: published pages whose review date is within the
 * threshold window (or already past). Self-contained.
 */
public final class ExpiringPublishedFilter implements ReportFilter {

    private static final String JCR_CONTENT = "jcr:content";

    private final LocalDate cutoff;

    /**
     * @param today         the reference date for the comparison
     * @param thresholdDays include pages whose review date is on or before {@code today + thresholdDays}
     */
    public ExpiringPublishedFilter(final LocalDate today, final int thresholdDays) {
        this.cutoff = today.plusDays(thresholdDays);
    }

    @Override
    public boolean accept(final Resource pageResource, final ValueMap contentVm) {
        if (!PagePublicationUtil.isPublished(pageResource.getChild(JCR_CONTENT))) {
            return false;
        }
        final LocalDate review = DateUtils.readLocalDate(contentVm, ReportsConstants.PN_REVIEW_EXPIRY);
        // Expiring within N days OR already expired: review <= today + N.
        return review != null && !review.isAfter(cutoff);
    }
}
