package com.mysite.core.services.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.dam.api.Asset;
import com.day.cq.dam.api.Rendition;
import com.mysite.core.services.WorkfrontWebhookService;
import com.mysite.core.util.HmacUtil;

/**
 * Default {@link WorkfrontWebhookService}. Reads a JSON dataset asset, signs the
 * exact body bytes with HMAC-SHA256 using the configured shared secret, and POSTs
 * them to the configured Fusion webhook via {@link HttpURLConnection} (no extra
 * dependencies). The signature is sent as {@code <signatureHeader>: sha256=<hex>}
 * and the dataset name as {@code <datasetHeader>}.
 *
 * <p>The service refuses to send when the webhook URL or secret is not configured,
 * so an unsigned request is never made.</p>
 */
@Designate(ocd = WorkfrontWebhookServiceImpl.Config.class)
@Component(service = WorkfrontWebhookService.class)
public class WorkfrontWebhookServiceImpl implements WorkfrontWebhookService {

    private static final Logger LOG = LoggerFactory.getLogger(WorkfrontWebhookServiceImpl.class);

    private static final String JSON_EXTENSION = ".json";

    @ObjectClassDefinition(
            name = "Workfront Webhook Service",
            description = "Sends signed JSON datasets to the Workfront Fusion webhook.")
    public @interface Config {

        @AttributeDefinition(name = "Webhook URL",
                description = "The Workfront Fusion webhook each dataset is POSTed to.")
        String webhookUrl() default "";

        @AttributeDefinition(name = "Webhook secret",
                description = "Shared secret for the HMAC-SHA256 body signature. Set per environment; "
                        + "leave empty in source control. Sending is skipped when empty.",
                type = org.osgi.service.metatype.annotations.AttributeType.PASSWORD)
        String webhookSecret() default "";

        @AttributeDefinition(name = "Signature header",
                description = "Request header carrying the 'sha256=<hex>' HMAC signature.")
        String signatureHeader() default "X-Workfront-Signature";

        @AttributeDefinition(name = "Dataset header",
                description = "Request header carrying the dataset name (CSV base name).")
        String datasetHeader() default "X-Workfront-Dataset";

        @AttributeDefinition(name = "Connect timeout (ms)",
                description = "HTTP connect timeout in milliseconds.")
        int connectTimeoutMs() default 10000;

        @AttributeDefinition(name = "Read timeout (ms)",
                description = "HTTP read timeout in milliseconds.")
        int readTimeoutMs() default 30000;
    }

    private String webhookUrl;
    private String webhookSecret;
    private String signatureHeader;
    private String datasetHeader;
    private int connectTimeoutMs;
    private int readTimeoutMs;

    @Activate
    protected void activate(final Config config) {
        this.webhookUrl = StringUtils.trimToEmpty(config.webhookUrl());
        this.webhookSecret = StringUtils.defaultString(config.webhookSecret());
        this.signatureHeader = StringUtils.defaultIfBlank(config.signatureHeader(), "X-Workfront-Signature");
        this.datasetHeader = StringUtils.defaultIfBlank(config.datasetHeader(), "X-Workfront-Dataset");
        this.connectTimeoutMs = Math.max(0, config.connectTimeoutMs());
        this.readTimeoutMs = Math.max(0, config.readTimeoutMs());
        // Never log the secret; only whether it is configured.
        LOG.info("WorkfrontWebhookService activated. webhookUrl={}, secretConfigured={}, "
                        + "signatureHeader={}, datasetHeader={}, connectTimeoutMs={}, readTimeoutMs={}",
                StringUtils.isBlank(webhookUrl) ? "<unset>" : webhookUrl,
                StringUtils.isNotEmpty(webhookSecret), signatureHeader, datasetHeader,
                connectTimeoutMs, readTimeoutMs);
    }

    @Override
    public SendResult send(final Resource jsonAsset) {
        final String dataset = StringUtils.removeEndIgnoreCase(jsonAsset.getName(), JSON_EXTENSION);

        if (StringUtils.isBlank(webhookUrl) || StringUtils.isEmpty(webhookSecret)) {
            final String msg = "Webhook URL or secret not configured; refusing to send dataset '" + dataset + "'";
            LOG.error(msg);
            return SendResult.failure(0, msg);
        }

        final byte[] body;
        try {
            body = readOriginal(jsonAsset);
        } catch (final Exception e) {
            LOG.error("Could not read JSON asset {}", jsonAsset.getPath(), e);
            return SendResult.failure(0, "read failed: " + e.getMessage());
        }

        return sendBody(dataset, body);
    }

    /**
     * Signs the given body with HMAC-SHA256 and POSTs it. Package-private so the
     * signing + HTTP path can be unit-tested with raw bytes (no DAM I/O). Refuses
     * to send when the URL/secret is not configured.
     */
    SendResult sendBody(final String dataset, final byte[] body) {
        if (StringUtils.isBlank(webhookUrl) || StringUtils.isEmpty(webhookSecret)) {
            final String msg = "Webhook URL or secret not configured; refusing to send dataset '" + dataset + "'";
            LOG.error(msg);
            return SendResult.failure(0, msg);
        }
        final String signature = "sha256=" + HmacUtil.sha256Hex(webhookSecret, body);
        return post(dataset, body, signature);
    }

    private SendResult post(final String dataset, final byte[] body, final String signature) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(webhookUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty(signatureHeader, signature);
            conn.setRequestProperty(datasetHeader, dataset);
            conn.setFixedLengthStreamingMode(body.length);

            // Full request dump (method, URL, headers, body) on a single line for readability.
            LOG.info("Webhook request for dataset '{}': POST {} | Content-Type: application/json; charset=utf-8"
                            + " | {}: {} | {}: {} | Body ({} bytes): {}",
                    dataset, webhookUrl,
                    signatureHeader, signature,
                    datasetHeader, dataset,
                    body.length, oneLine(new String(body, StandardCharsets.UTF_8)));

            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }

            final int status = conn.getResponseCode();
            final String responseBody = oneLine(readResponse(conn, status));
            if (status >= 200 && status < 300) {
                LOG.info("Sent dataset '{}' ({} bytes) to webhook: HTTP {} | Response body: {}",
                        dataset, body.length, status, responseBody);
                return SendResult.success(status);
            }
            LOG.warn("Webhook rejected dataset '{}': HTTP {} | Response body: {}", dataset, status, responseBody);
            return SendResult.failure(status, "HTTP " + status + (responseBody.isEmpty() ? "" : " - " + responseBody));
        } catch (final IOException e) {
            LOG.warn("Webhook send failed for dataset '{}': {}", dataset, e.getMessage());
            return SendResult.failure(0, e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static byte[] readOriginal(final Resource jsonAsset) throws Exception {
        final Asset asset = jsonAsset.adaptTo(Asset.class);
        if (asset == null) {
            throw new IllegalStateException("Not a DAM asset: " + jsonAsset.getPath());
        }
        final Rendition original = asset.getOriginal();
        if (original == null) {
            throw new IllegalStateException("Asset has no original rendition: " + asset.getPath());
        }
        try (InputStream in = original.getStream()) {
            if (in == null) {
                throw new IllegalStateException("Could not read original rendition of " + asset.getPath());
            }
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }
            return bos.toByteArray();
        }
    }

    /** Collapses line breaks to single spaces so a value logs on one line. */
    private static String oneLine(final String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
    }

    /** Reads the response body (success or error stream) for diagnostics (best-effort). */
    private static String readResponse(final HttpURLConnection conn, final int status) {
        final InputStream stream;
        try {
            stream = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
        } catch (final IOException e) {
            return "";
        }
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream) {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            final byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (final IOException e) {
            return "";
        }
    }
}
