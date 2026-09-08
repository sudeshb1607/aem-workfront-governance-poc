package com.mysite.core.util;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 signing helper. Used to sign the JSON payload sent to the Workfront
 * Fusion webhook so the receiver can verify the request is genuine by recomputing
 * {@code HMAC-SHA256(secret, body)} over the raw request body and comparing.
 */
public final class HmacUtil {

    private static final String ALGORITHM = "HmacSHA256";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private HmacUtil() {
        // static utility
    }

    /**
     * Computes the lowercase hex HMAC-SHA256 of {@code body} using {@code secret}.
     *
     * @param secret the shared secret key (UTF-8)
     * @param body   the exact bytes that will be sent as the request body
     * @return the 64-character lowercase hex digest
     * @throws IllegalStateException if the platform lacks HmacSHA256 (should never happen)
     */
    public static String sha256Hex(final String secret, final byte[] body) {
        try {
            final Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            final byte[] sig = mac.doFinal(body);
            final StringBuilder sb = new StringBuilder(sig.length * 2);
            for (final byte b : sig) {
                sb.append(HEX[(b >> 4) & 0xF]).append(HEX[b & 0xF]);
            }
            return sb.toString();
        } catch (final GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 signing failed", e);
        }
    }
}
