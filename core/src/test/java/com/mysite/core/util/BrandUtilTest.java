package com.mysite.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BrandUtilTest {

    @Test
    void brandNameIsSegmentAfterContent() {
        assertEquals("natwest", BrandUtil.brandName("/content/natwest/gb/en"));
        assertEquals("mysite", BrandUtil.brandName("/content/mysite"));
        assertEquals("", BrandUtil.brandName("/conf/mysite"));
        assertEquals("", BrandUtil.brandName(null));
    }

    @Test
    void sanitizeProducesFileSafeToken() {
        assertEquals("natwest-uk", BrandUtil.sanitize("NatWest UK"));
        assertEquals("rbs", BrandUtil.sanitize("  RBS  "));
        assertEquals("ulster", BrandUtil.sanitize("Ulster!"));
        assertEquals("unknown", BrandUtil.sanitize("   "));
        assertEquals("unknown", BrandUtil.sanitize(null));
    }
}
