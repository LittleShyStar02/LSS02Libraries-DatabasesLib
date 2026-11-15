package io.github.littleshystar02.lss02.databases.concurrent;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Factories for named daemon threads that never die silently: every thread gets an
 * {@link Thread.UncaughtExceptionHandler}, so an exception that escapes a task is reported instead of vanishing.
 *
 * <p>Typical use:
 * <pre>{@code
 * ExecutorService io = Executors.newSingleThreadExecutor(NamedThreads.daemon("MyPlugin-Storage", logger));
 * }</pre>
 *
 * <p>Daemon threads never keep the JVM alive, which is what a game server plugin wants for background workers.
 */
public final class NamedThreads {

    private NamedThreads() {
    }

    /**
     * Creates a factory whose threads are all called {@code name}, are daemons and report uncaught exceptions to the
     * given logger at {@link Level#SEVERE}.
     *
     * @param name the thread name
     * @param log  where uncaught exceptions are reported
     * @return the thread factory
     */
    public static ThreadFactory daemon(String name, Logger log) {
        Objects.requireNonNull(log, "log");
        return daemon(name, loggingHandler(log));
    }

    /**
     * Creates a factory whose threads are all called {@code name}, are daemons and use the given handler for
     * uncaught exceptions.
     *
     * @param name    the thread name
     * @param handler receives the exceptions that escape a thread
     * @return the thread factory
     */
    public static ThreadFactory daemon(String name, Thread.UncaughtExceptionHandler handler) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(handler, "handler");
        return runnable -> newDaemon(runnable, name, handler);
    }

    /**
     * Creates a factory for pools with more than one thread: threads are called {@code prefix-1}, {@code prefix-2},
     * and so on.
     *
     * @param prefix  the thread name prefix
     * @param handler receives the exceptions that escape a thread
     * @return the thread factory
     */
    public static ThreadFactory numberedDaemon(String prefix, Thread.UncaughtExceptionHandler handler) {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(handler, "handler");
        AtomicInteger counter = new AtomicInteger();
        return runnable -> newDaemon(runnable, prefix + "-" + counter.incrementAndGet(), handler);
    }

    /**
     * Creates a factory for pools with more than one thread, reporting to a logger.
     *
     * @param prefix the thread name prefix
     * @param log    where uncaught exceptions are reported
     * @return the thread factory
     * @see #numberedDaemon(String, Thread.UncaughtExceptionHandler)
     */
    public static ThreadFactory numberedDaemon(String prefix, Logger log) {
        Objects.requireNonNull(log, "log");
        return numberedDaemon(prefix, loggingHandler(log));
    }

    /**
     * Creates one named daemon thread (not started), for code that manages a thread by hand.
     *
     * @param runnable the task to run
     * @param name     the thread name
     * @param handler  receives the exceptions that escape the thread
     * @return the new, not yet started, daemon thread
     */
    public static Thread newDaemon(Runnable runnable, String name, Thread.UncaughtExceptionHandler handler) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler(handler);
        return thread;
    }

    private static Thread.UncaughtExceptionHandler loggingHandler(Logger log) {
        return (thread, error) -> log.log(Level.SEVERE, "Uncaught exception in thread " + thread.getName(), error);
    }
}
