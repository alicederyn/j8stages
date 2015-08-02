package org.inferred.cjp39.j8stages;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility methods for asynchronous execution of recursive tasks without
 * synchronization or thread pools.
 *
 * <p>
 * {@link #runNow(Runnable)} creates a work queue and executes a task, while
 * {@link #runSoon(Runnable)} submits a new task to the work queue. This allows
 * asynchronous tasks to be executed sequentially and as soon as possible,
 * without the risk of {@link StackOverflowError}s that direct execution (
 * {@link Runnable#run() Runnable::run}) leads to.
 *
 * <p>
 * {@link #multistepExecutor()} allows a framework to provide methods that run
 * immediately if called sequentially within a single {@code runNow} block, but
 * asynchronously otherwise. This allows standard single-threaded idioms, like
 * create-set, to run synchronously.
 */
public class LocalExecutor {

    private static final class ThreadData {
        Deque<LocalExecutor> activeExecutors = new ArrayDeque<>();
        long stamp = 0;
    }

    private static Logger LOG = Logger.getLogger(LocalExecutor.class.getName());
    private static final ThreadLocal<ThreadData> THREAD_DATA = new ThreadLocal<ThreadData>() {
        @Override
        protected ThreadData initialValue() {
            return new ThreadData();
        }
    };

    /**
     * Schedules {@code command} on the most recently created local executor, or
     * run it in a fresh local executor if none are active.
     */
    public static void runSoon(Runnable command) {
        LocalExecutor currentExecutor = THREAD_DATA.get().activeExecutors.peek();
        if (currentExecutor != null) {
            currentExecutor.add(command);
        } else {
            runNow(command);
        }
    }

    /**
     * Runs {@code command}, and any commands it asynchronously schedules with
     * {@link #runSoon}.
     */
    public static void runNow(Runnable command) {
        ThreadData threadData = THREAD_DATA.get();
        LocalExecutor executor = new LocalExecutor(++threadData.stamp);
        executor.add(command);
        threadData.activeExecutors.push(executor);
        try {
            executor.execute();
        } finally {
            threadData.activeExecutors.remove(executor);
        }
    }

    /**
     * Returns an executor that delegates to {@link #runNow(Runnable)} if used
     * on the same thread when only old local executors are running, or
     * {@link #runSoon(Runnable)} otherwise.
     *
     * <p>
     * This allows frameworks to synchronously execute standard single-threaded
     * idioms, like create-set, without forcing the user to run within a
     * {@code runNow} block.
     *
     * @returns the delegating executor, or {@link Optional#empty()} if no local
     *          executors are running
     */
    public static Optional<Executor> multistepExecutor() {
        ThreadData threadData = THREAD_DATA.get();
        if (threadData.activeExecutors.isEmpty()) {
            // Avoid creating a new object in the common case.
            return Optional.empty();
        }
        return Optional.of(new MultiStepExecutor(threadData.stamp));
    }

    private static final class MultiStepExecutor implements Executor {
        private final WeakReference<Thread> thread;
        private final long stamp;

        MultiStepExecutor(long stamp) {
            this.thread = new WeakReference<>(Thread.currentThread());
            this.stamp = stamp;
        }

        @Override
        public void execute(Runnable command) {
            LocalExecutor currentExecutor = THREAD_DATA.get().activeExecutors.peek();
            boolean executorIsOld =
                    Thread.currentThread() == thread.get()
                            && currentExecutor != null
                            && currentExecutor.stamp <= stamp;
            if (currentExecutor == null || executorIsOld) {
                runNow(command);
            } else {
                currentExecutor.add(command);
            }

        }
    }

    private final Deque<Runnable> work = new ArrayDeque<>();
    private final long stamp;

    private LocalExecutor(long stamp) {
        this.stamp = stamp;
    }

    private void add(Runnable command) {
        work.push(command);
    }

    /**
     * Executes all pending tasks. Any new tasks added on this thread before
     * this method returns will also be executed.
     */
    public void execute() {
        Runnable task;
        while ((task = work.poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException | Error e) {
                LOG.log(Level.WARNING, "LocalExecutor caught exception", e);
            }
        }
    }
}
