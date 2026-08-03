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

import com.mysite.core.models.report.UnpublishedReportConfig;

/**
 * Sling Model backing the Unpublished Pages Report configuration component.
 * Exposes the resolved configuration (with defaults applied) so the HTL can
 * render an author summary in edit view. This model is for the rendering path
 * only; the scheduler and Run-now servlet read the same data directly from the
 * JCR via {@code UnpublishedReportConfigReader}.
 */
public interface UnpublishedReportConfigModel {

    /**
     * @return the resolved configuration snapshot; never {@code null}.
     */
    UnpublishedReportConfig getConfig();

    /**
     * @return {@code true} when the author has saved the dialog at least once;
     *         {@code false} means the summary reflects built-in defaults only.
     */
    boolean isConfigured();

    /**
     * @return the JCR path of this configuration component, for the Run-now action.
     */
    String getComponentPath();
}
