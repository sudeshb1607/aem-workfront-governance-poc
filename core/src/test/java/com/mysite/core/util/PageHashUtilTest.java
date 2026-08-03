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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PageHashUtilTest {

    @Test
    void isDeterministic() {
        assertEquals(PageHashUtil.hash("/content/site/en.html"),
                PageHashUtil.hash("/content/site/en.html"));
    }

    @Test
    void distinctUrlsProduceDistinctHashes() {
        assertNotEquals(PageHashUtil.hash("/content/site/a.html"),
                PageHashUtil.hash("/content/site/b.html"));
    }

    @Test
    void producesLowercaseHex64() {
        final String hash = PageHashUtil.hash("/content/site/en.html");
        assertEquals(64, hash.length(), "SHA-256 hex is 64 characters");
        assertTrue(hash.matches("[0-9a-f]{64}"), "hash is lowercase hex");
    }

    @Test
    void nullTreatedAsEmpty() {
        assertEquals(PageHashUtil.hash(""), PageHashUtil.hash(null));
    }

    @Test
    void matchesKnownSha256Vector() {
        // SHA-256 of the empty string.
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                PageHashUtil.hash(""));
    }
}
