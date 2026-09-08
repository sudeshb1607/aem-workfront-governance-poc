package com.mysite.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class HmacUtilTest {

    @Test
    void matchesKnownHmacSha256Vector() {
        // Well-known vector: HMAC-SHA256(key="key", msg="The quick brown fox jumps over the lazy dog").
        final String sig = HmacUtil.sha256Hex("key",
                "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8));
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8", sig);
    }

    @Test
    void producesLowercaseHex64() {
        final String sig = HmacUtil.sha256Hex("secret", "{}".getBytes(StandardCharsets.UTF_8));
        assertEquals(64, sig.length());
        org.junit.jupiter.api.Assertions.assertTrue(sig.matches("[0-9a-f]{64}"));
    }

    @Test
    void isDeterministic() {
        final byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        assertEquals(HmacUtil.sha256Hex("s", body), HmacUtil.sha256Hex("s", body));
    }
}
