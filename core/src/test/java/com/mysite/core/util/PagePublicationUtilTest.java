package com.mysite.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.jupiter.api.Test;

class PagePublicationUtilTest {

    private static ValueMap vm(final Map<String, Object> map) {
        return new ValueMapDecorator(map);
    }

    @Test
    void readsReplicationMetadata() {
        final Calendar cal = Calendar.getInstance();
        final Map<String, Object> map = new HashMap<>();
        map.put("cq:lastReplicated", cal);
        map.put("cq:lastReplicationAction", "Activate");
        final ValueMap content = vm(map);

        assertEquals(cal, PagePublicationUtil.lastReplicated(content));
        assertEquals("Activate", PagePublicationUtil.lastReplicationAction(content));
    }

    @Test
    void missingMetadataIsNull() {
        final ValueMap empty = vm(new HashMap<>());
        assertNull(PagePublicationUtil.lastReplicated(empty));
        assertNull(PagePublicationUtil.lastReplicationAction(empty));
        assertNull(PagePublicationUtil.lastReplicated(null));
        assertNull(PagePublicationUtil.lastReplicationAction(null));
    }

    @Test
    void nullContentResourceIsNotPublished() {
        assertFalse(PagePublicationUtil.isPublished(null));
    }
}
