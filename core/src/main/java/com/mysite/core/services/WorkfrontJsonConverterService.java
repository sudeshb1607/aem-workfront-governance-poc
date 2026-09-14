package com.mysite.core.services;

import org.apache.sling.api.resource.Resource;
import org.osgi.annotation.versioning.ProviderType;

/**
 * Converts a single Workfront dashboard CSV asset into a JSON asset. The JSON is
 * a self-describing dataset ({@code dataset}, {@code generatedAt},
 * {@code recordCount}, {@code records[]}) later pushed to a Workfront Fusion
 * webhook by {@link WorkfrontWebhookService}.
 *
 * <p>Each CSV is converted independently so a failure on one never affects the
 * others (see {@code docs/workfront-json-webhook.md}).</p>
 */
@ProviderType
public interface WorkfrontJsonConverterService {

    /**
     * Converts one CSV asset to its JSON counterpart in the service's configured
     * output folder.
     *
     * @param csvAsset the DAM CSV asset resource to convert
     * @return the outcome of the conversion attempt; never {@code null}
     */
    ConversionResult convert(Resource csvAsset);

    /**
     * Converts one CSV asset to a JSON asset written into an explicit output
     * folder. Used by the multi-report scheduler so each report writes its JSON
     * into its own {@code <report>/json} folder.
     *
     * @param csvAsset         the DAM CSV asset resource to convert
     * @param jsonOutputFolder the DAM folder the JSON asset is written into
     * @return the outcome of the conversion attempt; never {@code null}
     */
    ConversionResult convert(Resource csvAsset, String jsonOutputFolder);

    /**
     * @return the absolute DAM folder scanned for source {@code *.csv} files.
     */
    String getInputFolder();

    /**
     * @return the absolute DAM folder JSON files are written into.
     */
    String getOutputFolder();

    /**
     * Immutable outcome of a single CSV -> JSON conversion attempt.
     */
    final class ConversionResult {

        private final boolean success;
        private final String jsonPath;
        private final long recordCount;
        private final String errorMessage;

        private ConversionResult(final boolean success, final String jsonPath,
                                 final long recordCount, final String errorMessage) {
            this.success = success;
            this.jsonPath = jsonPath;
            this.recordCount = recordCount;
            this.errorMessage = errorMessage;
        }

        public static ConversionResult success(final String jsonPath, final long recordCount) {
            return new ConversionResult(true, jsonPath, recordCount, null);
        }

        public static ConversionResult failure(final String jsonPath, final String errorMessage) {
            return new ConversionResult(false, jsonPath, 0L, errorMessage);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getJsonPath() {
            return jsonPath;
        }

        public long getRecordCount() {
            return recordCount;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
