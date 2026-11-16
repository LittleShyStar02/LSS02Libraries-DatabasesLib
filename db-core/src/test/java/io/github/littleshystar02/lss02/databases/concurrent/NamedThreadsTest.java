// SPDX-License-Identifier: GPL-3.0-only
package io.github.littleshystar02.lss02.databases.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link NamedThreads}. */
class NamedThreadsTest {

    /** Threads are named, daemon and not started. */
    @Test
    void daemonThreadHasTheGivenNameAndIsDaemon() {
        Thread thread = NamedThreads.daemon("Test-Worker", (t, e) -> { }).newThread(() -> { });
        assertEquals("Test-Worker", thread.getName());
        assertTrue(thread.isDaemon());
    }

    /** Pool threads are numbered from one. */
    @Test
    void numberedThreadsCountUp() {
        ThreadFactory factory = NamedThreads.numberedDaemon("Pool", (t, e) -> { });
        assertEquals("Pool-1", factory.newThread(() -> { }).getName());
        assertEquals("Pool-2", factory.newThread(() -> { }).getName());
    }

    /** An exception escaping the task reaches the handler. */
    @Test
    void uncaughtExceptionReachesTheHandler() throws InterruptedException {
        AtomicReference<Throwable> seen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread thread = NamedThreads.daemon("Boom", (t, e) -> {
            seen.set(e);
            done.countDown();
        }).newThread(() -> {
            throw new IllegalStateException("boom");
        });
        thread.start();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertNotNull(seen.get());
        assertEquals("boom", seen.get().getMessage());
    }

    /** The logger overload writes the failure at SEVERE with the thread name. */
    @Test
    void loggerOverloadReportsSevere() throws InterruptedException {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        AtomicReference<LogRecord> record = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord r) { record.set(r); done.countDown(); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        NamedThreads.daemon("Logged", logger).newThread(() -> {
            throw new IllegalStateException("x");
        }).start();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(java.util.logging.Level.SEVERE, record.get().getLevel());
        assertTrue(record.get().getMessage().contains("Logged"));
    }
}
