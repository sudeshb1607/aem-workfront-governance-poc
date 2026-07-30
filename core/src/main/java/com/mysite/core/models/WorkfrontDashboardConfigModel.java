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

import java.util.List;

import com.mysite.core.models.workfront.ContentTreeConfig;

/**
 * Sling Model backing the Workfront Dashboard configuration component. Exposes
 * the list of configured content trees so the HTL can render a summary in the
 * author edit view. This model is intended for the rendering path only; the
 * scheduler reads the same data directly from the JCR (no request context).
 */
public interface WorkfrontDashboardConfigModel {

    /**
     * @return the configured content trees; never {@code null}, possibly empty.
     */
    List<ContentTreeConfig> getContentTrees();

    /**
     * @return {@code true} when at least one valid content tree row is configured.
     */
    boolean isConfigured();
}
