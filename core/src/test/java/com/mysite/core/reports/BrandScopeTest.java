package com.mysite.core.reports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class BrandScopeTest {

    @Test
    void exposesBrandAndRoots() {
        final BrandScope s = new BrandScope("natwest", Arrays.asList("/content/natwest", "/content/nw2"));
        assertEquals("natwest", s.getBrand());
        assertEquals(2, s.getRoots().size());
        assertTrue(s.toString().contains("natwest"));
    }

    @Test
    void nullRootsBecomeEmptyList() {
        final BrandScope s = new BrandScope("rbs", null);
        assertTrue(s.getRoots().isEmpty());
    }
}
