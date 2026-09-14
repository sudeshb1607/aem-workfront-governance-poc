package com.mysite.core.models.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.mysite.core.models.ReportConfigModel;
import com.mysite.core.reports.ReportType;
import com.mysite.core.testcontext.AppAemContext;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

@ExtendWith(AemContextExtension.class)
class ReportConfigModelImplTest {

    private final AemContext context = AppAemContext.newAemContext();

    @BeforeEach
    void setup() {
        context.addModelsForClasses(ReportConfigModelImpl.class);
    }

    @Test
    void resolvesDefinitionAndRuleSummary() {
        final String path = "/content/cfg/expiring";
        context.build().resource(path,
                "sling:resourceType", ReportType.EXPIRING_PUBLISHED.getResourceType(),
                "thresholdDays", 30L).commit();

        final ReportConfigModel model = adapt(path);
        assertNotNull(model);
        assertTrue(model.isValid());
        assertEquals("expiring-published", model.getReportId());
        assertEquals(path, model.getComponentPath());
        assertNotNull(model.getDefinition());
        assertEquals(30, model.getDefinition().getThresholdDays());
        assertTrue(model.getRuleSummary().contains("30 day"), model.getRuleSummary());
    }

    @Test
    void archiveRuleSummaryMentionsWindow() {
        final String path = "/content/cfg/archive";
        context.build().resource(path,
                "sling:resourceType", ReportType.ARCHIVE_AGED.getResourceType()).commit();

        final ReportConfigModel model = adapt(path);
        assertTrue(model.isValid());
        assertTrue(model.getRuleSummary().contains("60"), model.getRuleSummary());
        assertTrue(model.getRuleSummary().contains("90"), model.getRuleSummary());
    }

    @Test
    void ruleSummariesForAllTypes() {
        assertTrue(summary(ReportType.ALL_LIVE).toLowerCase().contains("live"));
        assertTrue(summary(ReportType.NOT_LIVE_STALE).toLowerCase().contains("month"));
        assertTrue(summary(ReportType.LIVE_LONG_NO_CHILDREN).toLowerCase().contains("child"));
    }

    private String summary(final ReportType type) {
        final String path = "/content/cfg/" + type.getReportId();
        context.build().resource(path, "sling:resourceType", type.getResourceType()).commit();
        return adapt(path).getRuleSummary();
    }

    @Test
    void nonReportResourceIsInvalid() {
        final String path = "/content/cfg/other";
        context.build().resource(path, "sling:resourceType", "mysite/components/text").commit();

        final ReportConfigModel model = adapt(path);
        assertFalse(model.isValid());
        assertEquals("", model.getReportId());
    }

    private ReportConfigModel adapt(final String path) {
        final Resource resource = context.resourceResolver().getResource(path);
        assertNotNull(resource);
        return resource.adaptTo(ReportConfigModel.class);
    }
}
