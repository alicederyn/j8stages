# j8stages
*Experiments with the Java 8 CompletionStage API*

### Why

Playing around with Java 8's [CompletionStage][] API led me to ask a number of questions about its design, which I found I could best answer by attempting an implementation of my own. This is that implementation. It is not off-the-shelf code to use in real applications; rather, it is a thought experiment, and hopefully part of a discussion about ways the JDK could evolve.

Here are the questions, five-whys style.

##### 1. Why does [toCompletableFuture] exist?

* Because [CompletableFuture] needs to call it on every CompletionStage it takes in. Why?
* Because it only actually interoperates with other CompletableFutures. Why?
* Because it does not internally use callbacks to propagate results. Why?
* Because callbacks recurse, and recursion causes stack overflows, as you can easily demonstrate with Guava's [ListenableFuture] API. So why is this a problem?
* Because this internal API is closed to extension.

All this means you *must* constrain yourself to the fluent CompletionStage API on CompletableFutures. If you try to create a new primitive by calling [complete]/[completeExceptionally] from within a [whenComplete] callback, you leave yourself open to stack overflows again. If you try to combine ListenableFutures and CompletableFutures, same problem. And some problems, like providing an eitherOr implementation that short-circuits if *either* input completes exceptionally, simply cannot be solved with CompletableFutures.

So, is there a way to:

1. solve recursion,
2. interoperate with [CompletableFuture],
3. interoperate with [ListenableFuture], and
4. remain open to extension?

(Spoiler: there is.)

#### 2. Why is [whenComplete] surprising?

* Because it discards exceptions if both input stage and action fail. Why is that odd?
* Because whenComplete is the equivalent of try/finally, and that suppresses exceptions rather than discarding them. Why doesn't whenComplete?
* Because modifying an exception is a no-no. Why?
* Because you may not be the only chain of futures consuming the failed stage. Why does that prevent modification?
* Because then the user could receive an exception with suppressed exceptions from multiple concurrent sources, in an arbitrary order, with no indication which ones are relevant to the stage that returned them.

The missing question here seems to be: why are those stages attempting to modify the same exception? Is there no way to copy it?

(Spoiler: there is.)

#### 3. Why does [CompletableFuture] have two volatile fields?

* It has a `result` field and a `stack` field, one for the result of the future (once known), one for registering "dependent actions" (like callbacks, except as mentioned above, not open for extension). Why would they both be set at once?
* Because even after a future completes, if another thread tries to get the result from it while there are still dependent actions to perform, that thread will help clean them up. Why is that problematic?
* Because multiple threads attempting to 'help' by popping items off the stack will cause the cacheline to ping-pong around between multiple cores, slowing progress. Why is it done that way?
* (Speculation here) To avoid the opposite problem: a single thread performing thousands of tasks and blocking the progress of other threads. If the fanout of dependent actions is both wide and deep—imagine a tree with multiple big branches, each of which takes a lot of work to saw down, but which can be tackled by multiple workers at once—then splitting that task up will scale up nicely. Why might that not happen?
* Because many asynchronous problems don't work that way. They branch off into multiple problems, then merge back again. Each task is also very small; any large chunks of work are always farmed off to a thread pool. Opportunities for concurrency are generally a sign that someone is **doing it wrong**.

So, assuming we're happy to sacrifice that possible speed-up, is there a way to:

1. reduce implementation complexity,
2. stop unnecessary cacheline contention, and
3. get rid of that extra field?

(Spoiler: there is.)

### What

This project contains a working (well, not thoroughly tested, so probably not *100%* working...) implementation of [CompletionStage], whimsically called "MyFuture", that answers the questions above. There are three main differences to the [CompletableFuture] implementation in JDK 8: `LocalExecutor`, `ExceptionCloner` and `Callback`.

#### LocalExecutor

The `LocalExecutor` framework transforms recursion into iteration by spinning up a thread-local work queue on demand. MyFuture schedules all its callbacks on this work queue, allowing it to transparently interact not only with [CompletableFuture] and [ListenableFuture], but also with other frameworks we don't yet know about. The `LocalExecutor` framework is not tied to MyFuture, so other frameworks can use it to interoperably solve the recursion problem in future.

LocalExecutor has three entry-points for scheduling work. `runNow` creates a thread-local work queue, runs the given job, and then runs all jobs scheduled on the work queue until it empties. `runSoon` schedules a job on the most recently created work queue for the current thread, unless non exist, in which case it defers to `runNow`. Thus, recursive callbacks can run iteratively by simply scheduling all recursive steps with `runSoon`, while methods that should appear to execute synchronously (e.g. calling `addCallback` on a future whose result is already known) can call `runNow` to ensure all scheduled tasks complete before the method returns.

Finally, in line with the principle of least surprise, we want to ensure that linear code in a single thread that simply creates futures, registers callbacks and sets values does not delay execution to an outer work queue if one exists. (This is safe because such code can only recurse if the user has explicitly coded it recursively, at which point stack overflow errors are not a surprise.) The `multistepExecutor` method returns an [Executor] that functions like `runSoon`, except work queues created before the `Executor` is are ignored. This fixes a surprising edge-case.

All together, these methods let users extend the MyFuture API with their own primitives built from callbacks and [complete]/[completeExceptionally], without fear of stack overflows, even if the callbacks are registered on other types like `ListenableFuture`. All MyFuture's fluent methods are implemented this way.

#### ExceptionCloner

This one is the most obvious of the three: it simply clones exceptions. Specifically, `Throwable` implements `Serializable`, so unless someone has gone out of their way to be awkward, we can simply chain [ObjectOutputStream] and [ObjectInputStream] via a byte array, and clone arbitrary exceptions. (We can even show off and make it a shallow copy by overriding [replaceObject] and [resolveObject], provided the security manager lets us.) This lets us easily solve the problem of [whenComplete] throwing an exception when the stage has already failed: simply close the original exception and attach the new one to it with [addSuppressed].

Exception cloning could also be used to solve the "missing stack trace" problem that multithreaded execution presents us with. Since the original exception holds the stack trace of the worker thread, which we do not wish to lose, we must throw a wrapper exception to keep the stack trace of the receiving thread, which can be just as important in determining the root cause.

1. Futures.get solves this with a checked ExecutionException wrapper; CompletableFutures.getNow uses an unchecked CompletionException. However, that means try-catch can no longer be used to trap and handle specific exception types if the failure has been moved to a worker thread.
2. Some third-party frameworks attempt to use reflection to create a new instance of the original exception class, so try-catch works again. However, there is no guarantee that a "reasonable" constructor exists; and, indeed, there are popular libraries which do not provide any. Further, this approach may break invariants about exception causes.

Using `ExceptionCloner`, we have some new options:
3. Clone the original exception, replacing the clone's cause with the original exception, substituting the stack trace for a fresh trace from a new exception, and removing all suppressed exceptions. Such an invasive clone is hard to get right, however, and like 2, the approach may break invariants about exception causes. It also demands the security manager lets us replace objects during serialization.
4. Clone the original exception, replacing the stack trace with a new stack trace combining the original trace with the stack at the point of rethrowing. This keeps invariants, as well as all the information we need for debugging, but this stack trace is likely to be very confusing. It also demands the security manager lets us replace objects during serialization.
5. Clone the original exception. Create a new RethrownException to capture the new stack trace information, and attach it to the clone as a suppressed exception.

This last option has been used to implement MyFuture.getNow.

#### Callback

Rather than a separate `stack` field that remains set after the future completes, MyFuture has a single `state` field which, while the future is still pending, contains an object of type `Callback`. This encapsulates all the work that needs to be done when the future is completed, and is executed by whichever thread removes it as part of completing the future.

As with CompletableFuture, exceptions are wrapped in a `Failure` object, while the result of a successful stage is stored as-is. User code cannot create `Callback` or `Failure` instances, so they cannot be substituted by mistake. Unlike CompletableFuture, a `null` result is stored as `null`. The initial value of the state is a constant `Callback` instance that does nothing, and the final value is the stage result, so in non-exceptional execution memory allocation is conveniently kept to a minimum.

The `CallbackStack` type stores two callbacks, allowing a chain of callbacks to be built up as more dependent stages are registered. The `CallbackWithExecutor` type lets the MyFuture constructor attach an executor to the callback stack; it overrides the chaining `and` method to ensure the first callback is always a `CallbackWithExecutor` wrapper. Other callback types are specific to the various groups of fluent API methods: `CombiningCallback`, `WhenCompleteCallback`, and so on.

However, there is nothing privileged about the internal `Callback` type. It is provided with no more data than the [whenComplete] method, and all the fluent API methods are implemented by creating a new MyFuture instance and registering one or more callbacks to implement its behaviour. The type itself exists solely to improve the efficiency and readability of the code, by exposing the internal `state` representation, and by assuming the `Callback` implementations do not throw exceptions. The MyFuture API is definitely open to extension.


[CompletionStage]: https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CompletionStage.html
[toCompletableFuture]: https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CompletionStage.html#toCompletableFuture--
[CompletableFuture]: https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CompletableFuture.html
[ListenableFuture]: http://docs.guava-libraries.googlecode.com/git/javadoc/com/google/common/util/concurrent/ListenableFuture.html
[complete]: https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CompletableFuture.html#complete-T-
[completeExceptionally]: https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CompletableFuture.html#completeExceptionally-java.lang.Throwable-
[whenComplete]: https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CompletionStage.html#whenComplete-java.util.function.BiConsumer-
[ObjectInputStream]: https://docs.oracle.com/javase/8/docs/api/java/io/ObjectInputStream.html
[ObjectOutputStream]: https://docs.oracle.com/javase/8/docs/api/java/io/ObjectOutputStream.html
[replaceObject]: https://docs.oracle.com/javase/8/docs/api/java/io/ObjectOutputStream.html#replaceObject-java.lang.Object-
[resolveObject]: https://docs.oracle.com/javase/8/docs/api/java/io/ObjectInputStream.html#resolveObject-java.lang.Object-
[addSuppressed]: http://docs.oracle.com/javase/8/docs/api/java/lang/Throwable.html#addSuppressed-java.lang.Throwable-
[ThreadLocal]: https://docs.oracle.com/javase/8/docs/api/java/lang/ThreadLocal.html
[Executor]: https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/Executor.html

### Conclusions

This repository contains proof-of-concept code demonstrating:

1. a more interoperable, extensible way to solve the recursion problem of dependency chains;
2. a way to clone exceptions so they can be modified by downstream futures; and
3. a smaller, simpler underlying implementation of completable futures.

These all come with engineering trade-offs, of course. 1 is likely to be less efficient (no perf tests done), and may cause subtle bugs that haven't occurred to me; 2 may have unforseen consequences, perhaps with other JDK implementations, or with awkward user types; and 3 not only sacrifices the ability of threads to help execute pending callbacks, but may have other costs I simply haven't thought of. In general, consider this library a thought experiment, or part of a discussion: not off-the-shelf code to use in real applications.
