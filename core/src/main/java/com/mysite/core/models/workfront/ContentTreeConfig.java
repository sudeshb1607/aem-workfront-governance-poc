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

/**
 * Immutable value object describing a single content tree that should be
 * exported to a CSV file. Populated from one row of the composite multifield
 * on the Workfront Dashboard configuration component.
 */
public final class ContentTreeConfig {

    private final String contentTreePath;
    private final String csvName;

    public ContentTreeConfig(final String contentTreePath, final String csvName) {
        this.contentTreePath = contentTreePath;
        this.csvName = csvName;
    }

    /**
     * @return the absolute JCR path of the content tree root to export (e.g. {@code /content/mysite/us/en}).
     */
    public String getContentTreePath() {
        return contentTreePath;
    }

    /**
     * @return the CSV file name (without extension) to write into the DAM.
     */
    public String getCsvName() {
        return csvName;
    }

    @Override
    public String toString() {
        return "ContentTreeConfig{contentTreePath='" + contentTreePath + "', csvName='" + csvName + "'}";
    }
}
