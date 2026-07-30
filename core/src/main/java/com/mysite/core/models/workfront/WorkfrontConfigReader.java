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
package com.mysite.core.models.workfront;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Session;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.search.PredicateGroup;
import com.day.cq.search.Query;
import com.day.cq.search.QueryBuilder;
import com.day.cq.search.result.Hit;

/**
 * Reads Workfront Dashboard configuration straight from the JCR. Used by the
 * schedulers, which run without an HTTP request and therefore cannot rely on
 * the request-scoped {@code WorkfrontDashboardConfigModel}.
 */
public final class WorkfrontConfigReader {

    private static final Logger LOG = LoggerFactory.getLogger(WorkfrontConfigReader.class);

    /** Resource type of the configuration component to locate across the repository. */
    public static final String RESOURCE_TYPE = "mysite/components/workfront-dashboard-config";

    private WorkfrontConfigReader() {
        // static utility
    }

    /**
     * Finds every instance of the configuration component and collects all of
     * its content-tree rows, de-duplicating incomplete entries.
     *
     * @param resolver     a resolver with read access to {@code /content}
     * @param queryBuilder the QueryBuilder service
     * @param searchRoot   the path to scan (typically {@code /content})
     * @return all configured content trees; never {@code null}
     */
    public static List<ContentTreeConfig> readAllConfiguredTrees(final ResourceResolver resolver,
                                                                 final QueryBuilder queryBuilder,
                                                                 final String searchRoot) {
        final List<ContentTreeConfig> trees = new ArrayList<>();
        final Session session = resolver.adaptTo(Session.class);

        final Map<String, String> params = new HashMap<>();
        params.put("path", searchRoot);
        params.put("property", "sling:resourceType");
        params.put("property.value", RESOURCE_TYPE);
        params.put("p.limit", "-1");

        final Query query = queryBuilder.createQuery(PredicateGroup.create(params), session);
        for (final Hit hit : query.getResult().getHits()) {
            try {
                addRowsFromComponent(hit.getResource(), trees);
            } catch (final Exception e) {
                LOG.warn("Failed to read Workfront config component", e);
            }
        }
        LOG.debug("Discovered {} configured content tree(s) under {}", trees.size(), searchRoot);
        return trees;
    }

    private static void addRowsFromComponent(final Resource component, final List<ContentTreeConfig> trees) {
        if (component == null) {
            return;
        }
        final Resource treesNode = component.getChild(WorkfrontConfigConstants.PN_CONTENT_TREES);
        if (treesNode == null) {
            return;
        }
        for (final Resource row : treesNode.getChildren()) {
            final ValueMap vm = row.getValueMap();
            final String path = StringUtils.trimToNull(vm.get(WorkfrontConfigConstants.PN_CONTENT_TREE_PATH, String.class));
            final String csvName = StringUtils.trimToNull(vm.get(WorkfrontConfigConstants.PN_CSV_NAME, String.class));
            if (path == null || csvName == null) {
                LOG.warn("Skipping incomplete Workfront config row at {}", row.getPath());
                continue;
            }
            trees.add(new ContentTreeConfig(path, csvName));
        }
    }
}
