package com.mysite.core.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BrandScopeTest {

    @Test
    void exposesBrandAndRoot() {
        final BrandScope s = new BrandScope("natwest", "/content/natwest");
        assertEquals("natwest", s.getBrand());
        assertEquals("/content/natwest", s.getRoot());
        assertTrue(s.toString().contains("natwest"));
        assertTrue(s.toString().contains("/content/natwest"));
    }
}
