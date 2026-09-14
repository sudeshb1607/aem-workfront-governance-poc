package com.mysite.core.util;

import java.util.Calendar;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;

import com.day.cq.replication.ReplicationStatus;

/**
 * Shared helpers for reading a page's replication / publication state from its
 * {@code jcr:content} node, centralising the logic used across the reports.
 */
public final class PagePublicationUtil {

    /** {@code cq:lastReplicationAction} value indicating the page is activated. */
    public static final String ACTION_ACTIVATE = "Activate";

    private static final String PN_LAST_REPLICATED = "cq:lastReplicated";
    private static final String PN_LAST_REPLICATION_ACTION = "cq:lastReplicationAction";

    private PagePublicationUtil() {
        // static utility
    }

    /**
     * Whether the page is currently activated (live) on the replication agent.
     *
     * @param contentResource the page's {@code jcr:content} resource (may be {@code null})
     * @return {@code true} when {@link ReplicationStatus#isActivated()} is true
     */
    public static boolean isPublished(final Resource contentResource) {
        if (contentResource == null) {
            return false;
        }
        final ReplicationStatus status = contentResource.adaptTo(ReplicationStatus.class);
        return status != null && status.isActivated();
    }

    /**
     * @param contentVm the page's {@code jcr:content} ValueMap
     * @return the last replication timestamp, or {@code null} if never replicated
     */
    public static Calendar lastReplicated(final ValueMap contentVm) {
        return contentVm == null ? null : contentVm.get(PN_LAST_REPLICATED, Calendar.class);
    }

    /**
     * @param contentVm the page's {@code jcr:content} ValueMap
     * @return the last replication action ({@code Activate}/{@code Deactivate}), or {@code null}
     */
    public static String lastReplicationAction(final ValueMap contentVm) {
        return contentVm == null ? null : contentVm.get(PN_LAST_REPLICATION_ACTION, String.class);
    }
}
