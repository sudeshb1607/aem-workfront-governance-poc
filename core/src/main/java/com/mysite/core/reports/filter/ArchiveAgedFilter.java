package com.mysite.core.reports.filter;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import com.mysite.core.reports.ReportFilter;
import com.mysite.core.util.DateUtils;

/**
 * Report 5 — Archive Aged: pages whose archive-date age falls within the
 * configured [min,max] day window. Self-contained.
 */
public final class ArchiveAgedFilter implements ReportFilter {

    private final LocalDate today;
    private final int minDays;
    private final int maxDays;
    private final String dateProp;

    /**
     * @param today    the reference date for the age calculation
     * @param minDays  lower bound (inclusive) of the archive-age window, in days
     * @param maxDays  upper bound (inclusive) of the archive-age window, in days
     * @param dateProp the {@code jcr:content} date property used to compute the archive age
     */
    public ArchiveAgedFilter(final LocalDate today, final int minDays, final int maxDays, final String dateProp) {
        this.today = today;
        this.minDays = minDays;
        this.maxDays = maxDays;
        this.dateProp = dateProp;
    }

    @Override
    public boolean accept(final Resource pageResource, final ValueMap contentVm) {
        final LocalDate date = DateUtils.readLocalDate(contentVm, dateProp);
        if (date == null) {
            return false;
        }
        final long age = ChronoUnit.DAYS.between(date, today);
        return age >= minDays && age <= maxDays;
    }
}
