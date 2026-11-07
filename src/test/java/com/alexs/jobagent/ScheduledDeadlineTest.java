package com.alexs.jobagent;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledDeadlineTest {
    @Test
    void expiredDeadlineRequestsExitThenForcesStopAfterGrace() throws Exception {
        CountDownLatch orderly = new CountDownLatch(1);
        CountDownLatch forced = new CountDownLatch(1);
        Thread watchdog = JobAgentApplication.startScheduledDeadline(Duration.ofMillis(10),
                Duration.ofMillis(20), orderly::countDown, forced::countDown);
        assertTrue(orderly.await(2, TimeUnit.SECONDS));
        assertTrue(forced.await(2, TimeUnit.SECONDS));
        watchdog.join(1000);
        assertFalse(watchdog.isAlive());
    }

    @Test
    void interruptedDeadlineStopsWithoutExitAndRetainsInterruptStatus() throws Exception {
        CountDownLatch orderly = new CountDownLatch(1);
        CountDownLatch forced = new CountDownLatch(1);
        Thread watchdog = JobAgentApplication.startScheduledDeadline(Duration.ofMinutes(1),
                Duration.ofMillis(20), orderly::countDown, forced::countDown);
        watchdog.interrupt();
        watchdog.join(1000);
        assertFalse(watchdog.isAlive());
        assertTrue(watchdog.isInterrupted());
        assertEquals(1, orderly.getCount());
        assertEquals(1, forced.getCount());
    }
}
