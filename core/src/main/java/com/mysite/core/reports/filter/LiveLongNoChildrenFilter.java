package com.mysite.core.reports.filter;

import java.time.LocalDate;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import com.mysite.core.reports.ReportFilter;
import com.mysite.core.util.DateUtils;
import com.mysite.core.util.PagePublicationUtil;

/**
 * Report 4 — Live, Long-Published, No Children: published pages last replicated
 * more than the threshold months ago that have no child page. Self-contained.
 */
public final class LiveLongNoChildrenFilter implements ReportFilter {

    private static final String JCR_CONTENT = "jcr:content";
    private static final String PN_PRIMARY_TYPE = "jcr:primaryType";
    private static final String NT_PAGE = "cq:Page";

    private final LocalDate cutoff;

    /**
     * @param today          the reference date for the comparison
     * @param thresholdMonths include pages last replicated before {@code today - thresholdMonths}
     */
    public LiveLongNoChildrenFilter(final LocalDate today, final int thresholdMonths) {
        this.cutoff = today.minusMonths(thresholdMonths);
    }

    @Override
    public boolean accept(final Resource pageResource, final ValueMap contentVm) {
        if (!PagePublicationUtil.isPublished(pageResource.getChild(JCR_CONTENT))) {
            return false;
        }
        final LocalDate lastReplicated = DateUtils.toLocalDate(PagePublicationUtil.lastReplicated(contentVm));
        if (lastReplicated == null || !lastReplicated.isBefore(cutoff)) {
            return false;
        }
        return !hasChildPage(pageResource);
    }

    private static boolean hasChildPage(final Resource page) {
        for (final Resource child : page.getChildren()) {
            if (NT_PAGE.equals(child.getValueMap().get(PN_PRIMARY_TYPE, String.class))) {
                return true;
            }
        }
        return false;
    }
}
