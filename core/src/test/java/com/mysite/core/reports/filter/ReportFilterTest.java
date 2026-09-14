package com.mysite.core.reports.filter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.day.cq.replication.ReplicationStatus;
import com.mysite.core.testcontext.AppAemContext;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

/**
 * Verifies each per-report {@link com.mysite.core.reports.ReportFilter} rule in
 * isolation. Date-only rules use AEM Mock pages (reported as not-activated); the
 * published-page rules use Mockito resources whose {@code jcr:content} adapts to a
 * {@link ReplicationStatus}.
 */
@ExtendWith(AemContextExtension.class)
class ReportFilterTest {

    private final AemContext context = AppAemContext.newAemContext();
    private final LocalDate today = LocalDate.now();

    // ---- date-only rules (AEM Mock) ---------------------------------------------

    private Resource pageWithProp(final String path, final String prop, final int daysAgo) {
        final Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -daysAgo);
        context.build()
                .resource(path, "jcr:primaryType", "cq:Page")
                .resource(path + "/jcr:content", "jcr:primaryType", "cq:PageContent", prop, cal)
                .commit();
        return context.resourceResolver().getResource(path);
    }

    @Test
    void notLiveStaleMatchesOldUnpublishedPages() {
        final NotLiveStaleFilter f = new NotLiveStaleFilter(today, 6, "cq:lastModified");
        final Resource stale = pageWithProp("/content/a", "cq:lastModified", 220);
        final Resource fresh = pageWithProp("/content/b", "cq:lastModified", 40);
        assertTrue(f.accept(stale, stale.getChild("jcr:content").getValueMap()));
        assertFalse(f.accept(fresh, fresh.getChild("jcr:content").getValueMap()));
    }

    @Test
    void archiveAgedMatchesWithinWindow() {
        final ArchiveAgedFilter f = new ArchiveAgedFilter(today, 60, 90, "cq:lastModified");
        final Resource in = pageWithProp("/content/w", "cq:lastModified", 75);
        final Resource tooNew = pageWithProp("/content/n", "cq:lastModified", 30);
        final Resource tooOld = pageWithProp("/content/o", "cq:lastModified", 200);
        assertTrue(f.accept(in, in.getChild("jcr:content").getValueMap()));
        assertFalse(f.accept(tooNew, tooNew.getChild("jcr:content").getValueMap()));
        assertFalse(f.accept(tooOld, tooOld.getChild("jcr:content").getValueMap()));
    }

    @Test
    void notLiveStaleRejectsPublishedPages() {
        final NotLiveStaleFilter f = new NotLiveStaleFilter(today, 6, "cq:lastModified");
        assertFalse(f.accept(mockPage(true, false), new ValueMapDecorator(new HashMap<>())));
    }

    // ---- published-page rules (Mockito) -----------------------------------------

    private Resource mockPage(final boolean activated, final boolean hasChild) {
        final Resource page = mock(Resource.class);
        final Resource content = mock(Resource.class);
        when(page.getChild("jcr:content")).thenReturn(content);
        final ReplicationStatus status = mock(ReplicationStatus.class);
        when(content.adaptTo(ReplicationStatus.class)).thenReturn(status);
        when(status.isActivated()).thenReturn(activated);
        if (hasChild) {
            final Resource kid = mock(Resource.class);
            when(kid.getValueMap()).thenReturn(new ValueMapDecorator(
                    Collections.singletonMap("jcr:primaryType", "cq:Page")));
            when(page.getChildren()).thenReturn(Collections.singletonList(kid));
        } else {
            when(page.getChildren()).thenReturn(Collections.emptyList());
        }
        return page;
    }

    private static ValueMap vm(final String key, final Object value) {
        final Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return new ValueMapDecorator(map);
    }

    private static Calendar monthsAgo(final int months) {
        final Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MONTH, -months);
        return cal;
    }

    @Test
    void allLiveMatchesOnlyPublished() {
        final AllLiveFilter f = new AllLiveFilter();
        assertTrue(f.accept(mockPage(true, false), new ValueMapDecorator(new HashMap<>())));
        assertFalse(f.accept(mockPage(false, false), new ValueMapDecorator(new HashMap<>())));
    }

    @Test
    void expiringPublishedMatchesWithinWindowIncludingExpired() {
        final ExpiringPublishedFilter f = new ExpiringPublishedFilter(today, 45);
        final Resource pub = mockPage(true, false);
        assertTrue(f.accept(pub, vm("contentReviewExpiryDate", today.plusDays(10).toString())));
        assertTrue(f.accept(pub, vm("contentReviewExpiryDate", today.minusDays(5).toString())));
        assertFalse(f.accept(pub, vm("contentReviewExpiryDate", today.plusDays(100).toString())));
        assertFalse(f.accept(pub, new ValueMapDecorator(new HashMap<>())));
        assertFalse(f.accept(mockPage(false, false), vm("contentReviewExpiryDate", today.plusDays(10).toString())));
    }

    @Test
    void liveLongNoChildrenMatchesOldPublishedLeafPages() {
        final LiveLongNoChildrenFilter f = new LiveLongNoChildrenFilter(today, 18);
        assertTrue(f.accept(mockPage(true, false), vm("cq:lastReplicated", monthsAgo(20))));
        assertFalse(f.accept(mockPage(true, true), vm("cq:lastReplicated", monthsAgo(20))));  // has child
        assertFalse(f.accept(mockPage(true, false), vm("cq:lastReplicated", monthsAgo(3))));  // recent
        assertFalse(f.accept(mockPage(false, false), vm("cq:lastReplicated", monthsAgo(20)))); // not published
    }
}
