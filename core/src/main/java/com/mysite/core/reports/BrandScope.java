package com.mysite.core.reports;

import java.util.Collections;
import java.util.List;

/**
 * A brand (e.g. NatWest / RBS / Ulster) and the content root path(s) that make up
 * its scope for a report. Each brand yields one CSV per report
 * ({@code <reportId>-<brand>.csv}). For the archive-aged report the roots are the
 * configured archive folders.
 */
public final class BrandScope {

    private final String brand;
    private final List<String> roots;

    public BrandScope(final String brand, final List<String> roots) {
        this.brand = brand;
        this.roots = roots != null ? Collections.unmodifiableList(roots) : Collections.emptyList();
    }

    /** @return the brand key (also the file-name/dataset suffix after sanitising). */
    public String getBrand() {
        return brand;
    }

    /** @return the content root path(s) scanned for this brand. */
    public List<String> getRoots() {
        return roots;
    }

    @Override
    public String toString() {
        return "BrandScope{brand='" + brand + "', roots=" + roots + '}';
    }
}
