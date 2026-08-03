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
 * Immutable value object describing a single CSV column: its header and the
 * source it is resolved from. The source is either a {@code jcr:content}
 * property name (e.g. {@code cq:lastReplicated}) or one of the special tokens
 * {@code :title}, {@code :path}, {@code :url}. Populated from one row of the
 * {@code columns} composite multifield.
 */
public final class ReportColumn {

    private final String header;
    private final String source;

    public ReportColumn(final String header, final String source) {
        this.header = header;
        this.source = source;
    }

    /**
     * @return the CSV column heading.
     */
    public String getHeader() {
        return header;
    }

    /**
     * @return the column source: a {@code jcr:content} property name or a
     *         special token ({@code :title}, {@code :path}, {@code :url}).
     */
    public String getSource() {
        return source;
    }

    @Override
    public String toString() {
        return "ReportColumn{header='" + header + "', source='" + source + "'}";
    }
}
