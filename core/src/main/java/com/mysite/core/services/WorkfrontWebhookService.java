package com.mysite.core.services;

import org.apache.sling.api.resource.Resource;
import org.osgi.annotation.versioning.ProviderType;

/**
 * Sends a single JSON dataset asset to the configured Workfront Fusion webhook as
 * one HTTP POST, signed with HMAC-SHA256 over the request body so Fusion can
 * verify the request is genuine.
 *
 * <p>Each dataset is sent independently (see {@code docs/workfront-json-webhook.md}).</p>
 */
@ProviderType
public interface WorkfrontWebhookService {

    /**
     * Sends one JSON dataset asset to the webhook.
     *
     * @param jsonAsset the DAM JSON asset resource to send
     * @return the outcome of the send attempt; never {@code null}
     */
    SendResult send(Resource jsonAsset);

    /**
     * Immutable outcome of a single webhook send attempt.
     */
    final class SendResult {

        private final boolean success;
        private final int httpStatus;
        private final String errorMessage;

        private SendResult(final boolean success, final int httpStatus, final String errorMessage) {
            this.success = success;
            this.httpStatus = httpStatus;
            this.errorMessage = errorMessage;
        }

        public static SendResult success(final int httpStatus) {
            return new SendResult(true, httpStatus, null);
        }

        public static SendResult failure(final int httpStatus, final String errorMessage) {
            return new SendResult(false, httpStatus, errorMessage);
        }

        public boolean isSuccess() {
            return success;
        }

        public int getHttpStatus() {
            return httpStatus;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
