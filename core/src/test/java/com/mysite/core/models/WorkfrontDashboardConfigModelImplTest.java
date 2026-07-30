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

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.mysite.core.models.impl.WorkfrontDashboardConfigModelImpl;
import com.mysite.core.models.workfront.ContentTreeConfig;
import com.mysite.core.testcontext.AppAemContext;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

@ExtendWith(AemContextExtension.class)
class WorkfrontDashboardConfigModelImplTest {

    private static final String COMPONENT_PATH = "/content/mypage/jcr:content/workfront";
    private static final String RESOURCE_TYPE = "mysite/components/workfront-dashboard-config";

    private final AemContext context = AppAemContext.newAemContext();

    @BeforeEach
    void setup() {
        context.addModelsForClasses(WorkfrontDashboardConfigModelImpl.class);
    }

    @Test
    void testHappyPath() {
        context.build().resource(COMPONENT_PATH, "sling:resourceType", RESOURCE_TYPE)
                .resource(COMPONENT_PATH + "/contentTrees/item0",
                        "contentTreePath", "/content/mysite/us/en",
                        "csvName", "us-en-pages")
                .resource(COMPONENT_PATH + "/contentTrees/item1",
                        "contentTreePath", "/content/mysite/uk/en",
                        "csvName", "uk-en-pages")
                .commit();

        final WorkfrontDashboardConfigModel model = adaptModel();
        assertNotNull(model);
        assertTrue(model.isConfigured());

        final List<ContentTreeConfig> trees = model.getContentTrees();
        assertEquals(2, trees.size());
        assertEquals("/content/mysite/us/en", trees.get(0).getContentTreePath());
        assertEquals("us-en-pages", trees.get(0).getCsvName());
        assertEquals("/content/mysite/uk/en", trees.get(1).getContentTreePath());
    }

    @Test
    void testEmptyConfiguration() {
        context.build().resource(COMPONENT_PATH, "sling:resourceType", RESOURCE_TYPE).commit();

        final WorkfrontDashboardConfigModel model = adaptModel();
        assertNotNull(model);
        assertFalse(model.isConfigured());
        assertTrue(model.getContentTrees().isEmpty());
    }

    @Test
    void testSkipsIncompleteRows() {
        context.build().resource(COMPONENT_PATH, "sling:resourceType", RESOURCE_TYPE)
                .resource(COMPONENT_PATH + "/contentTrees/item0",
                        "contentTreePath", "/content/mysite/us/en",
                        "csvName", "us-en-pages")
                .resource(COMPONENT_PATH + "/contentTrees/item1",
                        "contentTreePath", "/content/mysite/incomplete")
                .resource(COMPONENT_PATH + "/contentTrees/item2",
                        "csvName", "orphan-csv")
                .commit();

        final WorkfrontDashboardConfigModel model = adaptModel();
        assertNotNull(model);
        assertEquals(1, model.getContentTrees().size(),
                "Rows missing a path or CSV name must be skipped");
        assertEquals("us-en-pages", model.getContentTrees().get(0).getCsvName());
    }

    private WorkfrontDashboardConfigModel adaptModel() {
        final Resource resource = context.resourceResolver().getResource(COMPONENT_PATH);
        assertNotNull(resource, "component resource should exist");
        return resource.adaptTo(WorkfrontDashboardConfigModel.class);
    }
}
