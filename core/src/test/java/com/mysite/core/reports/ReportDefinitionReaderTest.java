package com.mysite.core.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.mysite.core.testcontext.AppAemContext;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

@ExtendWith(AemContextExtension.class)
class ReportDefinitionReaderTest {

    private final AemContext context = AppAemContext.newAemContext();

    private ReportDefinition readAt(final String path) {
        final Resource r = context.resourceResolver().getResource(path);
        return ReportDefinitionReader.readOne(r);
    }

    @Test
    void parsesStringThresholdAndArchiveProps() {
        final String base = "/content/cfg/arch";
        context.build().resource(base,
                "sling:resourceType", ReportType.ARCHIVE_AGED.getResourceType(),
                "archiveMinDays", "70", "archiveMaxDays", "100", "archiveDateProp", "archivedDate")
                .commit();
        final ReportDefinition def = readAt(base);
        assertEquals(70, def.getArchiveMinDays());
        assertEquals(100, def.getArchiveMaxDays());
        assertEquals("archivedDate", def.getArchiveDateProp());
    }

    @Test
    void invalidIntegerFallsBackToDefault() {
        final String base = "/content/cfg/bad";
        context.build().resource(base,
                "sling:resourceType", ReportType.EXPIRING_PUBLISHED.getResourceType(),
                "thresholdDays", "not-a-number").commit();
        assertEquals(ReportsConstants.DEFAULT_THRESHOLD_DAYS, readAt(base).getThresholdDays());
    }

    @Test
    void skipsIncompleteBrandRowsAndReadsExcludeProperty() {
        final String base = "/content/cfg/incomplete";
        context.build()
                .resource(base, "sling:resourceType", ReportType.NOT_LIVE_STALE.getResourceType(),
                        "excludePropertyName", "excludeFromReport", "excludePropertyValue", "true")
                .resource(base + "/brands/item0", "brand", "natwest", "rootPath", "/content/natwest")
                .resource(base + "/brands/item1", "brand", "no-root")             // no rootPath -> skipped
                .commit();
        final ReportDefinition def = readAt(base);
        assertEquals(1, def.getBrands().size());
        assertEquals("/content/natwest", def.getBrands().get(0).getRoot());
        // Columns are the fixed governance schema regardless of any per-component config.
        assertEquals(14, def.getColumns().size());
        assertEquals("excludeFromReport", def.getExcludeProperty().getName());
        assertEquals("true", def.getExcludeProperty().getValue());
    }

    @Test
    void emptyExcludePropertyNameMeansNoExclusion() {
        final String base = "/content/cfg/noexcl";
        context.build().resource(base, "sling:resourceType", ReportType.ALL_LIVE.getResourceType()).commit();
        org.junit.jupiter.api.Assertions.assertNull(readAt(base).getExcludeProperty());
    }

    @Test
    void excludePropertyValueDefaultsToTrue() {
        final String base = "/content/cfg/excldefault";
        context.build().resource(base, "sling:resourceType", ReportType.ALL_LIVE.getResourceType(),
                "excludePropertyName", "excludeFromReport").commit();
        assertEquals("true", readAt(base).getExcludeProperty().getValue());
    }

    @Test
    void liveLongDefaultsTo18Months() {
        final String base = "/content/cfg/long";
        context.build().resource(base,
                "sling:resourceType", ReportType.LIVE_LONG_NO_CHILDREN.getResourceType()).commit();
        assertEquals(ReportsConstants.DEFAULT_LONG_MONTHS, readAt(base).getThresholdMonths());
    }

    @Test
    void readsBrandsThresholdAndType() {
        final String base = "/content/cfg/expiring";
        context.build()
                .resource(base, "sling:resourceType", ReportType.EXPIRING_PUBLISHED.getResourceType(),
                        "thresholdDays", 30L, "maxRecords", 500L)
                .resource(base + "/brands/item0", "brand", "NatWest", "rootPath", "/content/natwest")
                .resource(base + "/brands/item1", "brand", "RBS", "rootPath", "/content/rbs")
                .commit();

        final ReportDefinition def = readAt(base);
        assertEquals(ReportType.EXPIRING_PUBLISHED, def.getType());
        assertEquals("expiring-published", def.getReportId());
        assertEquals(30, def.getThresholdDays());
        assertEquals(500, def.getMaxRecords());
        assertTrue(def.isSendToWorkfront());
        assertEquals(2, def.getBrands().size());
        assertEquals("NatWest", def.getBrands().get(0).getBrand());
        assertEquals("/content/natwest", def.getBrands().get(0).getRoot());
        assertEquals("/content/rbs", def.getBrands().get(1).getRoot());
    }

    @Test
    void clampsMaxRecordsToHardCapForNonAllLive() {
        final String base = "/content/cfg/capped";
        context.build().resource(base,
                "sling:resourceType", ReportType.EXPIRING_PUBLISHED.getResourceType(),
                "maxRecords", 5000L).commit();
        // Even a direct CRXDE value above the hard cap is clamped down to 1000.
        assertEquals(ReportsConstants.HARD_CAP_MAX_RECORDS, readAt(base).getMaxRecords());
    }

    @Test
    void appliesDefaultsWhenEmpty() {
        final String base = "/content/cfg/stale";
        context.build()
                .resource(base, "sling:resourceType", ReportType.NOT_LIVE_STALE.getResourceType())
                .commit();

        final ReportDefinition def = readAt(base);
        // Default single "all" brand under /content.
        assertEquals(1, def.getBrands().size());
        assertEquals("all", def.getBrands().get(0).getBrand());
        assertEquals("/content", def.getBrands().get(0).getRoot());
        // Default months for the stale report + default cap.
        assertEquals(ReportsConstants.DEFAULT_STALE_MONTHS, def.getThresholdMonths());
        assertEquals(ReportsConstants.DEFAULT_MAX_RECORDS, def.getMaxRecords());
        // No exclusion property configured -> no property-based exclusion.
        org.junit.jupiter.api.Assertions.assertNull(def.getExcludeProperty());
        // Default 14-column governance schema.
        assertEquals(14, def.getColumns().size());
        assertEquals("Hash", def.getColumns().get(0).getHeader());
        // Default output folder for the type.
        assertEquals(ReportsConstants.REPORTS_ROOT + "/not-live-stale", def.getOutputFolder());
    }

    @Test
    void allLiveIsUnlimitedAndNotSent() {
        final String base = "/content/cfg/all";
        context.build()
                .resource(base, "sling:resourceType", ReportType.ALL_LIVE.getResourceType())
                .commit();

        final ReportDefinition def = readAt(base);
        assertEquals(0, def.getMaxRecords());
        assertFalse(def.isSendToWorkfront());
        assertEquals(ReportsConstants.ALL_LIVE_OUTPUT_FOLDER, def.getOutputFolder());
    }

    @Test
    void columnsAreAlwaysTheFixedGovernanceSchema() {
        final String base = "/content/cfg/archive";
        // Any per-component "columns" nodes are ignored now that the Columns tab is gone.
        context.build()
                .resource(base, "sling:resourceType", ReportType.ARCHIVE_AGED.getResourceType())
                .resource(base + "/columns/item0", "header", "Path", "source", ":path")
                .commit();

        final ReportDefinition def = readAt(base);
        assertEquals(14, def.getColumns().size());
        assertEquals("Hash", def.getColumns().get(0).getHeader());
    }
}
