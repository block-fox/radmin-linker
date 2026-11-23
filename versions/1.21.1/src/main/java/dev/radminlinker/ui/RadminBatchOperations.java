package dev.radminlinker.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/** Runs service writes sequentially and retains the outcome of each target. */
public final class RadminBatchOperations {
    private RadminBatchOperations() {}

    public record Result<T>(List<T> succeeded, List<T> failed) {
        public Result {
            succeeded = List.copyOf(succeeded);
            failed = List.copyOf(failed);
        }

        public boolean allSucceeded() { return failed.isEmpty(); }
    }

    public static <T> CompletableFuture<Result<T>> run(List<T> targets,
            Function<T, CompletableFuture<Boolean>> action) {
        List<T> stableTargets = List.copyOf(targets);
        Objects.requireNonNull(action, "action");
        List<T> succeeded = new ArrayList<>();
        List<T> failed = new ArrayList<>();
        CompletableFuture<Void> sequence = CompletableFuture.completedFuture(null);
        for (T target : stableTargets) {
            sequence = sequence.thenCompose(ignored -> invoke(action, target).handle((ok, error) -> {
                propagateError(error);
                (error == null && Boolean.TRUE.equals(ok) ? succeeded : failed).add(target);
                return null;
            }));
        }
        return sequence.thenApply(ignored -> new Result<>(succeeded, failed));
    }

    private static <T> CompletableFuture<Boolean> invoke(Function<T, CompletableFuture<Boolean>> action,
                                                          T target) {
        try {
            CompletableFuture<Boolean> request = action.apply(target);
            return request == null ? CompletableFuture.completedFuture(false) : request;
        } catch (RuntimeException error) {
            propagateError(error);
            return CompletableFuture.completedFuture(false);
        }
    }

    /** Recover ordinary request failures without disguising fatal JVM errors as a rejected request. */
    private static void propagateError(Throwable error) {
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        if (error instanceof Error fatal) throw fatal;
    }
}
