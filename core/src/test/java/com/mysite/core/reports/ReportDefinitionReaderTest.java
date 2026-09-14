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
    void skipsIncompleteBrandAndColumnRowsAndReadsExcludeProperty() {
        final String base = "/content/cfg/incomplete";
        context.build()
                .resource(base, "sling:resourceType", ReportType.NOT_LIVE_STALE.getResourceType(),
                        "excludePropertyName", "excludeFromReport", "excludePropertyValue", "true")
                .resource(base + "/brands/item0", "brand", "natwest", "rootPaths", new String[]{"/content/natwest"})
                .resource(base + "/brands/item1", "brand", "no-roots")            // no rootPaths -> skipped
                .resource(base + "/columns/item0", "header", "Path", "source", ":path")
                .resource(base + "/columns/item1", "header", "OnlyHeader")         // no source -> skipped
                .commit();
        final ReportDefinition def = readAt(base);
        assertEquals(1, def.getBrands().size());
        assertEquals(1, def.getColumns().size());
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
                .resource(base + "/brands/item0", "brand", "NatWest",
                        "rootPaths", new String[]{"/content/natwest", "/content/natwest-intl"})
                .resource(base + "/brands/item1", "brand", "RBS",
                        "rootPaths", new String[]{"/content/rbs"})
                .commit();

        final ReportDefinition def = readAt(base);
        assertEquals(ReportType.EXPIRING_PUBLISHED, def.getType());
        assertEquals("expiring-published", def.getReportId());
        assertEquals(30, def.getThresholdDays());
        assertEquals(500, def.getMaxRecords());
        assertTrue(def.isSendToWorkfront());
        assertEquals(2, def.getBrands().size());
        assertEquals("NatWest", def.getBrands().get(0).getBrand());
        assertEquals(2, def.getBrands().get(0).getRoots().size());
        assertEquals("/content/rbs", def.getBrands().get(1).getRoots().get(0));
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
        assertEquals("/content", def.getBrands().get(0).getRoots().get(0));
        // Default months for the stale report + default cap.
        assertEquals(ReportsConstants.DEFAULT_STALE_MONTHS, def.getThresholdMonths());
        assertEquals(ReportsConstants.DEFAULT_MAX_RECORDS, def.getMaxRecords());
        // No exclusion property configured -> no property-based exclusion.
        org.junit.jupiter.api.Assertions.assertNull(def.getExcludeProperty());
        // Default 12-column governance schema.
        assertEquals(12, def.getColumns().size());
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
    void readsCustomColumns() {
        final String base = "/content/cfg/archive";
        context.build()
                .resource(base, "sling:resourceType", ReportType.ARCHIVE_AGED.getResourceType())
                .resource(base + "/columns/item0", "header", "Path", "source", ":path")
                .resource(base + "/columns/item1", "header", "Brand", "source", ":brand")
                .commit();

        final ReportDefinition def = readAt(base);
        assertEquals(2, def.getColumns().size());
        assertEquals(":path", def.getColumns().get(0).getSource());
        assertEquals("Brand", def.getColumns().get(1).getHeader());
    }
}
