/*
 *  Copyright 2024 Adobe Systems Incorporated
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.mysite.core.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.mysite.core.models.impl.UnpublishedReportConfigModelImpl;
import com.mysite.core.models.report.ReportConfigConstants;
import com.mysite.core.testcontext.AppAemContext;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

@ExtendWith(AemContextExtension.class)
class UnpublishedReportConfigModelImplTest {

    private static final String COMPONENT_PATH = "/content/mypage/jcr:content/report";

    private final AemContext context = AppAemContext.newAemContext();

    @BeforeEach
    void setup() {
        context.addModelsForClasses(UnpublishedReportConfigModelImpl.class);
    }

    @Test
    void testAuthoredConfiguration() {
        context.build().resource(COMPONENT_PATH,
                "sling:resourceType", ReportConfigConstants.RESOURCE_TYPE,
                "scanRoots", new String[] {"/content/mysite"},
                "thresholdDays", 60L,
                "fileName", "stale-pages");

        final UnpublishedReportConfigModel model = adaptModel();
        assertNotNull(model);
        assertTrue(model.isConfigured(), "authored component reports as configured");
        assertEquals(COMPONENT_PATH, model.getComponentPath());
        assertEquals("/content/mysite", model.getConfig().getScanRoots().get(0));
        assertEquals(60, model.getConfig().getThresholdDays());
        assertEquals("stale-pages", model.getConfig().getFileName());
    }

    @Test
    void testEmptyConfigurationShowsDefaults() {
        context.build().resource(COMPONENT_PATH,
                "sling:resourceType", ReportConfigConstants.RESOURCE_TYPE);

        final UnpublishedReportConfigModel model = adaptModel();
        assertNotNull(model);
        assertFalse(model.isConfigured(), "un-authored component is not marked configured");
        // The summary still reflects usable defaults.
        assertEquals(ReportConfigConstants.DEFAULT_SCAN_ROOT, model.getConfig().getScanRoots().get(0));
        assertEquals(ReportConfigConstants.DEFAULT_THRESHOLD_DAYS, model.getConfig().getThresholdDays());
        assertFalse(model.getConfig().getColumns().isEmpty());
    }

    private UnpublishedReportConfigModel adaptModel() {
        final Resource resource = context.resourceResolver().getResource(COMPONENT_PATH);
        assertNotNull(resource, "component resource should exist");
        return resource.adaptTo(UnpublishedReportConfigModel.class);
    }
}
