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
package com.mysite.core.services;

import org.osgi.annotation.versioning.ProviderType;

import com.mysite.core.models.workfront.ContentTreeConfig;

/**
 * Generates a CSV export of page metadata for a configured content tree and
 * writes it into the DAM. Implementations must isolate failures per tree and
 * are designed/validated to handle a single content tree of up to ~10,000
 * pages: pages are fetched in bounded batches (see {@code pageBatchSize}),
 * so a 10k-page tree is read in roughly 20 queries rather than one large
 * result set. See {@code docs/workfront-csv-export.md} for scaling details.
 */
@ProviderType
public interface WorkfrontCsvGeneratorService {

    /**
     * Generates the CSV for a single content tree and stores it in the DAM.
     *
     * @param treeConfig the content tree + target CSV name to export
     * @return the outcome of the generation attempt; never {@code null}
     */
    CsvGenerationResult generateCsv(ContentTreeConfig treeConfig);

    /**
     * @return the absolute DAM folder path CSV files are written into
     *         (e.g. {@code /content/dam/mysite/workfront-dashboard}).
     */
    String getDamRootPath();

    /**
     * Immutable outcome of a single CSV generation attempt.
     */
    final class CsvGenerationResult {

        private final boolean success;
        private final String csvPath;
        private final long pagesExported;
        private final String errorMessage;

        private CsvGenerationResult(final boolean success, final String csvPath,
                                    final long pagesExported, final String errorMessage) {
            this.success = success;
            this.csvPath = csvPath;
            this.pagesExported = pagesExported;
            this.errorMessage = errorMessage;
        }

        public static CsvGenerationResult success(final String csvPath, final long pagesExported) {
            return new CsvGenerationResult(true, csvPath, pagesExported, null);
        }

        public static CsvGenerationResult failure(final String csvPath, final String errorMessage) {
            return new CsvGenerationResult(false, csvPath, 0L, errorMessage);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getCsvPath() {
            return csvPath;
        }

        public long getPagesExported() {
            return pagesExported;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
