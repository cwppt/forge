package forge.view;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Created by maustin on 08/02/2018.
 */
public class TimeLimitedCodeBlock {

    public static void runWithTimeout(final Runnable runnable, long timeout, TimeUnit timeUnit) throws Exception {
        runWithTimeout(() -> {
            runnable.run();
            return null;
        }, timeout, timeUnit);
    }

    public static <T> T runWithTimeout(Callable<T> callable, long timeout, TimeUnit timeUnit) throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final Future<T> future = executor.submit(callable);
        executor.shutdown(); // This does not cancel the already-scheduled task.
        Throwable failure = null;
        try {
            return future.get(timeout, timeUnit);
        }
        catch (TimeoutException e) {
            failure = e;
            future.cancel(true);
            throw e;
        }
        catch (InterruptedException e) {
            failure = e;
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        }
        catch (ExecutionException e) {
            failure = e.getCause();
            //unwrap the root cause
            Throwable t = e.getCause();
            if (t instanceof Error) {
                throw (Error) t;
            } else if (t instanceof Exception) {
                throw (Exception) t;
            } else {
                throw new IllegalStateException(t);
            }
        } finally {
            // Future completion/cancellation is not proof that its thread has exited.
            // A failed simulation caller must terminate its isolated process if this fails.
            boolean interrupted = Thread.interrupted();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            try {
                while (!executor.isTerminated()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        throw new UnstoppedTaskException(failure);
                    }
                    try { executor.awaitTermination(remaining, TimeUnit.NANOSECONDS); }
                    catch (InterruptedException e) { interrupted = true; future.cancel(true); }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    public static final class UnstoppedTaskException extends Exception {
        UnstoppedTaskException(Throwable cause) { super("Simulation task remained alive after cancellation; process isolation required", cause); }
    }

}
