package com.mysite.core.reports.filter;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import com.mysite.core.reports.ReportFilter;
import com.mysite.core.util.PagePublicationUtil;

/**
 * Report 1 — All Live: selects pages that are currently published (activated).
 * Self-contained so changes to this rule cannot affect the other reports.
 */
public final class AllLiveFilter implements ReportFilter {

    private static final String JCR_CONTENT = "jcr:content";

    @Override
    public boolean accept(final Resource pageResource, final ValueMap contentVm) {
        return PagePublicationUtil.isPublished(pageResource.getChild(JCR_CONTENT));
    }
}
