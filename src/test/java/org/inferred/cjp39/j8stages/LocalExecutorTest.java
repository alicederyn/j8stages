package org.inferred.cjp39.j8stages;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static java.util.concurrent.TimeUnit.SECONDS;

import static org.inferred.cjp39.j8stages.LocalExecutor.runNow;
import static org.inferred.cjp39.j8stages.LocalExecutor.runSoon;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

public class LocalExecutorTest {

    @Test
    public void tasks_run_at_end_of_runNow_block() {
        AtomicBoolean run1 = new AtomicBoolean();
        AtomicBoolean run2 = new AtomicBoolean();
        AtomicBoolean run3 = new AtomicBoolean();

        runNow(() -> {
            runSoon(() -> {
                runSoon(() -> {
                    runSoon(() -> run3.set(true));
                    run2.set(true);
                });
                run1.set(true);
            });
            assertFalse(run1.get());
            assertFalse(run2.get());
            assertFalse(run3.get());
        });
        assertTrue(run1.get());
        assertTrue(run2.get());
        assertTrue(run3.get());
    }

    @Test
    public void nested_tasks_run_asynchronously() {
        AtomicBoolean run1 = new AtomicBoolean();
        AtomicBoolean run2 = new AtomicBoolean();
        AtomicBoolean run3 = new AtomicBoolean();

        runNow(() -> {
            runSoon(() -> {
                runSoon(() -> {
                    runSoon(() -> {
                        assertTrue(run1.get());
                        assertTrue(run2.get());
                        run3.set(true);
                    });
                    assertTrue(run1.get());
                    assertFalse(run3.get());
                    run2.set(true);
                });
                assertFalse(run2.get());
                assertFalse(run3.get());
                run1.set(true);
            });
        });
    }

    @Test
    public void tasks_run_when_innermost_runNow_ends() {
        AtomicBoolean run1 = new AtomicBoolean();
        AtomicBoolean run2 = new AtomicBoolean();
        AtomicBoolean run3 = new AtomicBoolean();

        runNow(() -> {
            runNow(() -> {
                runSoon(() -> {
                    runSoon(() -> {
                        runSoon(() -> run3.set(true));
                        run2.set(true);
                    });
                    run1.set(true);
                });
            });
            assertTrue(run1.get());
            assertTrue(run2.get());
            assertTrue(run3.get());
        });
    }

    @Test
    public void tasks_run_immediately_outside_of_runNow_block() {
        AtomicBoolean run1 = new AtomicBoolean();
        AtomicBoolean run2 = new AtomicBoolean();
        AtomicBoolean run3 = new AtomicBoolean();

        runNow(() -> {});
        runSoon(() -> {
            runSoon(() -> {
                runSoon(() -> {
                    assertTrue(run1.get());
                    assertTrue(run2.get());
                    run3.set(true);
                });
                assertTrue(run1.get());
                assertFalse(run3.get());
                run2.set(true);
            });
            assertFalse(run2.get());
            assertFalse(run3.get());
            run1.set(true);
        });
        assertTrue(run1.get());
        assertTrue(run2.get());
        assertTrue(run3.get());
    }

    @Test
    public void tasks_run_immediately_on_worker_thread() {
        AtomicBoolean run1 = new AtomicBoolean();
        AtomicBoolean run2 = new AtomicBoolean();
        AtomicBoolean run3 = new AtomicBoolean();

        runNow(() -> {
            try (WorkerThread worker = new WorkerThread()) {
                worker.run(() -> {
                    runSoon(() -> {
                        runSoon(() -> {
                            runSoon(() -> {
                                assertTrue(run1.get());
                                assertTrue(run2.get());
                                run3.set(true);
                            });
                            assertTrue(run1.get());
                            assertFalse(run3.get());
                            run2.set(true);
                        });
                        assertFalse(run2.get());
                        assertFalse(run3.get());
                        run1.set(true);
                    });
                    assertTrue(run1.get());
                    assertTrue(run2.get());
                    assertTrue(run3.get());
                });
            }
        });
    }

    private static class WorkerThread implements AutoCloseable {
        private final ExecutorService executor =
                newSingleThreadExecutor(
                        new ThreadFactoryBuilder().setNameFormat("LocalExecutorTest worker thread").build());

        public void run(Runnable task) {
            try {
                executor.submit(task).get(10, SECONDS);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public void close() {
            executor.shutdown();
        }
    }

}
