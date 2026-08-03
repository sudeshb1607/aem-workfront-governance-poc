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

import java.util.Collections;
import java.util.List;

/**
 * Immutable snapshot of a single Unpublished Pages Report configuration
 * component. Built by {@link UnpublishedReportConfigReader} with defaults
 * already applied, so consumers (the generator service, scheduler and Run-now
 * servlet) never need to re-derive fallbacks.
 */
public final class UnpublishedReportConfig {

    private final String componentPath;
    private final List<String> scanRoots;
    private final List<String> excludePaths;
    private final List<ExcludeProperty> excludeProps;
    private final int thresholdDays;
    private final String outputFolder;
    private final String fileName;
    private final boolean activateCsv;
    private final List<ReportColumn> columns;

    public UnpublishedReportConfig(final String componentPath,
                                   final List<String> scanRoots,
                                   final List<String> excludePaths,
                                   final List<ExcludeProperty> excludeProps,
                                   final int thresholdDays,
                                   final String outputFolder,
                                   final String fileName,
                                   final boolean activateCsv,
                                   final List<ReportColumn> columns) {
        this.componentPath = componentPath;
        this.scanRoots = Collections.unmodifiableList(scanRoots);
        this.excludePaths = Collections.unmodifiableList(excludePaths);
        this.excludeProps = Collections.unmodifiableList(excludeProps);
        this.thresholdDays = thresholdDays;
        this.outputFolder = outputFolder;
        this.fileName = fileName;
        this.activateCsv = activateCsv;
        this.columns = Collections.unmodifiableList(columns);
    }

    /**
     * @return the JCR path of the configuration component this snapshot was read from.
     */
    public String getComponentPath() {
        return componentPath;
    }

    /**
     * @return the content roots to scan for {@code cq:Page} nodes; never empty.
     */
    public List<String> getScanRoots() {
        return scanRoots;
    }

    /**
     * @return path prefixes whose pages are excluded from the report.
     */
    public List<String> getExcludePaths() {
        return excludePaths;
    }

    /**
     * @return property conditions that exclude a matching page from the report.
     */
    public List<ExcludeProperty> getExcludeProps() {
        return excludeProps;
    }

    /**
     * @return the number of days without publication after which a page is reported.
     */
    public int getThresholdDays() {
        return thresholdDays;
    }

    /**
     * @return the DAM folder the CSV is written into (no trailing slash).
     */
    public String getOutputFolder() {
        return outputFolder;
    }

    /**
     * @return the CSV base file name (without extension or date suffix).
     */
    public String getFileName() {
        return fileName;
    }

    /**
     * @return {@code true} when the generated CSV should be activated.
     */
    public boolean isActivateCsv() {
        return activateCsv;
    }

    /**
     * @return the ordered list of CSV columns to emit; never empty.
     */
    public List<ReportColumn> getColumns() {
        return columns;
    }

    @Override
    public String toString() {
        return "UnpublishedReportConfig{componentPath='" + componentPath + "', scanRoots=" + scanRoots
                + ", excludePaths=" + excludePaths + ", excludeProps=" + excludeProps
                + ", thresholdDays=" + thresholdDays + ", outputFolder='" + outputFolder
                + "', fileName='" + fileName + "', activateCsv=" + activateCsv
                + ", columns=" + columns + '}';
    }
}
