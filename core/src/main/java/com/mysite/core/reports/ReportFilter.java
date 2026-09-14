package com.mysite.core.reports;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

/**
 * Selection rule for a report: decides whether a single {@code cq:Page} qualifies.
 * Exclusion (paths / properties) is applied separately by the generator, so a
 * filter only encodes the report's positive rule.
 */
@FunctionalInterface
public interface ReportFilter {

    /**
     * @param pageResource the {@code cq:Page} resource
     * @param contentVm    the page's {@code jcr:content} ValueMap (never {@code null})
     * @return {@code true} when the page belongs in the report
     */
    boolean accept(Resource pageResource, ValueMap contentVm);
}
