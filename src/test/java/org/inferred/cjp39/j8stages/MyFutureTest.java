package org.inferred.cjp39.j8stages;

import static java.util.concurrent.CompletableFuture.completedFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import static com.google.common.truth.Truth.assertThat;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.inferred.cjp39.j8stages.MyFuture.RethrownException;
import org.junit.Test;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;

public class MyFutureTest {

    private static final class MyRuntimeException extends RuntimeException {
        private static final long serialVersionUID = -1842821516258779581L;

        private MyRuntimeException(String message) {
            super(message);
        }
    }

    private static final class MyError extends Error {
        private static final long serialVersionUID = 9187743018039390988L;

        private MyError(String message) {
            super(message);
        }
    }

    private static final class MyException extends Exception {
        private static final long serialVersionUID = 4411554210045482073L;

        private MyException(String message) {
            super(message);
        }
    }

    private static final com.google.common.base.Function<Integer, Integer> ADD_TWO = v -> v + 2;

    @Test
    public void combine_result_available_immediately() {
        MyFuture<Integer> a = new MyFuture<>();
        MyFuture<Integer> b = new MyFuture<>();
        a.complete(3);
        b.complete(4);
        MyFuture<Integer> c = a.thenCombine(b, (x, y) -> x + y);
        assertEquals(7, (int) c.getNow(-1));
    }

    @Test
    public void combine_result_available_immediately_inside_local_executor() {
        LocalExecutor.runNow(() -> {
            MyFuture<Integer> a = new MyFuture<>();
            MyFuture<Integer> b = new MyFuture<>();
            a.complete(3);
            b.complete(4);
            MyFuture<Integer> c = a.thenCombine(b, (x, y) -> x + y);
            assertEquals(7, (int) c.getNow(-1));
        });
    }

    @Test
    public void dependent_stage_completes_after_local_executor_closes() {
        MyFuture<Integer> head = new MyFuture<>();
        MyFuture<Integer> tail = head.thenApply(v -> v + 1);
        LocalExecutor.runNow(() -> {
            head.complete(7);
            assertFalse(tail.isDone());
        });
        assertTrue(tail.isDone());
        assertEquals(8, (int) tail.getNow(-1));
    }

    @Test
    public void dependent_stage_completes_immediately_in_locallyRun_block() {
        LocalExecutor.runNow(() -> {
            MyFuture<Integer> head = new MyFuture<>();
            MyFuture<Integer> tail = head.thenApply(v -> v + 1);
            head.complete(7);
            assertEquals(8, (int) tail.getNow(-1));
        });
    }

    @Test
    public void dependent_stage_completes_after_local_executor_closes_in_locallyRun_block() {
        LocalExecutor.runNow(() -> {
            MyFuture<Integer> head = new MyFuture<>();
            MyFuture<Integer> tail = head.thenApply(v -> v + 1);
            LocalExecutor.runNow(() -> {
                head.complete(7);
                assertFalse(tail.isDone());
            });
            assertTrue(tail.isDone());
            assertEquals(8, (int) tail.getNow(-1));
        });
    }

    @Test
    public void a_hundred_thousand_dependent_completion_stages() {
        CompletableFuture<Integer> head = new CompletableFuture<>();
        CompletableFuture<Integer> tail = head;
        for (int i = 0; i < 100_000; i++) {
            tail = tail.thenApply(v -> v + 1);
        }
        head.complete(7);
        assertEquals(100_007, (int) tail.getNow(-1));
    }

    @Test
    public void a_hundred_thousand_dependent_stages_do_not_overflow() {
        MyFuture<Integer> head = new MyFuture<>();
        MyFuture<Integer> tail = head;
        for (int i = 0; i < 100_000; i++) {
            tail = tail.thenApply(v -> v + 1);
        }
        head.complete(7);
        assertEquals(100_007, (int) tail.getNow(-1));
    }

    @Test
    public void a_hundred_thousand_recursive_style_callbacks_with_MyFuture() {
        MyFuture<Integer> head = new MyFuture<>();
        MyFuture<Integer> tail = head;
        for (int i = 0; i < 100_000; i++) {
            MyFuture<Integer> newTail = new MyFuture<>();
            tail.thenAccept(v -> newTail.complete(v + 1));
            tail = newTail;
        }
        head.complete(7);
        assertEquals(100_007, (int) tail.getNow(-1));
    }

    @Test
    public void a_hundred_thousand_concurrent_stages_do_not_overflow() {
        MyFuture<Integer> head = new MyFuture<>();
        MyFuture<Integer> tail = head;
        for (int i = 0; i < 100_000; i++) {
            tail = head.thenApply(v -> v + 1);
        }
        head.complete(7);
        assertEquals(8, (int) tail.getNow(-1));
    }

    @Test
    public void a_hundred_thousand_stages_via_listenable_futures_do_not_overflow() {
        MyFuture<Integer> head = new MyFuture<>();
        MyFuture<Integer> tail = head;
        for (int i = 0; i < 100_000; i++) {
            ListenableFuture<Integer> step = Futures.transform(tail.toListenableFuture(), ADD_TWO);
            tail = MyFuture.from(step).thenApply(v -> v - 1);
        }
        head.complete(7);
        assertEquals(100_007, (int) tail.getNow(-1));
    }

    @Test
    public void two_thousand_stages_of_listenable_futures_overflows() {
        SettableFuture<Integer> head = SettableFuture.create();
        ListenableFuture<Integer> tail = head;
        for (int i = 0; i < 2_000; i++) {
            tail = Futures.transform(tail, (Integer x) -> x + 1);
        }
        head.set(7);
        assertFalse(tail.isDone());
    }

    @Test
    public void two_thousand_stages_of_explicitly_completing_futures_overflows() {
        CompletableFuture<Integer> head = new CompletableFuture<>();
        CompletableFuture<Integer> tail = head;
        for (int i = 0; i < 2_000; i++) {
            CompletableFuture<Integer> newTail = new CompletableFuture<>();
            tail.thenAccept(v -> newTail.complete(v + 1));
            tail = newTail;
        }
        head.complete(7);
        assertFalse(tail.isDone());
    }

    @Test
    public void cancelling_cancels_original_listenable_future() {
        SettableFuture<Integer> future = SettableFuture.create();
        MyFuture.from(future).cancel();
        assertTrue(future.isCancelled());
    }

    @Test
    public void a_hundred_thousand_stages_via_CompletableFuture_thenCombine_do_not_overflow() {
        MyFuture<Integer> head = new MyFuture<>();
        MyFuture<Integer> tail = head;
        for (int i = 0; i < 100_000; i++) {
            CompletableFuture<Integer> step = completedFuture(2).thenCombine(tail, (a, b) -> a + b);
            tail = MyFuture.completed(1).thenCombine(step, (a, b) -> b - a);
        }
        head.complete(7);
        assertEquals(100_007, (int) tail.getNow(-1));
    }

    @Test
    public void a_hundred_thousand_stages_via_toCompletableFuture_do_not_overflow() {
        MyFuture<Integer> head = new MyFuture<>();
        MyFuture<Integer> tail = head;
        for (int i = 0; i < 100_000; i++) {
            CompletableFuture<Integer> step = tail.toCompletableFuture().thenApply(v -> v + 2);
            tail = MyFuture.from(step).thenApply(v -> v - 1);
        }
        head.complete(7);
        assertEquals(100_007, (int) tail.getNow(-1));
    }

    @Test
    public void cancelling_completable_future_cancels_original() {
        MyFuture<Integer> future = new MyFuture<>();
        future.toCompletableFuture().cancel(true);
        assertTrue(future.isCancelled());
    }

    @Test
    public void whenComplete_adds_suppressed_exception() {
        try {
            MyFuture<?> future =
                    MyFuture.completedExceptionally(new MyRuntimeException("first")).whenComplete((t, x) -> {
                        throw new RuntimeException("second");
                    });
            future.getNow(null);
            fail();
        } catch (MyRuntimeException e) {
            assertEquals("first", e.getMessage());
            assertThat(e.getSuppressed()).hasLength(2);
            assertEquals("second", e.getSuppressed()[0].getMessage());
            assertThat(e.getSuppressed()[1]).isInstanceOf(RethrownException.class);
        }
    }

    @Test
    public void getNow_throws_cloned_RuntimeException() {
        MyRuntimeException x = new MyRuntimeException("failed");
        MyFuture<?> future = MyFuture.completedExceptionally(x);
        try {
            future.getNow(null);
            fail();
        } catch (MyRuntimeException e) {
            assertEquals("failed", e.getMessage());
            assertThat(e.getCause()).isNull();
            assertThat(e.getSuppressed()).hasLength(1);
            assertThat(e.getSuppressed()[0]).isInstanceOf(RethrownException.class);
            assertThat(x.getSuppressed()).hasLength(0);
        }
    }

    @Test
    public void getNow_throws_cloned_CancellationException() {
        CancellationException x = new CancellationException("failed");
        MyFuture<?> future = MyFuture.completedExceptionally(x);
        try {
            future.getNow(null);
            fail();
        } catch (CancellationException e) {
            assertEquals("failed", e.getMessage());
            assertThat(e.getCause()).isNull();
            assertThat(e.getSuppressed()).hasLength(1);
            assertThat(e.getSuppressed()[0]).isInstanceOf(RethrownException.class);
            assertThat(x.getSuppressed()).hasLength(0);
        }
    }

    @Test
    public void getNow_throws_cloned_Error() {
        MyError x = new MyError("failed");
        MyFuture<?> future = MyFuture.completedExceptionally(x);
        try {
            future.getNow(null);
            fail();
        } catch (MyError e) {
            assertEquals("failed", e.getMessage());
            assertThat(e.getCause()).isNull();
            assertThat(e.getSuppressed()).hasLength(1);
            assertThat(e.getSuppressed()[0]).isInstanceOf(RethrownException.class);
            assertThat(x.getSuppressed()).hasLength(0);
        }
    }

    @Test
    public void getNow_throws_wrapped_Exception() {
        MyException x = new MyException("failed");
        MyFuture<?> future = MyFuture.completedExceptionally(x);
        try {
            future.getNow(null);
            fail();
        } catch (CompletionException e) {
            assertEquals(x, e.getCause());
        }
    }

    @Test
    public void acceptEither_doesnt_short_circuit() {
        CompletableFuture<Integer> a = new CompletableFuture<>();
        CompletableFuture<Integer> b = new CompletableFuture<>();
        CompletableFuture<Integer> c = a.thenCombine(b, (w, v) -> w + v);
        b.completeExceptionally(new IllegalStateException());
        assertFalse(c.isCompletedExceptionally());
    }

}
