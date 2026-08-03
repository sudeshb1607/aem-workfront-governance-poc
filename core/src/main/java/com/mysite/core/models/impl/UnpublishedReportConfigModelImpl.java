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
package com.mysite.core.models.impl;

import javax.annotation.PostConstruct;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.SlingObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mysite.core.models.UnpublishedReportConfigModel;
import com.mysite.core.models.report.ReportConfigConstants;
import com.mysite.core.models.report.UnpublishedReportConfig;
import com.mysite.core.models.report.UnpublishedReportConfigReader;

/**
 * Default implementation of {@link UnpublishedReportConfigModel}. Delegates to
 * {@link UnpublishedReportConfigReader} so the multifields are read exactly the
 * same way the scheduler reads them, guaranteeing the edit-mode summary matches
 * what a scheduled run would use.
 */
@Model(
        adaptables = {SlingHttpServletRequest.class, Resource.class},
        adapters = UnpublishedReportConfigModel.class)
public class UnpublishedReportConfigModelImpl implements UnpublishedReportConfigModel {

    private static final Logger LOG = LoggerFactory.getLogger(UnpublishedReportConfigModelImpl.class);

    @SlingObject
    private Resource resource;

    private UnpublishedReportConfig config;
    private boolean configured;

    @PostConstruct
    protected void init() {
        this.config = UnpublishedReportConfigReader.readOne(resource);
        this.configured = hasSavedConfig(resource);
        LOG.debug("Loaded Unpublished Pages Report config from {} (configured={})",
                resource.getPath(), configured);
    }

    private static boolean hasSavedConfig(final Resource resource) {
        final ValueMap vm = resource.getValueMap();
        return vm.containsKey(ReportConfigConstants.PN_SCAN_ROOTS)
                || vm.containsKey(ReportConfigConstants.PN_EXCLUDE_PATHS)
                || vm.containsKey(ReportConfigConstants.PN_OUTPUT_FOLDER)
                || vm.containsKey(ReportConfigConstants.PN_THRESHOLD_DAYS)
                || resource.getChild(ReportConfigConstants.PN_EXCLUDE_PROPS) != null
                || resource.getChild(ReportConfigConstants.PN_COLUMNS) != null;
    }

    @Override
    public UnpublishedReportConfig getConfig() {
        return config;
    }

    @Override
    public boolean isConfigured() {
        return configured;
    }

    @Override
    public String getComponentPath() {
        return resource.getPath();
    }
}
