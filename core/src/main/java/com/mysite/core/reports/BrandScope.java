package com.mysite.core.reports;

/**
 * A brand (e.g. NatWest / RBS / Ulster) and the single content root path that
 * makes up its scope for a report. Each brand yields one CSV per report
 * ({@code <reportId>-<brand>.csv}). For the archive-aged report the root is the
 * configured archive folder.
 */
public final class BrandScope {

    private final String brand;
    private final String root;

    public BrandScope(final String brand, final String root) {
        this.brand = brand;
        this.root = root;
    }

    /** @return the brand key (also the file-name/dataset suffix after sanitising). */
    public String getBrand() {
        return brand;
    }

    /** @return the single content root path scanned for this brand. */
    public String getRoot() {
        return root;
    }

    @Override
    public String toString() {
        return "BrandScope{brand='" + brand + "', root='" + root + "'}";
    }
}
