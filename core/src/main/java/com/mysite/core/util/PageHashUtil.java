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
package com.mysite.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Produces a stable, unique hash code for a page from its URL. Used by the CSV
 * generators so every exported page carries a deterministic identifier that can
 * later be used to refer back to that exact page.
 */
public final class PageHashUtil {

    private PageHashUtil() {
        // static utility
    }

    /**
     * Computes a lowercase hex SHA-256 digest of the given page URL. The same
     * URL always yields the same hash, and distinct URLs yield distinct hashes.
     *
     * @param url the page URL (or any stable page identifier); {@code null} is treated as empty
     * @return a 64-character lowercase hex string
     */
    public static String hash(final String url) {
        final String input = url != null ? url : "";
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (final byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (final NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JLS to be present; this is unreachable.
            return Integer.toHexString(input.hashCode());
        }
    }
}
