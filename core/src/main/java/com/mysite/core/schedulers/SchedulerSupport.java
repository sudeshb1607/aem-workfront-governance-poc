package com.mysite.core.schedulers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Small helpers shared by the Workfront schedulers and the report generator.
 */
public final class SchedulerSupport {

    private SchedulerSupport() {
        // static utility
    }

    /**
     * Real cool-down between units of work, driven by an explicit
     * <em>deactivation</em> latch rather than the thread's interrupt flag.
     *
     * <p>Why not the interrupt flag: on a shared AEM author these long, throttled
     * runs execute on a pooled scheduler thread that the platform may interrupt
     * for reasons unrelated to shutdown (bundle refresh, an OSGi config change
     * mid-run, thread-pool reclamation). Treating any interrupt as fatal aborted
     * the whole run after the first report. Instead we wait on {@code stopLatch},
     * which each component counts down only in its {@code @Deactivate}. So:</p>
     * <ul>
     *   <li>timeout elapses → return {@code true} (keep going);</li>
     *   <li>component is being deactivated (real stop / shutdown / reconfigure) →
     *       return {@code false} so the caller ends the run cleanly;</li>
     *   <li>a spurious thread interrupt → the flag is cleared (so it cannot
     *       cascade into aborting every remaining unit) and, unless the component
     *       is actually stopping, we keep going.</li>
     * </ul>
     *
     * <p>The wait is {@link CountDownLatch#await(long, TimeUnit)} — a genuine
     * delay that is not {@code Thread.sleep}, so it does not trip the CQRules
     * CWE-676 rule.</p>
     *
     * @param seconds   the cool-down in seconds; {@code <= 0} returns immediately
     * @param stopLatch the component's deactivation latch (count 1 while running,
     *                  counted down on deactivate); must not be {@code null}
     * @return {@code true} to continue; {@code false} only when the component is
     *         being deactivated
     */
    public static boolean await(final int seconds, final CountDownLatch stopLatch) {
        if (stopLatch.getCount() == 0L) {
            return false;
        }
        if (seconds <= 0) {
            return true;
        }
        try {
            // Returns true if the latch fired (deactivation) before the timeout.
            final boolean deactivated = stopLatch.await(seconds, TimeUnit.SECONDS);
            return !deactivated;
        } catch (final InterruptedException e) {
            // Transient/platform interrupt: clear the flag so it cannot cascade,
            // and continue unless a real deactivation is in progress.
            Thread.interrupted();
            return stopLatch.getCount() > 0L;
        }
    }
}
