package org.inferred.cjp39.j8stages;

import static org.inferred.cjp39.j8stages.LocalExecutor.runNow;
import static org.inferred.cjp39.j8stages.LocalExecutor.runSoon;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Uninterruptibles;

public class MyFuture<T> implements CompletionStage<T> {

    public static <T> MyFuture<T> completed(T value) {
        return new MyFuture<>(value);
    }

    public static <T> MyFuture<T> cancelled() {
        return new MyFuture<>(new CancellationException());
    }

    public static <T> MyFuture<T> completedExceptionally(Throwable t) {
        return new MyFuture<>(withFailure(t));
    }

    /**
     * Returns a future maintaining the same completion properties as
     * {@link listenableFuture}. Cancelling the returned future will cancel the
     * input future; however, setting its value via {@link #complete(Object)} or
     * {@link #completeExceptionally(Throwable)} will not modify the input
     * future.
     */
    public static <T> MyFuture<T> from(ListenableFuture<T> listenableFuture) {
        if (listenableFuture instanceof CompletionStage) {
            /*
             * Converting from a CompletionStage is much more efficient, as no
             * exception needs to be thrown and caught. However, until Guava
             * catches up with Java 8, we have to assume that nobody would
             * create a subclass of ListenableFuture<T> and CompletionStage<U>
             * where U != V.
             */
            @SuppressWarnings("unchecked")
            CompletionStage<T> stage = (CompletionStage<T>) listenableFuture;
            return from(stage);
        }
        MyFuture<T> future = new MyFuture<>();
        listenableFuture.addListener(() -> {
            try {
                T result = Uninterruptibles.getUninterruptibly(listenableFuture);
                future.complete(result);
            } catch (RuntimeException | ExecutionException | Error e) {
                future.completeExceptionally(e.getCause());
            }
        } , Runnable::run);
        future.addInternalCallback(state -> {
            if (state instanceof Failure) {
                if (((Failure) state).cause instanceof CancellationException) {
                    listenableFuture.cancel(false);
                }
            }
        });
        return future;
    }

    /**
     * Returns a future maintaining the same completion properties as
     * {@code stage}. If {@code stage} is already a {@code MyFuture}, it will be
     * returned directly. If it is a {@link Future}, it will be cancelled if the
     * returned future is cancelled. If it is a {@link CompletableFuture}, it
     * will also be update if the returned future is completed.
     */
    public static <T> MyFuture<T> from(CompletionStage<T> stage) {
        if (stage instanceof MyFuture) {
            return (MyFuture<T>) stage;
        } else if (stage instanceof CompletableFuture) {
            return from((CompletableFuture<T>) stage);
        }
        MyFuture<T> future = new MyFuture<>();
        future.completeFrom(stage);
        if (stage instanceof Future) {
            future.addInternalCallback(state -> {
                if (state instanceof Failure) {
                    if (((Failure) state).cause instanceof CancellationException) {
                        ((Future<?>) stage).cancel(false);
                    }
                }
            });
        }
        return future;
    }

    /**
     * Returns a future maintaining the same completion properties as
     * {@code stage}. {@code stage} will be update if the returned future is
     * completed or cancelled.
     */
    public static <T> MyFuture<T> from(CompletableFuture<T> stage) {
        MyFuture<T> future = new MyFuture<>();
        future.completeFrom(stage);
        future.addInternalCallback(state -> {
            if (state instanceof Failure) {
                stage.completeExceptionally(((Failure) state).cause);
            } else {
                @SuppressWarnings("unchecked")
                T value = (T) state;
                stage.complete(value);
            }
        });
        return future;
    }

    /**
     * Returns a new CompletionStage that, when either {@code first} or
     * {@code second} completes, completes with the corresponding result (or
     * exception).
     */
    public static <T> MyFuture<T> eitherOf(CompletionStage<? extends T> first, CompletionStage<? extends T> second) {
        MyFuture<T> result = new MyFuture<>();
        result.completeFrom(first);
        result.completeFrom(second);
        return result;
    }

    /**
     * Returns a new CompletionStage that, when any input stage completes,
     * completes with the corresponding result (or exception).
     */
    @SafeVarargs
    public static <T> MyFuture<T> anyOf(
            CompletionStage<? extends T> first,
            CompletionStage<? extends T> second,
            CompletionStage<? extends T>... others) {
        MyFuture<T> result = eitherOf(first, second);
        if (others != null) {
            for (CompletionStage<? extends T> other : others) {
                result.completeFrom(other);
            }
        }
        return result;
    }

    /**
     * Returns a new CompletionStage that, when any input stage completes,
     * completes with the corresponding result (or exception).
     *
     * @throws IllegalArgumentException
     *             if {@code stages} is empty
     */
    public static <T> MyFuture<T> anyOf(Iterable<CompletionStage<? extends T>> stages) {
        MyFuture<T> result = new MyFuture<>();
        boolean anyStage = false;
        for (CompletionStage<? extends T> stage : stages) {
            result.completeFrom(stage);
            anyStage = true;
        }
        checkArgument(anyStage, "Must provide at least one input stage");
        return result;
    }

    @VisibleForTesting
    static class RethrownException extends Exception {
        private static final long serialVersionUID = 1296586904077392396L;

        RethrownException(String message) {
            super(message);
        }
    }

    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<MyFuture, Object> STATE =
            AtomicReferenceFieldUpdater.newUpdater(MyFuture.class, Object.class, "state");

    /**
     * Stores the current state, encoded as follows:
     * <ul>
     * <li>If the stage is incomplete: a {@link Callback} instance
     * <li>If the stage completed exceptionally: a {@link Failure} instance
     * <li>Otherwise, the result of the stage (may be null).
     * </ul>
     */
    private volatile Object state;

    public MyFuture() {
        Optional<Executor> executor = LocalExecutor.multistepExecutor();
        state = executor.<Object>map(NoCallbackWithExecutor::new).orElse(NOTHING);
    }

    private MyFuture(Object state) {
        this.state = state;
    }

    public ListenableFuture<T> toListenableFuture() {
        return new ListenableFutureBridge();
    }

    private static final CancellationAction<CancellationException> CANCELLATION_EXCEPTION =
            (cause) -> (CancellationException) new CancellationException().initCause(cause);

    private static CompletionException rethrow(Throwable t) {
        if (t instanceof RuntimeException) {
            RuntimeException e = (RuntimeException) t;
            RuntimeException clone = ExceptionCloner.clone(e);
            clone.addSuppressed(new RethrownException("Completion exception rethrown"));
            throw clone;
        } else if (t instanceof Error) {
            Error e = (Error) t;
            Error clone = ExceptionCloner.clone(e);
            clone.addSuppressed(new RethrownException("Completion error rethrown"));
            throw clone;
        }
        return new CompletionException(t);
    }

    /**
     * Returns the result value (or throws any encountered exception) if
     * completed, else returns the given valueIfAbsent.
     *
     * <p>
     * If the future completed exceptionally with a runtime exception or error,
     * a copy will be thrown; if with a checked exception, it will be wrapped in
     * a {@link CompletionException}.
     *
     * @param valueIfAbsent
     *            the value to return if not completed
     * @return the result value, if completed, else the given valueIfAbsent
     * @throws CancellationException
     *             if the computation was cancelled
     * @throws RuntimeException
     *             if this future completed exceptionally with a
     *             RuntimeException
     * @throws CompletionException
     *             if this future completed exceptionally with a checked
     *             exception
     * @throws Error
     *             if this future completed exceptionally with an Error
     */
    public T getNow(T valueIfAbsent) {
        return internalGet(MyFuture::rethrow, MyFuture::rethrow, null, () -> valueIfAbsent);
    }

    public T get() throws InterruptedException {
        return internalGet(MyFuture::rethrow, MyFuture::rethrow, CountDownLatch::await, null);
    }

    public T getUninterruptibly() {
        return internalGet(MyFuture::rethrow, MyFuture::rethrow, Uninterruptibles::awaitUninterruptibly, null);
    }

    public T tryGet(long timeout, TimeUnit unit) throws InterruptedException, TimeoutException {
        return internalGet(MyFuture::rethrow, MyFuture::rethrow, latch -> latch.await(timeout, unit), () -> {
            throw new TimeoutException();
        });
    }

    public T tryGetUninterruptibly(long timeout, TimeUnit unit) throws TimeoutException {
        return internalGet(CompletionException::new, CANCELLATION_EXCEPTION, latch -> {
            Uninterruptibles.awaitUninterruptibly(latch, timeout, unit);
        } , () -> {
            throw new TimeoutException();
        });
    }

    public boolean isCancelled() {
        Object currentState = state;
        if (currentState instanceof Failure) {
            Failure failure = (Failure) currentState;
            return (failure.cause instanceof CancellationException);
        }
        return false;
    }

    public boolean isCompletedExceptionally() {
        return (state instanceof Failure);
    }

    public boolean isDone() {
        return !(state instanceof Callback);
    }

    /**
     * If not already completed, sets the value returned by {@link #get()} and
     * related methods to the given value.
     *
     * <p>
     * Synchronous callbacks and dependent stages will be executed on this
     * thread, but may not be scheduled immediately, to avoid deep recursion.
     *
     * @param value
     *            the result value
     * @return {@code true} if this invocation caused this future to transition
     *         to a completed state, else {@code false}
     * @see LocalExecutor
     */
    public boolean complete(T value) {
        return completeInternal(value);
    }

    /**
     * If not already completed, causes invocations of {@link #get()} and
     * related methods to throw the given exception.
     *
     * <p>
     * Synchronous callbacks and dependent stages will be executed on this
     * thread, but may not be scheduled immediately, to avoid deep recursion.
     *
     * @param ex
     *            the exception
     * @return {@code true} if this invocation caused this future to transition
     *         to a completed state, else {@code false}
     * @see LocalExecutor
     */
    public boolean completeExceptionally(Throwable t) {
        return completeInternal(withFailure(checkNotNull(t)));
    }

    /**
     * If not already completed, completes this CompletableFuture with a
     * {@link CancellationException}.
     *
     * <p>
     * Synchronous callbacks and dependent stages will be executed on this
     * thread, but may not be scheduled immediately, to avoid deep recursion.
     *
     * @return {@code true} if this task is now cancelled
     * @see LocalExecutor
     */
    public boolean cancel() {
        return completeInternal(withFailure(new CancellationException())) || isCancelled();
    }

    public void addCallback(Runnable callback, Executor e) {
        addInternalCallback(wrap(callback, e));
    }

    public void addCallback(FutureCallback<T> callback, Executor e) {
        addInternalCallback(wrap(callback, e));
    }

    @Override
    public <U> MyFuture<U> thenApply(Function<? super T, ? extends U> fn) {
        return thenApplyAsync(fn, Runnable::run);
    }

    @Override
    public <U> MyFuture<U> thenApplyAsync(Function<? super T, ? extends U> fn) {
        return thenApplyAsync(fn, ForkJoinPool.commonPool());
    }

    @Override
    public <U> MyFuture<U> thenApplyAsync(Function<? super T, ? extends U> fn, Executor executor) {
        return this.addInternalCallback(new TransformingCallback<T, U>(fn, executor)).getFuture();
    }

    @Override
    public MyFuture<Void> thenAccept(Consumer<? super T> action) {
        return thenApply(input -> {
            action.accept(input);
            return null;
        });
    }

    @Override
    public MyFuture<Void> thenAcceptAsync(Consumer<? super T> action) {
        return thenApplyAsync(input -> {
            action.accept(input);
            return null;
        });
    }

    @Override
    public MyFuture<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) {
        return thenApplyAsync(input -> {
            action.accept(input);
            return null;
        } , executor);
    }

    @Override
    public MyFuture<Void> thenRun(Runnable action) {
        return thenApply(input -> {
            action.run();
            return null;
        });
    }

    @Override
    public MyFuture<Void> thenRunAsync(Runnable action) {
        return thenApplyAsync(input -> {
            action.run();
            return null;
        });
    }

    @Override
    public MyFuture<Void> thenRunAsync(Runnable action, Executor executor) {
        return thenApplyAsync(input -> {
            action.run();
            return null;
        } , executor);
    }

    @Override
    public <U, V> MyFuture<V> thenCombine(
            CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> fn) {
        return thenCombineAsync(other, fn, Runnable::run);
    }

    @Override
    public <U, V> MyFuture<V> thenCombineAsync(
            CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> fn) {
        return thenCombineAsync(other, fn, ForkJoinPool.commonPool());
    }

    @Override
    public <U, V> MyFuture<V> thenCombineAsync(
            CompletionStage<? extends U> other,
            BiFunction<? super T, ? super U, ? extends V> fn,
            Executor executor) {
        return addInternalCallback(new CombiningCallback<T, U, V>(other, fn, executor)).getFuture();
    }

    @Override
    public <U> MyFuture<Void> thenAcceptBoth(
            CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action) {
        return thenAcceptBothAsync(other, action, Runnable::run);
    }

    @Override
    public <U> MyFuture<Void> thenAcceptBothAsync(
            CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action) {
        return thenAcceptBothAsync(other, action, ForkJoinPool.commonPool());
    }

    @Override
    public <U> MyFuture<Void> thenAcceptBothAsync(
            CompletionStage<? extends U> other,
            BiConsumer<? super T, ? super U> action,
            Executor executor) {
        return thenCombineAsync(other, (t, u) -> {
            action.accept(t, u);
            return null;
        } , executor);
    }

    @Override
    public MyFuture<Void> runAfterBoth(CompletionStage<?> other, Runnable action) {
        return runAfterBothAsync(other, action, Runnable::run);
    }

    @Override
    public MyFuture<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action) {
        return runAfterBothAsync(other, action, ForkJoinPool.commonPool());
    }

    @Override
    public MyFuture<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action, Executor executor) {
        return thenCombineAsync(other, (t, u) -> {
            action.run();
            return null;
        } , executor);
    }

    /**
     * Returns a new future that, when either this future or {@code other}
     * completes, completes with the corresponding result (or exception).
     */
    public MyFuture<T> or(CompletionStage<? extends T> other) {
        return eitherOf(this, other);
    }

    @Override
    public <U> MyFuture<U> applyToEither(CompletionStage<? extends T> other, Function<? super T, U> fn) {
        return or(other).thenApply(fn);
    }

    @Override
    public <U> MyFuture<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> fn) {
        return or(other).thenApplyAsync(fn);
    }

    @Override
    public <U> MyFuture<U> applyToEitherAsync(
            CompletionStage<? extends T> other,
            Function<? super T, U> fn,
            Executor executor) {
        return or(other).thenApplyAsync(fn, executor);
    }

    @Override
    public MyFuture<Void> acceptEither(CompletionStage<? extends T> other, Consumer<? super T> action) {
        return or(other).thenAccept(action);
    }

    @Override
    public MyFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action) {
        return or(other).thenAcceptAsync(action);
    }

    @Override
    public MyFuture<Void> acceptEitherAsync(
            CompletionStage<? extends T> other,
            Consumer<? super T> action,
            Executor executor) {
        return or(other).thenAcceptAsync(action, executor);
    }

    @Override
    public MyFuture<Void> runAfterEither(CompletionStage<?> other, Runnable action) {
        return eitherOf(this, other).thenRun(action);
    }

    @Override
    public MyFuture<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action) {
        return eitherOf(this, other).thenRunAsync(action);
    }

    @Override
    public MyFuture<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action, Executor executor) {
        return eitherOf(this, other).thenRunAsync(action, executor);
    }

    @Override
    public <U> MyFuture<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn) {
        return thenComposeAsync(fn, Runnable::run);
    }

    @Override
    public <U> MyFuture<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn) {
        return thenComposeAsync(fn, ForkJoinPool.commonPool());
    }

    @Override
    public <U> MyFuture<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn, Executor executor) {
        return addInternalCallback(new ComposingCallback<>(fn, executor)).getFuture();
    }

    @Override
    public MyFuture<T> exceptionally(Function<Throwable, ? extends T> fn) {
        return handleAsync((t, x) -> (x != null) ? fn.apply(x) : t, Runnable::run);
    }

    /**
     * Returns a new future with the same result or exception as this future,
     * that executes the given action when this future completes.
     *
     * <p>
     * When this future is complete, the given action is invoked with the result
     * (or {@code null} if none) and the exception (or {@code null} if none) of
     * this future as arguments. The returned future is completed when the
     * action returns. If the supplied action itself encounters an exception,
     * then the returned future exceptionally completes with this exception
     * unless this future also completed exceptionally, in which case it is
     * added as a suppressed exception.
     *
     * @param action
     *            the action to perform
     * @return the new CompletionStage
     */
    @Override
    public MyFuture<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
        return whenCompleteAsync(action, Runnable::run);
    }

    /**
     * Returns a new future with the same result or exception as this future,
     * that executes the given action using this future's default asynchronous
     * execution facility when this future completes.
     *
     * <p>
     * When this future is complete, the given action is invoked with the result
     * (or {@code null} if none) and the exception (or {@code null} if none) of
     * this future as arguments. The returned future is completed when the
     * action returns. If the supplied action itself encounters an exception,
     * then the returned future exceptionally completes with this exception
     * unless this future also completed exceptionally, in which case it is
     * added as a suppressed exception.
     *
     * @param action
     *            the action to perform
     * @return the new future
     */
    @Override
    public MyFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action) {
        return whenCompleteAsync(action, ForkJoinPool.commonPool());
    }

    /**
     * Returns a new future with the same result or exception as this future,
     * that executes the given action using the supplied Executor when this
     * future completes.
     *
     * <p>
     * When this future is complete, the given action is invoked with the result
     * (or {@code null} if none) and the exception (or {@code null} if none) of
     * this future as arguments. The returned future is completed when the
     * action returns. If the supplied action itself encounters an exception,
     * then the returned future exceptionally completes with this exception
     * unless this future also completed exceptionally, in which case it is
     * added as a suppressed exception.
     *
     * @param action
     *            the action to perform
     * @param executor
     *            the executor to use for asynchronous execution
     * @return the new future
     */
    @Override
    public MyFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action, Executor executor) {
        return addInternalCallback(new WhenCompleteCallback<T>(action, executor)).getFuture();
    }

    @Override
    public <U> MyFuture<U> handle(BiFunction<? super T, Throwable, ? extends U> fn) {
        return handleAsync(fn, Runnable::run);
    }

    @Override
    public <U> MyFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn) {
        return handleAsync(fn, ForkJoinPool.commonPool());
    }

    @Override
    public <U> MyFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn, Executor executor) {
        return addInternalCallback(new HandlingCallback<T, U>(fn, executor)).getFuture();
    }

    /**
     * Returns a {@link CompletableFuture} maintaining the same completion
     * properties as this future. Completing or cancelling the result will
     * complete this future also. (Note however that this is not done
     * atomically; if two threads race, they may leave the result in a different
     * state than this future.)
     */
    @Override
    public CompletableFuture<T> toCompletableFuture() {
        CompletableFuture<T> future = addInternalCallback(new CompletableFutureCallback<T>()).getFuture();
        completeFrom(future);
        return future;
    }

    private final class ListenableFutureBridge implements ListenableFuture<T> {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return MyFuture.this.cancel();
        }

        @Override
        public boolean isCancelled() {
            return MyFuture.this.isCancelled();
        }

        @Override
        public boolean isDone() {
            return MyFuture.this.isDone();
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            return MyFuture.this
                    .internalGet(ExecutionException::new, ExecutionException::new, CountDownLatch::await, null);
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return MyFuture.this.internalGet(
                    ExecutionException::new,
                    ExecutionException::new,
                    latch -> latch.await(timeout, unit),
                    () -> {
                        throw new TimeoutException();
                    });
        }

        @Override
        public void addListener(Runnable listener, Executor executor) {
            addInternalCallback(MyFuture.wrap(listener, executor));
        }
    }

    /** If the state is a Callback instance, the stage is not yet complete. */
    private static interface Callback {
        void onComplete(Object state);

        default Callback and(Callback also) {
            return new CallbackStack(also, this);
        }

        default void onCompleteAsync(Object state) {
            runSoon(() -> this.onComplete(state));
        }
    }

    private static Callback wrap(Runnable callback, Executor e) {
        return state -> {
            try {
                e.execute(callback);
            } catch (RuntimeException f) {}
        };
    }

    private static <T> Callback wrap(FutureCallback<T> callback, Executor e) {
        return state -> {
            try {
                if (state instanceof Failure) {
                    Throwable cause = ((Failure) state).cause;
                    e.execute(() -> callback.onFailure(cause == null ? new CancellationException() : cause));
                } else {
                    @SuppressWarnings("unchecked")
                    T result = (T) state;
                    e.execute(() -> callback.onSuccess(result));
                }
            } catch (RuntimeException f) {}
        };
    }

    private abstract static class CallbackWithFuture<T> implements Callback {
        protected final MyFuture<T> future = new MyFuture<T>();

        public MyFuture<T> getFuture() {
            return future;
        }
    }

    private static class TransformingCallback<T, U> extends CallbackWithFuture<U> {
        final Function<? super T, ? extends U> transform;
        final Executor executor;

        TransformingCallback(Function<? super T, ? extends U> transform, Executor executor) {
            this.transform = transform;
            this.executor = executor;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void onComplete(Object state) {
            if (state instanceof Failure) {
                future.completeInternal(state);
            } else {
                executor.execute(() -> {
                    try {
                        future.completeInternal(transform.apply((T) state));
                    } catch (RuntimeException | Error e) {
                        future.completeInternal(withFailure(e));
                    }
                });
            }
        }
    }

    private static final class CombiningCallback<T, U, V> extends CallbackWithFuture<V> {
        private final Executor executor;
        private final BiFunction<? super T, ? super U, ? extends V> fn;
        private final CompletionStage<? extends U> other;

        CombiningCallback(
                CompletionStage<? extends U> other,
                BiFunction<? super T, ? super U, ? extends V> fn,
                Executor executor) {
            this.executor = executor;
            this.fn = fn;
            this.other = other;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void onComplete(Object state) {
            if (state instanceof Failure) {
                future.completeInternal(state);
            } else {
                other.whenCompleteAsync((input2, t) -> {
                    if (t != null) {
                        future.completeInternal(withFailure(t));
                    } else {
                        executor.execute(() -> {
                            try {
                                V output = fn.apply((T) state, input2);
                                future.completeInternal(output);
                            } catch (RuntimeException | Error e) {
                                future.completeInternal(withFailure(e));
                            }
                        });
                    }
                } , Runnable::run);
            }
        }
    }

    private static final class ComposingCallback<T, U> extends CallbackWithFuture<U> {
        private final Function<? super T, ? extends CompletionStage<U>> fn;
        private final Executor executor;

        ComposingCallback(Function<? super T, ? extends CompletionStage<U>> fn, Executor executor) {
            this.fn = fn;
            this.executor = executor;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void onComplete(Object state) {
            if (state instanceof Failure) {
                future.completeInternal(state);
            } else {
                T t = (T) state;
                executor.execute(() -> {
                    try {
                        CompletionStage<U> nextStage = fn.apply(t);
                        if (nextStage == null) {
                            future.completeExceptionally(new NullPointerException("apply() returned null: " + fn));
                        } else {
                            future.completeFrom(nextStage);
                        }
                    } catch (RuntimeException | Error x) {
                        future.completeInternal(withFailure(x, state));
                    }
                });
            }
        }
    }

    private void completeFrom(CompletionStage<? extends T> stage) {
        if (stage instanceof MyFuture) {
            ((MyFuture<? extends T>) stage).addInternalCallback(this::completeInternal);
        } else {
            stage.whenCompleteAsync((u, x) -> {
                completeInternal((x != null) ? withFailure(x) : u);
            } , Runnable::run);
        }
    }

    private static class WhenCompleteCallback<T> extends CallbackWithFuture<T> {
        private final BiConsumer<? super T, ? super Throwable> action;
        final Executor executor;

        WhenCompleteCallback(BiConsumer<? super T, ? super Throwable> action, Executor executor) {
            this.action = action;
            this.executor = executor;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void onComplete(Object state) {
            executor.execute(() -> {
                T t;
                Throwable x;
                if (state instanceof Failure) {
                    t = null;
                    x = ((Failure) state).cause;
                } else {
                    t = (T) state;
                    x = null;
                }
                try {
                    action.accept(t, x);
                    future.completeInternal(state);
                } catch (RuntimeException | Error e) {
                    if (x == null) {
                        future.completeInternal(withFailure(e));
                    } else if (e == x) {
                        future.completeInternal(state);
                    } else {
                        Throwable clone = ExceptionCloner.clone(x);
                        clone.addSuppressed(e);
                        future.completeInternal(withFailure(clone));
                    }
                }
            });
        }
    }

    private static class HandlingCallback<T, U> extends CallbackWithFuture<U> {
        private final BiFunction<? super T, Throwable, ? extends U> fn;
        final Executor executor;

        HandlingCallback(BiFunction<? super T, Throwable, ? extends U> fn, Executor executor) {
            this.fn = fn;
            this.executor = executor;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void onComplete(Object state) {
            executor.execute(() -> {
                T t;
                Throwable x;
                if (state instanceof Failure) {
                    t = null;
                    x = ((Failure) state).cause;
                } else {
                    t = (T) state;
                    x = null;
                }
                try {
                    future.completeInternal(fn.apply(t, x));
                } catch (RuntimeException | Error e) {
                    future.completeInternal(withFailure(e, state));
                }
            });
        }
    }

    private static class CompletableFutureCallback<T> implements Callback {
        private final CompletableFuture<T> future = new CompletableFuture<>();

        @Override
        @SuppressWarnings("unchecked")
        public void onComplete(Object state) {
            if (state instanceof Failure) {
                future.completeExceptionally(((Failure) state).cause);
            } else {
                future.complete((T) state);
            }
        }

        public CompletableFuture<T> getFuture() {
            return future;
        }
    }

    /**
     * A callback that executes a pair of callbacks.
     */
    private static class CallbackStack implements Callback {
        final Callback action;
        final Callback next;

        CallbackStack(Callback action, Callback next) {
            this.action = checkNotNull(action);
            this.next = checkNotNull(next);
            assert!(action instanceof CallbackStack);
            assert next != NOTHING;
        }

        @Override
        public void onComplete(Object state) {
            action.onComplete(state);
            next.onCompleteAsync(state);
        }
    }

    /**
     * Callback that delays asynchronous execution, of {@code action} as well as
     * any subsequently-chained actions, to {@code executor}.
     */
    private static class CallbackWithExecutor implements Callback {
        final Callback action;
        final Executor executor;

        CallbackWithExecutor(Callback action, Executor executor) {
            this.action = action;
            this.executor = executor;
        }

        @Override
        public void onComplete(Object state) {
            action.onComplete(state);
        }

        @Override
        public void onCompleteAsync(Object state) {
            executor.execute(() -> action.onCompleteAsync(state));
        }

        @Override
        public Callback and(Callback also) {
            return new CallbackWithExecutor(action.and(also), executor);
        }
    }

    /**
     * Callback that does nothing, but if actions are chained to it, delays
     * their asynchronous execution to {@code executor}.
     */
    private static class NoCallbackWithExecutor implements Callback {

        final Executor executor;

        NoCallbackWithExecutor(Executor executor) {
            this.executor = executor;
        }

        @Override
        public void onComplete(Object state) {}

        @Override
        public void onCompleteAsync(Object state) {}

        @Override
        public Callback and(Callback also) {
            return new CallbackWithExecutor(also, executor);
        }

    }

    /**
     * If the state is a Failure instance, the stage completed exceptionally.
     */
    private static class Failure {
        private final Throwable cause;

        Failure(Throwable t) {
            this.cause = t;
        }
    }

    private static Failure withFailure(Throwable t) {
        return new Failure(t);
    }

    private static Failure withFailure(Throwable t, Object state) {
        if (state instanceof Failure && ((Failure) state).cause == t) {
            return (Failure) state;
        } else {
            return new Failure(t);
        }
    }

    private static final Callback NOTHING = new Callback() {

        @Override
        public void onComplete(Object state) {}

        @Override
        public void onCompleteAsync(Object state) {}

        @Override
        public Callback and(Callback also) {
            return also;
        }
    };

    /*
     * Completing this future may cause a cascade of other callbacks. We need to
     * ensure we do not get a StackOverflowError by executing them all
     * recursively. However, we also want to ensure all callbacks are triggered
     * "soon enough", in case the user has run a synchronous action, and is
     * expecting results. To do that, we delay execution of the callback and
     * only run it at the top level of this method, keeping the stack trace
     * small. If the callback happens after this method returns, we still want
     * to run it with the minimal amount of stack, or we may still contribute to
     * a StackOverflowError; but we do not want to delay indefinitely, or we
     * risk breaking the apparent immediacy of an outer stage. We therefore
     * piggyback on the most recently started executor on this thread.
     * LocalExecutor provides this very specific set of behaviors. The net
     * result is our synchronous methods always return a completed future if all
     * the data is available, without StackOverflowErrors.
     */
    private <C extends Callback> C addInternalCallback(C callback) {
        runNow(() -> {
            Object currentState = null;
            Callback newState;
            do {
                currentState = state;
                if (currentState instanceof Callback) {
                    newState = ((Callback) currentState).and(callback);
                } else {
                    callback.onComplete(currentState);
                    newState = null;
                }
            } while (newState != null && !compareAndSwapState(currentState, newState));
        });
        return callback;
    }

    private boolean completeInternal(Object finalState) {
        Object currentState = null;
        do {
            currentState = state;
            if (!(currentState instanceof Callback)) {
                return false;
            }
        } while (!compareAndSwapState(state, finalState));
        ((Callback) currentState).onCompleteAsync(finalState);
        return true;
    }

    private boolean compareAndSwapState(Object expected, Object value) {
        return STATE.compareAndSet(this, expected, value);
    }

    private interface FailureAction<X extends Exception> {
        X createException(Throwable cause);
    }

    private interface CancellationAction<X extends Exception> {
        X createException(Throwable cause);
    }

    private interface BlockingAction<X extends Exception> {
        void block(CountDownLatch latch) throws X;
    }

    private interface IncompleteAction<T, X extends Exception> {
        T getFallback() throws X;
    }

    private <W extends Exception, X extends Exception, Y extends Exception, Z extends Exception> T internalGet(
            FailureAction<X> failureAction,
            CancellationAction<W> cancellationAction,
            BlockingAction<Y> blockingAction,
            IncompleteAction<T, Z> fallback) throws W, X, Y, Z {
        checkArgument(blockingAction != null || fallback != null);
        Object currentState = this.state;
        if (currentState instanceof Callback && blockingAction != null) {
            CountDownLatch latch = new CountDownLatch(1);
            boolean addedCallback;
            do {
                Callback newState = ((Callback) currentState).and(state -> latch.countDown());
                addedCallback = compareAndSwapState(currentState, newState);
                if (addedCallback) {
                    blockingAction.block(latch);
                }
                currentState = this.state;
            } while (currentState instanceof Callback && !addedCallback);
        }
        if (currentState instanceof Callback) {
            checkArgument(fallback != null, "Blocking action did not wait for latch, and no fallback provided");
            return fallback.getFallback();
        } else if (currentState instanceof Failure) {
            Throwable cause = ((Failure) currentState).cause;
            if (cause instanceof CancellationException) {
                throw cancellationAction.createException(cause);
            } else {
                throw failureAction.createException(cause);
            }
        } else {
            @SuppressWarnings("unchecked")
            T result = (T) currentState;
            return result;
        }
    }

}
