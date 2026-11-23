import dev.radminlan.ui.RadminBatchOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Checks write ordering and partial failures without touching the Radmin service. */
public final class BatchOperationsRegression {
    public static void main(String[] args) {
        check();
    }

    public static void check() {
        checkSequentialRequests();
        checkPartialFailures();
        checkEmptyAndInvalidInput();
        checkStableResult();
        checkFatalErrors();
        System.out.println("BATCH_OPERATIONS_REGRESSION_OK sequential=true partialFailures=true immutable=true");
    }

    private static void checkSequentialRequests() {
        List<String> targets = new ArrayList<>(List.of("first", "second", "third"));
        List<String> invoked = new ArrayList<>();
        CompletableFuture<Boolean> first = new CompletableFuture<>();
        CompletableFuture<Boolean> second = new CompletableFuture<>();
        var batch = RadminBatchOperations.run(targets, target -> {
            invoked.add(target);
            return switch (target) {
                case "first" -> first;
                case "second" -> second;
                default -> CompletableFuture.completedFuture(true);
            };
        });
        equal(invoked, List.of("first"), "only the first request starts before it completes");
        require(!batch.isDone(), "the batch waits for the first request");
        targets.clear();
        first.complete(true);
        equal(invoked, List.of("first", "second"), "the next request starts after the first completes");
        require(!batch.isDone(), "the batch waits for the second request");
        second.complete(false);
        equal(invoked, List.of("first", "second", "third"), "a rejected request does not stop remaining targets");
        var result = batch.join();
        equal(result.succeeded(), List.of("first", "third"), "only confirmed requests are successful");
        equal(result.failed(), List.of("second"), "the rejected request remains failed");
        require(!result.allSucceeded(), "partial completion is not reported as full success");
    }

    private static void checkPartialFailures() {
        List<String> targets = List.of("ok", "rejected", "null-result", "async-error", "sync-error", "null-future", "last");
        List<String> invoked = new ArrayList<>();
        var result = RadminBatchOperations.run(targets, target -> {
            invoked.add(target);
            return switch (target) {
                case "ok", "last" -> CompletableFuture.completedFuture(true);
                case "rejected" -> CompletableFuture.completedFuture(false);
                case "null-result" -> CompletableFuture.completedFuture(null);
                case "async-error" -> CompletableFuture.failedFuture(new IllegalStateException("Service unavailable"));
                case "sync-error" -> throw new IllegalArgumentException("Invalid request");
                default -> null;
            };
        }).join();
        equal(invoked, targets, "ordinary failures still attempt every target in order");
        equal(result.succeeded(), List.of("ok", "last"), "only true results belong in the successful list");
        equal(result.failed(), List.of("rejected", "null-result", "async-error", "sync-error", "null-future"),
                "false, null and exceptional requests are retained as failures");
        require(!result.allSucceeded(), "mixed failures cannot report success");

        var success = RadminBatchOperations.run(List.of("a", "b"), target -> CompletableFuture.completedFuture(true)).join();
        require(success.allSucceeded() && success.failed().isEmpty(), "a completely successful batch reports success");
        var failure = RadminBatchOperations.run(List.of("a", "b"), target -> CompletableFuture.completedFuture(false)).join();
        require(!failure.allSucceeded() && failure.succeeded().isEmpty(), "a completely rejected batch reports failure");
    }

    private static void checkEmptyAndInvalidInput() {
        var empty = RadminBatchOperations.<String>run(List.of(), target -> {
            throw new AssertionError("An empty batch must not issue a write");
        }).join();
        require(empty.succeeded().isEmpty() && empty.failed().isEmpty() && empty.allSucceeded(),
                "an empty batch completes without issuing a request");
        try {
            RadminBatchOperations.run(null, target -> CompletableFuture.completedFuture(true));
            throw new AssertionError("A missing target list must be rejected");
        } catch (NullPointerException expected) {
        }
        try {
            RadminBatchOperations.run(List.of("a"), null);
            throw new AssertionError("A missing action must be rejected");
        } catch (NullPointerException expected) {
        }
    }

    private static void checkStableResult() {
        List<String> successes = new ArrayList<>(List.of("a"));
        List<String> failures = new ArrayList<>(List.of("b"));
        var result = new RadminBatchOperations.Result<>(successes, failures);
        successes.clear();
        failures.clear();
        equal(result.succeeded(), List.of("a"), "successful results are copied before publication");
        equal(result.failed(), List.of("b"), "failed results are copied before publication");
        try {
            result.succeeded().clear();
            throw new AssertionError("Published successful results must be immutable");
        } catch (UnsupportedOperationException expected) {
        }
        try {
            result.failed().clear();
            throw new AssertionError("Published failed results must be immutable");
        } catch (UnsupportedOperationException expected) {
        }
    }

    private static void checkFatalErrors() {
        for (boolean asynchronous : new boolean[] {false, true}) {
            AssertionError fatal = new AssertionError("fatal test marker");
            List<String> invoked = new ArrayList<>();
            var request = RadminBatchOperations.run(List.of("first", "second"), target -> {
                invoked.add(target);
                if (asynchronous) return CompletableFuture.failedFuture(new CompletionException(fatal));
                throw fatal;
            });
            try {
                request.join();
                throw new AssertionError("Fatal errors must not be reported as ordinary request failures");
            } catch (CompletionException expected) {
                Throwable cause = expected;
                while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                require(cause == fatal, "the fatal error is preserved");
            }
            equal(invoked, List.of("first"), "fatal errors prevent further writes");
        }
    }

    private static void equal(Object actual, Object expected, String message) {
        require(actual.equals(expected), message + ": expected=" + expected + ", actual=" + actual);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
