import dev.radminlan.config.RadminConfig;
import dev.radminlan.network.PipeRadminBackend;
import dev.radminlan.network.RadminBackend;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Read-only live service check. Emits aggregate counts only. */
class NativeTelemetrySmoke {
    public static void main(String[] args) throws Exception {
        Path workDir = Path.of(System.getProperty("uiRegression.workDir", ".tmp/ui-regression")).toAbsolutePath();
        Path isolatedGameDir = Files.createTempDirectory(workDir, "telemetry-smoke-");
        FabricLoader loader = FabricLoader.getInstance();
        Method setGameDir = loader.getClass().getDeclaredMethod("setGameDir", Path.class);
        setGameDir.setAccessible(true);
        setGameDir.invoke(loader, isolatedGameDir);
        RadminConfig config = new RadminConfig();
        config.pipeTimeoutMs = 1500;
        try (PipeRadminBackend backend = new PipeRadminBackend(config)) {
            long startedAt = System.nanoTime();
            RadminBackend.RadminSnapshot initial = backend.snapshot().get(4, TimeUnit.SECONDS);
            System.out.println("NATIVE_TELEMETRY_INITIAL durationMs="
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) + " state=" + initial.state());
            if (initial.state() != RadminBackend.ServiceState.READY && initial.state() != RadminBackend.ServiceState.DISABLED) {
                throw new AssertionError("Installed Radmin service did not return a complete initial state: " + initial.message());
            }
            RadminBackend.RadminSnapshot previous = initial;
            int changes = 0;
            int reused = 0;
            long firstChangeMs = -1;
            long observationStarted = System.nanoTime();
            long deadline = observationStarted + TimeUnit.SECONDS.toNanos(4);
            // No further snapshot requests: every observed change comes from
            // the existing subscription and latestSnapshot's local cache.
            while (System.nanoTime() < deadline) {
                Thread.sleep(25);
                RadminBackend.RadminSnapshot current = backend.latestSnapshot();
                if (current == null) throw new AssertionError("Live cache unexpectedly disappeared");
                if (current != previous) {
                    changes++;
                    if (firstChangeMs < 0) firstChangeMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - observationStarted);
                    previous = current;
                } else reused++;
            }
            System.out.println("NATIVE_TELEMETRY_STREAM explicitSnapshots=1 observationMs=4000 liveChanges=" + changes
                    + " unchangedPolls=" + reused + " firstChangeMs=" + firstChangeMs);
            int sample = 0;
            for (RadminBackend.RadminSnapshot snapshot : java.util.List.of(initial, previous)) {
                Map<Long, RadminBackend.Peer> peers = new LinkedHashMap<>();
                snapshot.networks().forEach(network -> network.peers().forEach(peer -> peers.put(peer.nodeId(), peer)));
                long online = peers.values().stream().filter(RadminBackend.Peer::online).count();
                long offline = peers.values().stream().filter(peer -> "offline".equals(peer.state())).count();
                long unknown = peers.values().stream().filter(peer -> "unknown".equals(peer.state())).count();
                long known = peers.values().stream().filter(peer -> peer.online() && peer.latencyMs() >= 0).count();
                var summary = peers.values().stream().filter(peer -> peer.online() && peer.latencyMs() >= 0)
                        .mapToLong(RadminBackend.Peer::latencyMs).summaryStatistics();
                System.out.println("NATIVE_TELEMETRY_SAMPLE sample=" + sample + " state=" + snapshot.state()
                        + " networks=" + snapshot.networks().size() + " peers=" + peers.size() + " online=" + online
                        + " offline=" + offline + " unknown=" + unknown + " knownLatency=" + known
                        + " min=" + (summary.getCount() == 0 ? -1 : summary.getMin())
                        + " max=" + (summary.getCount() == 0 ? -1 : summary.getMax()));
                sample++;
            }
        }
        checkUnavailableSubscription();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (updateThreads() != 0 && System.nanoTime() < deadline) Thread.sleep(20);
        long alive = updateThreads();
        System.out.println("NATIVE_TELEMETRY_CLOSED updateThreads=" + alive);
        if (alive != 0) throw new AssertionError("Read-only update subscription did not stop");
    }

    private static void checkUnavailableSubscription() throws Exception {
        RadminConfig missing = new RadminConfig();
        missing.pipeName = "\\\\.\\pipe\\radminlan-missing-" + java.util.UUID.randomUUID();
        try (PipeRadminBackend backend = new PipeRadminBackend(missing)) {
            RadminBackend.RadminSnapshot unavailable = backend.snapshot().get(3, TimeUnit.SECONDS);
            if (unavailable.state() != RadminBackend.ServiceState.UNAVAILABLE
                    || !unavailable.message().contains("Win32 2")) {
                throw new AssertionError("An unopened subscription masked the query diagnostic: " + unavailable.message());
            }
            Thread.sleep(600);
            if (backend.latestSnapshot() != unavailable) {
                throw new AssertionError("Failed subscription retries invalidated an unrelated completed query");
            }
            System.out.println("NATIVE_TELEMETRY_MISSING_PIPE diagnosticPreserved=true retryInvalidation=false");
        }
    }

    private static long updateThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.isAlive() && thread.getName().equals("radminlan-pipe-updates")).count();
    }
}
