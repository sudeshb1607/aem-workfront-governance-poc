package com.mysite.core.schedulers;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SchedulerSupportTest {

    @AfterEach
    void clearInterrupt() {
        // Ensure a stray interrupt flag never leaks into the next test.
        Thread.interrupted();
    }

    @Test
    void zeroOrNegativeContinuesImmediately() {
        final CountDownLatch latch = new CountDownLatch(1);
        assertTrue(SchedulerSupport.await(0, latch));
        assertTrue(SchedulerSupport.await(-1, latch));
    }

    @Test
    void alreadyDeactivatedStops() {
        final CountDownLatch latch = new CountDownLatch(1);
        latch.countDown();
        assertFalse(SchedulerSupport.await(0, latch), "should stop when latch already fired");
        assertFalse(SchedulerSupport.await(10, latch), "should stop without waiting when latch already fired");
    }

    @Test
    void realWaitTimesOutAndContinues() {
        final CountDownLatch latch = new CountDownLatch(1);
        final long start = System.nanoTime();
        assertTrue(SchedulerSupport.await(1, latch));
        final long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMillis >= 900L, "expected a real ~1s wait but was " + elapsedMillis + "ms");
    }

    @Test
    void deactivationDuringWaitStops() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        // Fire the latch shortly after the wait starts, simulating @Deactivate.
        final Thread deactivator = new Thread(() -> {
            try {
                Thread.sleep(300L);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            latch.countDown();
        });
        deactivator.start();
        assertFalse(SchedulerSupport.await(30, latch), "should stop when deactivated mid-wait");
        deactivator.join(2000L);
    }

    @Test
    void spuriousInterruptDoesNotStopTheRun() {
        // A transient interrupt (not a deactivation) must NOT abort: await clears
        // the flag and returns true so the remaining work continues.
        final CountDownLatch latch = new CountDownLatch(1);
        Thread.currentThread().interrupt();
        assertTrue(SchedulerSupport.await(10, latch), "spurious interrupt should not stop the run");
        assertFalse(Thread.currentThread().isInterrupted(), "interrupt flag should have been cleared");
    }
}
