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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.SlingObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mysite.core.models.WorkfrontDashboardConfigModel;
import com.mysite.core.models.workfront.ContentTreeConfig;
import com.mysite.core.models.workfront.WorkfrontConfigConstants;

/**
 * Default implementation of {@link WorkfrontDashboardConfigModel}. Reads the
 * {@code contentTrees} composite multifield child nodes off the component
 * resource and exposes them as immutable {@link ContentTreeConfig} rows.
 * Rows missing either a path or a CSV name are skipped defensively so a partial
 * author entry never breaks the export.
 */
@Model(
        adaptables = Resource.class,
        adapters = WorkfrontDashboardConfigModel.class)
public class WorkfrontDashboardConfigModelImpl implements WorkfrontDashboardConfigModel {

    private static final Logger LOG = LoggerFactory.getLogger(WorkfrontDashboardConfigModelImpl.class);

    @SlingObject
    private Resource resource;

    private final List<ContentTreeConfig> contentTrees = new ArrayList<>();

    @PostConstruct
    protected void init() {
        final Resource treesResource = resource.getChild(WorkfrontConfigConstants.PN_CONTENT_TREES);
        if (treesResource == null) {
            LOG.debug("No '{}' node under {}", WorkfrontConfigConstants.PN_CONTENT_TREES, resource.getPath());
            return;
        }
        for (final Resource row : treesResource.getChildren()) {
            final ValueMap vm = row.getValueMap();
            final String path = StringUtils.trimToNull(vm.get(WorkfrontConfigConstants.PN_CONTENT_TREE_PATH, String.class));
            final String csvName = StringUtils.trimToNull(vm.get(WorkfrontConfigConstants.PN_CSV_NAME, String.class));
            if (path == null || csvName == null) {
                LOG.warn("Skipping incomplete Workfront config row at {} (path='{}', csvName='{}')",
                        row.getPath(), path, csvName);
                continue;
            }
            contentTrees.add(new ContentTreeConfig(path, csvName));
        }
        LOG.debug("Loaded {} content tree(s) from {}", contentTrees.size(), resource.getPath());
    }

    @Override
    public List<ContentTreeConfig> getContentTrees() {
        return Collections.unmodifiableList(contentTrees);
    }

    @Override
    public boolean isConfigured() {
        return !contentTrees.isEmpty();
    }
}
