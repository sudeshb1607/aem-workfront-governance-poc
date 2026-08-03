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
package com.mysite.core.models.report;

/**
 * Shared property/node names and defaults for the Unpublished Pages Report
 * configuration component, used by both the rendering Sling Model and the
 * JCR-based reader that the scheduler and Run-now servlet rely on.
 */
public final class ReportConfigConstants {

    /** Resource type of the configuration component to locate across the repository. */
    public static final String RESOURCE_TYPE = "mysite/components/unpublishedpagesreport";

    /** Simple multifield: multi-value property listing the content roots to scan. */
    public static final String PN_SCAN_ROOTS = "scanRoots";
    /** Simple multifield: multi-value property listing path prefixes to exclude. */
    public static final String PN_EXCLUDE_PATHS = "excludePaths";

    /** Composite multifield node holding the exclude-property conditions. */
    public static final String PN_EXCLUDE_PROPS = "excludeProps";
    /** Property name within an exclude-property row. */
    public static final String PN_EXCLUDE_PROP_NAME = "propertyName";
    /** Property value within an exclude-property row. */
    public static final String PN_EXCLUDE_PROP_VALUE = "propertyValue";

    /** Number of days without publication after which a page is reported. */
    public static final String PN_THRESHOLD_DAYS = "thresholdDays";
    /** DAM folder the generated CSV is written into. */
    public static final String PN_OUTPUT_FOLDER = "outputFolder";
    /** CSV base file name (a date suffix is appended at generation time). */
    public static final String PN_FILE_NAME = "fileName";
    /** Whether the generated CSV should be activated. */
    public static final String PN_ACTIVATE_CSV = "activateCsv";

    /** Composite multifield node holding the CSV column definitions. */
    public static final String PN_COLUMNS = "columns";
    /** Column header (CSV heading) within a column row. */
    public static final String PN_COLUMN_HEADER = "header";
    /** Column source (jcr:content property name or a special token) within a column row. */
    public static final String PN_COLUMN_SOURCE = "source";

    /** Special column source token resolving to the page title. */
    public static final String SOURCE_TITLE = ":title";
    /** Special column source token resolving to the page path. */
    public static final String SOURCE_PATH = ":path";
    /** Special column source token resolving to the page URL. */
    public static final String SOURCE_URL = ":url";
    /** Special column source token resolving to a unique hash of the page URL. */
    public static final String SOURCE_HASH = ":hash";

    // --- Defaults applied by the reader when a field is left empty. ---

    public static final String DEFAULT_SCAN_ROOT = "/content";
    public static final String DEFAULT_EXCLUDE_PATH = "/content/test";
    public static final String DEFAULT_EXCLUDE_PROP_NAME = "excludeFromDelete";
    public static final String DEFAULT_EXCLUDE_PROP_VALUE = "true";
    public static final int DEFAULT_THRESHOLD_DAYS = 90;
    public static final String DEFAULT_OUTPUT_FOLDER = "/content/dam/mysite/reports";
    public static final String DEFAULT_FILE_NAME = "unpublished-pages";
    public static final boolean DEFAULT_ACTIVATE_CSV = true;

    private ReportConfigConstants() {
        // constants holder
    }
}
