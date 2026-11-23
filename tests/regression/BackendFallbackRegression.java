import dev.radminlan.config.RadminConfig;
import dev.radminlan.network.RadminBackend;
import dev.radminlan.network.RadminBackends;
import dev.radminlan.network.RadminCoreProvider;
import dev.radminlan.network.WindowsCliBackend;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;

/** Transport routing checks use in-memory backends and never send Radmin commands. */
public final class BackendFallbackRegression {
    private static final String GUID = "00112233445566778899aabbccddeeff";
    private static final RadminBackend.Network NETWORK = new RadminBackend.Network("Friends", GUID, "", List.of());

    public static void check() throws Exception {
        checkBackendSelection();
        checkDefaultAddressing();
        checkLeaveFallback();
        checkSnapshotFallback();
        checkLatestSnapshotRouting();
        checkCliAvailability();
        System.out.println("BACKEND_FALLBACK_REGRESSION_OK nameRouting=true guidRouting=true liveRouting=true failures=true closed=true");
    }

    private static void checkBackendSelection() {
        boolean embedded = ServiceLoader.load(RadminCoreProvider.class, RadminCoreProvider.class.getClassLoader())
                .findFirst().isPresent();
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        RadminConfig config = new RadminConfig();
        // No Windows pipe or external CLI is allowed to participate in this test.
        config.pipeName = "";
        config.enableCli = false;
        String windowsDiagnostic = windows ? "CLI integration disabled in config" : "screen.radminlan.core.windows_only";
        for (var mode : RadminConfig.ConnectionBackend.values()) {
            config.connectionBackend = mode;
            try (var backend = RadminBackends.create(config)) {
                var snapshot = backend.snapshot().join();
                if (embedded && mode != RadminConfig.ConnectionBackend.WINDOWS) {
                    require("RadminLAN Core".equals(snapshot.transport()), "bundled core selected for " + mode);
                } else {
                    boolean useWindows = mode == RadminConfig.ConnectionBackend.WINDOWS
                            || mode == RadminConfig.ConnectionBackend.AUTO && windows;
                    String message = useWindows ? windowsDiagnostic : "screen.radminlan.core.not_embedded";
                    require(message.equals(snapshot.message()), "expected backend diagnostic for " + mode);
                }
            }
        }
        System.out.println("BACKEND_SELECTION_REGRESSION_OK embedded=" + embedded + " windows=" + windows);
    }

    private static void checkDefaultAddressing() {
        FakeBackend backend = new FakeBackend();
        backend.writeResult = CompletableFuture.completedFuture(true);
        require(backend.leaveNetwork(NETWORK).join(), "network with GUID uses the GUID API");
        require(backend.calls.equals(List.of("guid:" + GUID)), "GUID address remains intact");
        backend.calls.clear();
        require(backend.leaveNetwork(new RadminBackend.Network("Friends", "", "", List.of())).join(),
                "CLI snapshot without a GUID uses the name API");
        require(backend.calls.equals(List.of("name:Friends")), "name-only address remains intact");
        backend.calls.clear();
        require(!backend.leaveNetwork((RadminBackend.Network) null).join(), "missing network is rejected");
        require(!backend.leaveNetwork(new RadminBackend.Network(" ", null, "", List.of())).join(),
                "missing network identity is rejected");
        require(backend.calls.isEmpty(), "missing identity performs no transport operation");
    }

    private static void checkLeaveFallback() throws Exception {
        FakeBackend primary = new FakeBackend();
        FakeBackend secondary = nameBackend();
        RadminBackend backend = fallback(primary, secondary);
        require(backend.leaveNetwork(NETWORK).join(), "name transport handles a failed GUID transport");
        require(primary.calls.equals(List.of("guid:" + GUID)), "primary receives the GUID");
        require(secondary.calls.equals(List.of("name:Friends")), "fallback receives the network name, never its GUID");

        primary.calls.clear();
        secondary.calls.clear();
        require(!backend.leaveNetworkByGuid(GUID).join(), "GUID-only legacy API cannot invent a CLI network name");
        require(secondary.calls.equals(List.of("guid:" + GUID)), "legacy fallback uses only the GUID API");
        primary.calls.clear();
        secondary.calls.clear();
        require(!backend.leaveNetworkByGuid("").join() && !backend.leaveNetworkByGuid(null).join(),
                "empty legacy GUID is rejected without throwing");
        require(primary.calls.isEmpty() && secondary.calls.isEmpty(), "empty GUID performs no transport operation");

        for (int failure = 0; failure < 4; failure++) {
            primary.calls.clear();
            secondary.calls.clear();
            primary.throwWrite = failure == 0;
            primary.writeResult = switch (failure) {
                case 1 -> CompletableFuture.failedFuture(new IllegalStateException("pipe disconnected"));
                case 2 -> CompletableFuture.completedFuture(null);
                default -> null;
            };
            require(backend.leaveNetwork(NETWORK).join(), "transport failure falls back safely: " + failure);
            require(secondary.calls.equals(List.of("name:Friends")), "failure retains the correct fallback name: " + failure);
        }
        primary.throwWrite = false;
        primary.writeResult = CompletableFuture.completedFuture(true);
        secondary.calls.clear();
        require(backend.leaveNetwork(NETWORK).join() && secondary.calls.isEmpty(), "primary success does not repeat the command");

        primary.writeResult = CompletableFuture.completedFuture(false);
        secondary.throwWrite = true;
        require(!backend.leaveNetwork(NETWORK).join(), "both transports failing returns false");
        require(!backend.leaveNetwork((RadminBackend.Network) null).join(), "fallback rejects a missing network");
    }

    private static void checkSnapshotFallback() throws Exception {
        FakeBackend primary = new FakeBackend();
        FakeBackend secondary = new FakeBackend();
        RadminBackend.RadminSnapshot ready = snapshot(RadminBackend.ServiceState.READY, "CLI");
        secondary.snapshotResult = CompletableFuture.completedFuture(ready);
        RadminBackend backend = fallback(primary, secondary);
        for (int failure = 0; failure < 5; failure++) {
            primary.throwSnapshot = failure == 0;
            primary.snapshotResult = switch (failure) {
                case 1 -> CompletableFuture.failedFuture(new IllegalStateException("pipe disconnected"));
                case 2 -> CompletableFuture.completedFuture(null);
                case 3 -> null;
                default -> CompletableFuture.completedFuture(RadminBackend.RadminSnapshot.unavailable("Pipe unavailable"));
            };
            require(backend.snapshot().join() == ready, "failed or missing primary snapshot uses fallback: " + failure);
        }
        primary.throwSnapshot = false;
        for (RadminBackend.ServiceState state : List.of(RadminBackend.ServiceState.READY, RadminBackend.ServiceState.DISABLED)) {
            RadminBackend.RadminSnapshot authoritative = snapshot(state, "Pipe");
            primary.snapshotResult = CompletableFuture.completedFuture(authoritative);
            secondary.snapshotCalls = 0;
            require(backend.snapshot().join() == authoritative && secondary.snapshotCalls == 0,
                    "available primary state stays authoritative: " + state);
        }
        RadminBackend.RadminSnapshot unavailable = RadminBackend.RadminSnapshot.unavailable("Pipe diagnostic");
        primary.snapshotResult = CompletableFuture.completedFuture(unavailable);
        secondary.throwSnapshot = true;
        require(backend.snapshot().join() == unavailable, "fallback failure preserves the primary diagnostic");
        primary.writable = true;
        require(backend.supportsWrite(), "primary write capability is exposed");
        primary.writable = false;
        secondary.writable = true;
        require(backend.supportsWrite(), "fallback write capability is exposed");
        backend.close();
        require(!backend.supportsWrite(), "closed transports expose no write capability");
    }

    private static void checkCliAvailability() throws Exception {
        RadminConfig config = new RadminConfig();
        Path fixture = Files.createTempFile("radminlan-cli-capability-", ".fixture");
        WindowsCliBackend cli = new WindowsCliBackend(config);
        try {
            require(!cli.supportsWrite(), "an unconfigured CLI has no write capability");
            config.radminExecutable = fixture.toString();
            require(cli.supportsWrite(), "configured existing CLI exposes its command capability");
            config.enableCli = false;
            require(!cli.supportsWrite(), "disabled CLI immediately removes write capability");
            require(!cli.leaveNetwork(NETWORK).join(), "disabled CLI rejects a write without launching a process");
            require(cli.snapshot().join().state() == RadminBackend.ServiceState.UNAVAILABLE,
                    "disabled CLI rejects a query without launching a process");
            config.enableCli = true;
            config.radminExecutable = "\u0000invalid-path";
            require(!cli.supportsWrite(), "invalid configured path is safely unavailable");
            config.radminExecutable = fixture.toString();
            cli.close();
            require(!cli.supportsWrite() && !cli.leaveNetwork(NETWORK).join(),
                    "closed CLI returns false instead of rejecting executor submission");
        } finally {
            cli.close();
            Files.deleteIfExists(fixture);
        }
    }

    private static void checkLatestSnapshotRouting() throws Exception {
        FakeBackend primary = new FakeBackend();
        FakeBackend secondary = new FakeBackend();
        RadminBackend backend = fallback(primary, secondary);
        primary.live = snapshot(RadminBackend.ServiceState.READY, "Unselected pipe subscription");
        secondary.live = snapshot(RadminBackend.ServiceState.READY, "Unselected fallback subscription");
        requireLatest(backend, primary, secondary, null,
                "subscription views remain unavailable until an explicit request selects their source");

        CompletableFuture<RadminBackend.RadminSnapshot> firstRequest = new CompletableFuture<>();
        primary.snapshotResult = firstRequest;
        var pending = backend.snapshot();
        requireLatest(backend, primary, secondary, null,
                "a pending first request does not publish an unconfirmed source");
        firstRequest.complete(primary.live);
        require(pending.join() == primary.live, "the first ready snapshot selects the primary transport");

        for (RadminBackend.ServiceState state : List.of(RadminBackend.ServiceState.READY, RadminBackend.ServiceState.DISABLED)) {
            RadminBackend.RadminSnapshot response = snapshot(state, "Pipe response");
            primary.snapshotResult = CompletableFuture.completedFuture(response);
            primary.live = response;
            int fallbackCalls = secondary.snapshotCalls;
            require(backend.snapshot().join() == response && secondary.snapshotCalls == fallbackCalls,
                    "an available primary snapshot keeps the fallback idle: " + state);
            RadminBackend.RadminSnapshot changed = snapshot(state, "Updated pipe subscription");
            primary.live = changed;
            requireLatest(backend, primary, secondary, changed,
                    "primary subscription changes are visible without another request: " + state);
        }

        RadminBackend.RadminSnapshot cliResponse = snapshot(RadminBackend.ServiceState.READY, "CLI response");
        primary.snapshotResult = CompletableFuture.completedFuture(RadminBackend.RadminSnapshot.unavailable("Pipe disconnected"));
        secondary.snapshotResult = CompletableFuture.completedFuture(cliResponse);
        secondary.live = null;
        require(backend.snapshot().join() == cliResponse, "an unavailable pipe selects the ready fallback");
        primary.live = snapshot(RadminBackend.ServiceState.READY, "Stale pipe subscription");
        requireLatest(backend, primary, secondary, null,
                "a CLI source without a live view cannot expose the primary subscription cache");
        RadminBackend.RadminSnapshot fallbackUpdate = snapshot(RadminBackend.ServiceState.READY, "Updated fallback subscription");
        secondary.live = fallbackUpdate;
        requireLatest(backend, primary, secondary, fallbackUpdate,
                "a supported fallback subscription remains authoritative after routing to fallback");
        primary.live = snapshot(RadminBackend.ServiceState.READY, "Recovered but unselected pipe subscription");
        requireLatest(backend, primary, secondary, fallbackUpdate,
                "an unsolicited primary update cannot overwrite a selected fallback source");

        RadminBackend.RadminSnapshot recovered = snapshot(RadminBackend.ServiceState.READY, "Recovered pipe response");
        primary.live = recovered;
        primary.snapshotResult = CompletableFuture.completedFuture(recovered);
        int fallbackCalls = secondary.snapshotCalls;
        require(backend.snapshot().join() == recovered && secondary.snapshotCalls == fallbackCalls,
                "an explicit successful primary refresh switches routing back without querying fallback");
        secondary.live = snapshot(RadminBackend.ServiceState.READY, "Stale fallback subscription");
        requireLatest(backend, primary, secondary, recovered,
                "the recovered primary stays authoritative over later fallback updates");
    }

    private static void requireLatest(RadminBackend backend, FakeBackend primary, FakeBackend secondary,
                                      RadminBackend.RadminSnapshot expected, String message) {
        int primaryCalls = primary.snapshotCalls;
        int fallbackCalls = secondary.snapshotCalls;
        require(backend.latestSnapshot() == expected, message);
        require(backend.latestSnapshot() == expected, "repeated local reads preserve the selected source");
        require(primary.snapshotCalls == primaryCalls && secondary.snapshotCalls == fallbackCalls,
                "latestSnapshot reads never start a transport request");
    }

    private static FakeBackend nameBackend() {
        return new FakeBackend() {
            @Override public CompletableFuture<Boolean> leaveNetwork(Network network) {
                return network == null ? CompletableFuture.completedFuture(false) : leaveNetwork(network.name());
            }
            @Override public CompletableFuture<Boolean> leaveNetwork(String name) {
                calls.add("name:" + name);
                if (throwWrite) throw new IllegalStateException("CLI unavailable");
                return CompletableFuture.completedFuture("Friends".equals(name));
            }
        };
    }

    private static RadminBackend fallback(RadminBackend primary, RadminBackend secondary) throws Exception {
        Class<?> type = Class.forName("dev.radminlan.network.FallbackRadminBackend");
        Constructor<?> constructor = type.getDeclaredConstructor(RadminBackend.class, RadminBackend.class);
        constructor.setAccessible(true);
        return (RadminBackend) constructor.newInstance(primary, secondary);
    }

    private static RadminBackend.RadminSnapshot snapshot(RadminBackend.ServiceState state, String transport) {
        return new RadminBackend.RadminSnapshot(state, "", "", List.of(), "", true, transport);
    }

    private static class FakeBackend implements RadminBackend {
        final List<String> calls = new ArrayList<>();
        CompletableFuture<Boolean> writeResult = CompletableFuture.completedFuture(false);
        CompletableFuture<RadminSnapshot> snapshotResult = CompletableFuture.completedFuture(RadminSnapshot.unavailable("Pipe unavailable"));
        RadminSnapshot live;
        boolean throwWrite;
        boolean throwSnapshot;
        boolean writable;
        int snapshotCalls;
        @Override public CompletableFuture<RadminSnapshot> snapshot() {
            snapshotCalls++;
            if (throwSnapshot) throw new IllegalStateException("Pipe unavailable");
            return snapshotResult;
        }
        @Override public RadminSnapshot latestSnapshot() { return live; }
        @Override public CompletableFuture<Boolean> createNetwork(String name, String password) { return writeResult; }
        @Override public CompletableFuture<Boolean> joinNetwork(String name, String password) { return writeResult; }
        @Override public CompletableFuture<Boolean> leaveNetwork(String name) {
            calls.add("name:" + name);
            if (throwWrite) throw new IllegalArgumentException("Network name unavailable");
            return writeResult;
        }
        @Override public CompletableFuture<Boolean> leaveNetworkByGuid(String guid) {
            calls.add("guid:" + guid);
            if (throwWrite) throw new IllegalArgumentException("Invalid GUID");
            return writeResult;
        }
        @Override public boolean supportsWrite() { return writable; }
        @Override public void close() { writable = false; }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
