package dev.radminlan.network;

import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.function.Supplier;

/** Uses the pipe first and falls back to the configured CLI when the pipe is unavailable. */
final class FallbackRadminBackend implements RadminBackend {
    private final RadminBackend primary;
    private final RadminBackend fallback;
    private volatile RadminBackend snapshotSource;

    FallbackRadminBackend(RadminBackend primary, RadminBackend fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public CompletableFuture<RadminSnapshot> snapshot() {
        return readSnapshot(primary).thenCompose(result -> {
            if (result.state() == ServiceState.READY || result.state() == ServiceState.DISABLED) {
                snapshotSource = primary;
                return CompletableFuture.completedFuture(result);
            }
            return readSnapshot(fallback).thenApply(cli -> {
                snapshotSource = cli.state() == ServiceState.READY ? fallback : primary;
                return cli.state() == ServiceState.READY ? cli : result;
            });
        });
    }

    @Override public RadminSnapshot latestSnapshot() {
        RadminBackend source = snapshotSource;
        return source == null ? null : source.latestSnapshot();
    }

    @Override public CompletableFuture<Boolean> leaveNetwork(String name) {
        if (name == null || name.isBlank()) return CompletableFuture.completedFuture(false);
        return leaveWithFallback(() -> primary.leaveNetwork(name), () -> fallback.leaveNetwork(name));
    }

    @Override public CompletableFuture<Boolean> leaveNetwork(Network network) {
        if (network == null) return CompletableFuture.completedFuture(false);
        return leaveWithFallback(() -> primary.leaveNetwork(network), () -> fallback.leaveNetwork(network));
    }

    @Override public CompletableFuture<Boolean> leaveNetworkByGuid(String guid) {
        if (guid == null || guid.isBlank()) return CompletableFuture.completedFuture(false);
        // A GUID alone cannot be translated to the CLI's network-name argument.
        return leaveWithFallback(() -> primary.leaveNetworkByGuid(guid), () -> fallback.leaveNetworkByGuid(guid));
    }

    private static CompletableFuture<Boolean> leaveWithFallback(Supplier<CompletableFuture<Boolean>> first,
                                                                 Supplier<CompletableFuture<Boolean>> second) {
        return attemptLeave(first).thenCompose(ok -> ok ? CompletableFuture.completedFuture(true) : attemptLeave(second));
    }

    private static CompletableFuture<Boolean> attemptLeave(Supplier<CompletableFuture<Boolean>> request) {
        try {
            CompletableFuture<Boolean> result = request.get();
            return result == null ? CompletableFuture.completedFuture(false)
                    : result.handle((ok, error) -> error == null && Boolean.TRUE.equals(ok));
        } catch (RuntimeException ignored) {
            return CompletableFuture.completedFuture(false);
        }
    }

    private static CompletableFuture<RadminSnapshot> readSnapshot(RadminBackend backend) {
        try {
            CompletableFuture<RadminSnapshot> request = backend.snapshot();
            if (request != null) return request.handle((result, error) -> error == null && result != null
                    ? result : RadminSnapshot.unavailable("Radmin snapshot unavailable"));
        } catch (RuntimeException ignored) {
        }
        return CompletableFuture.completedFuture(RadminSnapshot.unavailable("Radmin snapshot unavailable"));
    }

    @Override public CompletableFuture<Boolean> createNetwork(String name, String password) {
        return primary.createNetwork(name, password).thenCompose(ok -> ok
                ? CompletableFuture.completedFuture(true)
                : fallback.createNetwork(name, password));
    }

    @Override public CompletableFuture<List<String>> publicNetworks(String query) {
        return primary.publicNetworks(query)
                .exceptionallyCompose(error -> fallback.publicNetworks(query));
    }

    @Override public CompletableFuture<Boolean> setPower(boolean enabled) {
        return primary.setPower(enabled).thenCompose(ok -> ok
                ? CompletableFuture.completedFuture(true)
                : fallback.setPower(enabled));
    }

    @Override public CompletableFuture<Boolean> setAlias(String alias) {
        return primary.setAlias(alias).thenCompose(ok -> ok
                ? CompletableFuture.completedFuture(true) : fallback.setAlias(alias));
    }

    @Override public CompletableFuture<Boolean> joinNetwork(String name, String password) {
        return primary.joinNetwork(name, password).thenCompose(ok -> ok
                ? CompletableFuture.completedFuture(true)
                : fallback.joinNetwork(name, password));
    }

    @Override public CompletableFuture<Boolean> setNetworkEnabled(String guid, boolean enabled) {
        return primary.setNetworkEnabled(guid, enabled).thenCompose(ok -> ok
                ? CompletableFuture.completedFuture(true)
                : fallback.setNetworkEnabled(guid, enabled));
    }

    @Override public CompletableFuture<Boolean> deleteNetwork(String guid) {
        return primary.deleteNetwork(guid).thenCompose(ok -> ok
                ? CompletableFuture.completedFuture(true)
                : fallback.deleteNetwork(guid));
    }

    @Override public CompletableFuture<Boolean> kickPeer(String guid, long nodeId, String networkName) {
        return primary.kickPeer(guid, nodeId, networkName).thenCompose(ok -> ok
                ? CompletableFuture.completedFuture(true)
                : fallback.kickPeer(guid, nodeId, networkName));
    }

    @Override public boolean supportsWrite() { return fallback.supportsWrite() || primary.supportsWrite(); }
    @Override public void close() { primary.close(); fallback.close(); }
}
